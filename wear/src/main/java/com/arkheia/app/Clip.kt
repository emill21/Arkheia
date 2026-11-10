package com.arkheia.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Copy / move / zip: what's on the clipboard, and the long job in progress. */
object Clip {
    data class Content(val files: List<File>, val move: Boolean) {
        val label get() = if (files.size == 1) files[0].name else "${files.size} items"
    }

    sealed interface Op {
        data object Idle : Op
        /** [verb] is "Copying", "Moving", "Zipping"…; [done] of [total] steps. */
        data class Running(val verb: String, val done: Int, val total: Int, val current: String) : Op {
            val fraction get() = if (total > 0) done.toFloat() / total else 0f
        }
        data class Finished(val message: String, val ok: Boolean) : Op
    }

    val content = MutableStateFlow<Content?>(null)
    val op = MutableStateFlow<Op>(Op.Idle)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun copy(files: List<File>) { if (files.isNotEmpty()) content.value = Content(files, move = false) }
    fun cut(files: List<File>) { if (files.isNotEmpty()) content.value = Content(files, move = true) }
    fun copy(f: File) = copy(listOf(f))
    fun cut(f: File) = cut(listOf(f))
    fun clear() { content.value = null }
    fun busy() = op.value is Op.Running

    /** A folder can't be pasted into itself. */
    fun canPasteInto(dir: File): Boolean {
        val c = content.value ?: return false
        return c.files.none { it.isDirectory && Fs.isInside(dir, it) }
    }

    fun pasteInto(dir: File) {
        val c = content.value ?: return
        if (busy()) return
        val verb = if (c.move) "Moving" else "Copying"
        op.value = Op.Running(verb, 0, c.files.size, c.files[0].name)
        scope.launch {
            var ok = 0
            var failed = 0
            c.files.forEachIndexed { i, src ->
                op.value = Op.Running(verb, i, c.files.size, src.name)
                val success = runCatching {
                    when {
                        !src.exists() -> false
                        src.isDirectory && Fs.isInside(dir, src) -> false
                        c.move && src.parentFile?.path == dir.path -> true
                        c.move -> {
                            val target = Fs.freeName(dir, src.name)
                            val moved = src.renameTo(target) ||
                                (src.copyRecursively(target, overwrite = false) && src.deleteRecursively())
                            if (moved) Prefs.movePath(src.path, target.path)
                            moved
                        }
                        else -> src.copyRecursively(Fs.freeName(dir, src.name), overwrite = false)
                    }
                }.getOrDefault(false)
                if (success) ok++ else failed++
            }
            Fs.changed()
            if (c.move || failed == 0) content.value = null
            val past = if (c.move) "Moved" else "Copied"
            finish(if (failed == 0) "$past ${if (ok == 1) c.files[0].name else "$ok items"}" else "$past $ok, $failed failed", failed == 0)
        }
    }

    /** Delete several files and folders, with progress. */
    fun deleteAll(files: List<File>) {
        if (busy() || files.isEmpty()) return
        op.value = Op.Running("Deleting", 0, files.size, files[0].name)
        scope.launch {
            var failed = 0
            files.forEachIndexed { i, f ->
                op.value = Op.Running("Deleting", i, files.size, f.name)
                if (!Fs.delete(f) && f.exists()) failed++
            }
            Fs.changed()
            finish(if (failed == 0) "Deleted ${if (files.size == 1) files[0].name else "${files.size} items"}" else "$failed couldn't be deleted", failed == 0)
        }
    }

    /** Pack files and folders into one .zip next to the first of them. */
    fun zip(files: List<File>) {
        if (busy() || files.isEmpty()) return
        val dir = files[0].parentFile ?: return
        val name = if (files.size == 1) files[0].nameWithoutExtension.ifEmpty { files[0].name } else dir.name.ifEmpty { "Archive" }
        val target = Fs.freeName(dir, "$name.zip")
        op.value = Op.Running("Zipping", 0, 1, target.name)
        scope.launch {
            val all = files.flatMap { root -> root.walkTopDown().filter { it.isFile }.map { root to it }.toList() }
            val result = runCatching {
                ZipOutputStream(FileOutputStream(target).buffered()).use { zip ->
                    // Keep empty folders too.
                    files.filter { it.isDirectory }.forEach { root ->
                        root.walkTopDown().filter { it.isDirectory && it.list().isNullOrEmpty() }.forEach { d ->
                            zip.putNextEntry(ZipEntry(entryName(root, d) + "/"))
                            zip.closeEntry()
                        }
                    }
                    all.forEachIndexed { i, (root, f) ->
                        op.value = Op.Running("Zipping", i, all.size, f.name)
                        zip.putNextEntry(ZipEntry(entryName(root, f)).apply { time = f.lastModified() })
                        f.inputStream().buffered().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            if (result.isFailure) target.delete()
            Fs.changed()
            finish(if (result.isSuccess) "Made ${target.name}" else "Couldn't zip: ${result.exceptionOrNull()?.message}", result.isSuccess)
        }
    }

    private fun entryName(root: File, f: File): String {
        val base = root.parentFile ?: root
        return f.relativeTo(base).path.replace(File.separatorChar, '/')
    }

    /** Unpack a .zip into a new folder beside it. */
    fun unzip(archive: File) {
        if (busy()) return
        val dir = archive.parentFile ?: return
        val target = Fs.freeName(dir, archive.nameWithoutExtension.ifEmpty { "Unzipped" })
        op.value = Op.Running("Unzipping", 0, 1, archive.name)
        scope.launch {
            val result = runCatching {
                ZipFile(archive).use { zip ->
                    val entries = zip.entries().toList()
                    val canonical = target.canonicalPath + File.separator
                    entries.forEachIndexed { i, entry ->
                        op.value = Op.Running("Unzipping", i, entries.size, entry.name.substringAfterLast('/'))
                        val out = File(target, entry.name)
                        // Never write outside the target folder ("zip slip").
                        if (!(out.canonicalPath + if (entry.isDirectory) File.separator else "").startsWith(canonical)) return@forEachIndexed
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            zip.getInputStream(entry).use { ins -> out.outputStream().buffered().use { ins.copyTo(it) } }
                            if (entry.time > 0) out.setLastModified(entry.time)
                        }
                    }
                }
            }
            if (result.isFailure) target.deleteRecursively()
            Fs.changed()
            finish(if (result.isSuccess) "Unzipped into ${target.name}" else "Couldn't unzip: ${result.exceptionOrNull()?.message}", result.isSuccess)
        }
    }

    private fun finish(message: String, ok: Boolean) {
        op.value = Op.Finished(message, ok)
    }

    fun dismiss() { if (op.value is Op.Finished) op.value = Op.Idle }
}
