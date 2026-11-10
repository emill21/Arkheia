package com.arkheia.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Copy / move: what's on the clipboard, and the paste in progress. */
object Clip {
    data class Content(val files: List<File>, val move: Boolean) {
        val label get() = if (files.size == 1) files[0].name else "${files.size} items"
    }

    sealed interface Op {
        data object Idle : Op
        data class Running(val move: Boolean, val done: Int, val total: Int, val current: String) : Op
        data class Finished(val message: String) : Op
    }

    val content = MutableStateFlow<Content?>(null)
    val op = MutableStateFlow<Op>(Op.Idle)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun copy(f: File) { content.value = Content(listOf(f), move = false) }
    fun cut(f: File) { content.value = Content(listOf(f), move = true) }
    fun clear() { content.value = null }

    /** A folder can't be pasted into itself. */
    fun canPasteInto(dir: File): Boolean {
        val c = content.value ?: return false
        return c.files.none { it.isDirectory && Fs.isInside(dir, it) }
    }

    fun pasteInto(dir: File) {
        val c = content.value ?: return
        if (op.value is Op.Running) return
        scope.launch {
            var ok = 0
            var failed = 0
            c.files.forEachIndexed { i, src ->
                op.value = Op.Running(c.move, i, c.files.size, src.name)
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
            val verb = if (c.move) "Moved" else "Copied"
            op.value = Op.Finished(
                if (failed == 0) "$verb ${if (ok == 1) c.files[0].name else "$ok items"}"
                else "$verb $ok, $failed failed"
            )
        }
    }

    fun dismiss() { if (op.value is Op.Finished) op.value = Op.Idle }
}
