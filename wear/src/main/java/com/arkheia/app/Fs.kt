package com.arkheia.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.PriorityQueue

enum class Kind(val label: String) {
    Folder("Folder"), Image("Image"), Audio("Audio"), Video("Video"), Text("Text"),
    Document("Document"), Archive("Archive"), Apk("App"), Other("File"),
}

data class Entry(
    val file: File,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    /** Items inside a folder (-1 when it can't be read). */
    val children: Int,
    val kind: Kind,
) {
    val name: String get() = file.name
    val path: String get() = file.path
}

data class StorageInfo(val total: Long, val free: Long) {
    val used get() = (total - free).coerceAtLeast(0)
    val fraction get() = if (total > 0) used.toFloat() / total else 0f
}

data class Analysis(
    val byKind: Map<Kind, Long>,
    val countByKind: Map<Kind, Int>,
    val largest: List<Entry>,
    val files: Int,
    val folders: Int,
)

/** Everything that touches the file system. Call the slow ones off the main thread. */
object Fs {
    val root: File get() = Environment.getExternalStorageDirectory()

    /** Bumped after every change, so open folders reload. */
    val version = MutableStateFlow(0)
    fun changed() { version.value++ }

    // ------------------------------------------------------------ Access

    private lateinit var app: Context
    fun init(c: Context) { app = c.applicationContext }

    /** The "Files" permission the watch shows in App info (read + write storage). */
    val filePermissions = arrayOf(
        android.Manifest.permission.READ_EXTERNAL_STORAGE,
        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
    )

    fun hasFilesPermission(c: Context = app): Boolean = filePermissions.all {
        androidx.core.content.ContextCompat.checkSelfPermission(c, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun hasAccess(): Boolean =
        Environment.isExternalStorageManager() || (::app.isInitialized && hasFilesPermission())

    /** Arkheia's App info page, where the Files permission lives. */
    fun openAppInfo(c: Context): Boolean = try {
        c.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: Exception) {
        false
    }

    // ------------------------------------------------------------ Types

    private val images = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
    private val audio = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav", "amr", "3gpp", "mid", "midi", "wma")
    private val video = setOf("mp4", "mkv", "webm", "3gp", "mov", "avi", "m4v", "ts")
    private val text = setOf(
        "txt", "md", "log", "json", "xml", "csv", "tsv", "ini", "cfg", "conf", "yml", "yaml", "toml",
        "properties", "html", "htm", "css", "js", "kt", "java", "py", "sh", "c", "h", "cpp", "srt", "vtt", "gradle", "kts",
    )
    private val docs = setOf("pdf", "doc", "docx", "odt", "rtf", "xls", "xlsx", "ods", "ppt", "pptx", "odp", "epub")
    private val archives = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz")

    fun kindOf(f: File): Kind = if (f.isDirectory) Kind.Folder else kindOfName(f.name)

    fun kindOfName(name: String): Kind {
        val e = name.substringAfterLast('.', "").lowercase()
        return when (e) {
            in images -> Kind.Image
            in audio -> Kind.Audio
            in video -> Kind.Video
            in text -> Kind.Text
            in docs -> Kind.Document
            in archives -> Kind.Archive
            "apk" -> Kind.Apk
            else -> Kind.Other
        }
    }

    fun mime(f: File): String {
        val e = f.extension.lowercase()
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(e)?.let { return it }
        return when (kindOf(f)) {
            Kind.Text -> "text/plain"
            Kind.Image -> "image/*"
            Kind.Audio -> "audio/*"
            Kind.Video -> "video/*"
            Kind.Apk -> "application/vnd.android.package-archive"
            else -> "*/*"
        }
    }

    // ------------------------------------------------------------ Listing

    private fun visible(name: String, showHidden: Boolean) = showHidden || !name.startsWith(".")

    fun entry(f: File, showHidden: Boolean = true): Entry {
        val dir = f.isDirectory
        return Entry(
            file = f,
            isDir = dir,
            size = if (dir) 0 else f.length(),
            modified = f.lastModified(),
            children = if (dir) f.list()?.count { visible(it, showHidden) } ?: -1 else 0,
            kind = if (dir) Kind.Folder else kindOfName(f.name),
        )
    }

    /** The folder's contents, sorted; null if it can't be read. */
    fun list(dir: File, showHidden: Boolean, sort: Sort, foldersFirst: Boolean): List<Entry>? {
        val files = dir.listFiles() ?: return null
        val entries = files.filter { visible(it.name, showHidden) }.map { entry(it, showHidden) }
        val byName = Comparator<Entry> { a, b -> a.name.compareTo(b.name, ignoreCase = true) }
        val order: Comparator<Entry> = when (sort) {
            Sort.Name -> byName
            Sort.Date -> compareByDescending<Entry> { it.modified }.then(byName)
            Sort.Size -> compareByDescending<Entry> { if (it.isDir) it.children.toLong() else it.size }.then(byName)
            Sort.Type -> compareBy<Entry> { it.kind.ordinal }
                .then(compareBy<Entry> { it.file.extension.lowercase() })
                .then(byName)
        }
        val full = if (foldersFirst) compareBy<Entry> { !it.isDir }.then(order) else order
        return entries.sortedWith(full)
    }

    fun storage(): StorageInfo = try {
        val s = StatFs(root.path)
        StorageInfo(s.totalBytes, s.availableBytes)
    } catch (e: Exception) {
        StorageInfo(0, 0)
    }

    /** Total bytes and file count under a folder. */
    suspend fun folderSize(dir: File): Pair<Long, Int> {
        var bytes = 0L
        var count = 0
        for (f in dir.walkTopDown()) {
            if (count % 200 == 0) currentCoroutineContext().ensureActive()
            if (f.isFile) {
                bytes += f.length(); count++
            }
        }
        return bytes to count
    }

    /** Where the space goes: by type, and the biggest files. */
    suspend fun analyze(progress: (Int) -> Unit): Analysis {
        val byKind = HashMap<Kind, Long>()
        val counts = HashMap<Kind, Int>()
        val top = PriorityQueue<Entry>(16, compareBy<Entry> { it.size })
        var files = 0
        var folders = 0
        for (f in root.walkTopDown()) {
            if (f.isDirectory) {
                folders++; continue
            }
            files++
            if (files % 250 == 0) {
                currentCoroutineContext().ensureActive()
                progress(files)
            }
            val size = f.length()
            val k = kindOfName(f.name)
            byKind[k] = (byKind[k] ?: 0L) + size
            counts[k] = (counts[k] ?: 0) + 1
            if (top.size < 15 || size > top.peek()!!.size) {
                top.add(Entry(f, false, size, f.lastModified(), 0, k))
                if (top.size > 15) top.poll()
            }
        }
        return Analysis(byKind, counts, top.sortedByDescending { it.size }, files, folders)
    }

    suspend fun search(query: String, showHidden: Boolean, limit: Int = 120): List<Entry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<Entry>()
        var n = 0
        val walk = root.walkTopDown().onEnter { it == root || visible(it.name, showHidden) }
        for (f in walk) {
            if (++n % 200 == 0) currentCoroutineContext().ensureActive()
            if (f == root || !visible(f.name, showHidden)) continue
            if (f.name.lowercase().contains(q)) {
                out += entry(f, showHidden)
                if (out.size >= limit) break
            }
        }
        return out.sortedWith(compareBy<Entry> { !it.name.lowercase().startsWith(q) }.then(compareBy<Entry> { !it.isDir }))
    }

    // ------------------------------------------------------------ Changes

    fun validName(name: String) = name.isNotBlank() && '/' !in name && name != "." && name != ".." && '\u0000' !in name

    /** Rename, keeping the old extension if the new name leaves it off. Null on failure. */
    fun rename(f: File, input: String): File? {
        var name = input.trim()
        if (!validName(name)) return null
        if (f.isFile && '.' !in name && f.extension.isNotEmpty()) name += ".${f.extension}"
        if (name == f.name) return f
        val target = File(f.parentFile, name)
        if (target.exists() && !target.path.equals(f.path, ignoreCase = true)) return null
        val ok = f.renameTo(target)
        if (ok) {
            Prefs.movePath(f.path, target.path); changed()
        }
        return if (ok) target else null
    }

    fun mkdir(parent: File, name: String): File? {
        val n = name.trim()
        if (!validName(n)) return null
        val d = File(parent, n)
        if (d.exists()) return null
        return if (d.mkdirs()) d.also { changed() } else null
    }

    fun delete(f: File): Boolean {
        val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
        if (ok || !f.exists()) Prefs.movePath(f.path, null)
        changed()
        return ok
    }

    /** A name in [dir] that doesn't clash: "photo (1).jpg", "photo (2).jpg"… */
    fun freeName(dir: File, name: String): File {
        var t = File(dir, name)
        if (!t.exists()) return t
        val dot = name.lastIndexOf('.')
        val (base, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        var i = 1
        while (t.exists()) {
            t = File(dir, "$base ($i)$ext"); i++
        }
        return t
    }

    fun isInside(child: File, parent: File): Boolean {
        val c = runCatching { child.canonicalPath }.getOrDefault(child.absolutePath)
        val p = runCatching { parent.canonicalPath }.getOrDefault(parent.absolutePath)
        return c == p || c.startsWith("$p/")
    }

    // ------------------------------------------------------------ Opening

    fun uri(c: Context, f: File): Uri = FileProvider.getUriForFile(c, "${c.packageName}.files", f)

    /** Hand the file to another app on the watch. */
    fun openWith(c: Context, f: File) {
        try {
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri(c, f), mime(f))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            c.startActivity(Intent.createChooser(i, f.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Prefs.addRecent(f.path)
        } catch (e: ActivityNotFoundException) {
            toast(c, "No app on your watch can open this")
        } catch (e: Exception) {
            toast(c, "Couldn't open: ${e.message ?: "unknown error"}")
        }
    }

    fun install(c: Context, f: File) {
        if (!c.packageManager.canRequestPackageInstalls()) {
            try {
                c.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                toast(c, "Allow Arkheia to install apps, then tap Install again")
            } catch (e: Exception) {
                toast(c, "Your watch doesn't let Arkheia install apps")
            }
            return
        }
        try {
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri(c, f), "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            c.startActivity(i)
        } catch (e: Exception) {
            toast(c, "Your watch has no installer for this. Install it with Syndesia instead.")
        }
    }

    fun toast(c: Context, msg: String) = Toast.makeText(c, msg, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------ Formatting

    fun bytes(b: Long): String = when {
        b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
        b >= 1L shl 10 -> "%.0f KB".format(b / (1L shl 10).toDouble())
        else -> "$b B"
    }

    fun date(ms: Long): String {
        if (ms <= 0) return "—"
        val now = System.currentTimeMillis()
        val pattern = when {
            now - ms < 20 * 3600_000L -> "HH:mm"
            SimpleDateFormat("yyyy", Locale.getDefault()).format(Date(ms)) ==
                SimpleDateFormat("yyyy", Locale.getDefault()).format(Date(now)) -> "d MMM"
            else -> "d MMM yyyy"
        }
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))
    }

    fun fullDate(ms: Long): String =
        if (ms <= 0) "—" else SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))

    /** "Download/Watch" rather than "/storage/emulated/0/Download/Watch". */
    fun pretty(path: String): String {
        val r = root.path
        return when {
            path == r -> "Internal storage"
            path.startsWith("$r/") -> path.removePrefix("$r/")
            else -> path
        }
    }

    fun items(n: Int) = when (n) {
        -1 -> "Locked"
        0 -> "Empty"
        1 -> "1 item"
        else -> "$n items"
    }
}
