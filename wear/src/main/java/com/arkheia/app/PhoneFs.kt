package com.arkheia.app

import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File
import java.io.IOException

/**
 * Your phone's files, reached through Syndesia: Syndesia on the watch carries the requests over
 * Bluetooth to Syndesia on the phone, which reads its storage.
 */
object PhoneFs {
    const val SYNDESIA = "com.syndesia.app"
    private const val AUTHORITY = "com.syndesia.app.phonefiles"

    class Failure(val code: String, message: String) : IOException(message)

    data class Listing(val path: String, val root: String, val entries: List<Entry>)

    fun syndesiaInstalled(c: Context): Boolean =
        runCatching { c.packageManager.getPackageInfo(SYNDESIA, 0); true }.getOrDefault(false)

    fun available(c: Context): Boolean =
        c.packageManager.resolveContentProvider(AUTHORITY, 0) != null

    /** Lists a phone folder ("" = the phone's main storage). Slow: call off the main thread. */
    fun list(c: Context, path: String, showHidden: Boolean, sort: Sort, foldersFirst: Boolean): Listing {
        if (!available(c)) throw Failure("nosyndesia", "Update Syndesia on your watch to browse your phone.")
        val uri = Uri.parse("content://$AUTHORITY/list").buildUpon()
            .appendQueryParameter("path", path)
            .appendQueryParameter("hidden", if (showHidden) "1" else "0")
            .build()
        val cursor = runCatching { c.contentResolver.query(uri, null, null, null, null) }
            .getOrElse { throw Failure("nosyndesia", "Couldn't reach Syndesia on your watch.") }
            ?: throw Failure("nosyndesia", "Couldn't reach Syndesia on your watch.")
        cursor.use { cur ->
            val ex = cur.extras
            ex.getString("error")?.let { throw Failure(it, ex.getString("message") ?: "Couldn't read your phone's files.") }
            val dir = ex.getString("path") ?: path
            val out = ArrayList<Entry>(cur.count)
            while (cur.moveToNext()) {
                val name = cur.getString(0)
                val isDir = cur.getInt(1) == 1
                out.add(
                    Entry(
                        file = File(dir, name),
                        isDir = isDir,
                        size = cur.getLong(2),
                        modified = cur.getLong(3),
                        children = cur.getInt(4),
                        kind = if (isDir) Kind.Folder else Fs.kindOfName(name),
                    )
                )
            }
            return Listing(dir, ex.getString("root") ?: dir, Fs.sorted(out, sort, foldersFirst))
        }
    }

    /** Where files copied from the phone land on the watch. */
    val inbox: File get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "From phone")

    /**
     * Copies a phone file onto the watch (Download/From phone), reporting bytes as they arrive.
     * Returns the saved file.
     */
    fun copyToWatch(c: Context, remote: Entry, progress: (Long) -> Unit): File {
        val dir = inbox.apply { mkdirs() }
        var dest = File(dir, remote.name)
        if (dest.exists() && dest.length() == remote.size) return dest // already copied
        if (dest.exists()) {
            val base = remote.name.substringBeforeLast('.', remote.name)
            val ext = remote.name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            var n = 2
            while (dest.exists()) dest = File(dir, "$base ($n)$ext").also { n++ }
        }
        val uri = Uri.parse("content://$AUTHORITY/file").buildUpon().appendQueryParameter("path", remote.path).build()
        val part = File(dir, ".${dest.name}.part")
        try {
            val input = c.contentResolver.openInputStream(uri) ?: throw Failure("nosyndesia", "Couldn't reach Syndesia on your watch.")
            input.use { inp ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        progress(total)
                    }
                }
            }
            if (remote.size >= 0 && part.length() != remote.size) throw Failure("partial", "Only part of ${remote.name} arrived.")
            if (!part.renameTo(dest)) throw Failure("save", "Couldn't save ${remote.name} on the watch.")
            Fs.changed()
            return dest
        } catch (e: Failure) {
            part.delete(); throw e
        } catch (e: Exception) {
            part.delete(); throw Failure("copy", e.message ?: "Couldn't copy ${remote.name} from your phone.")
        }
    }
}
