package com.arkheia.app.watch

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.items
import com.arkheia.app.Entry
import com.arkheia.app.Fs
import com.arkheia.app.Kind
import com.arkheia.app.PhoneFs
import com.arkheia.app.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A folder on your phone, browsed over Bluetooth through Syndesia. [path] "" is the phone's main storage. */
@Composable
fun PhoneFolderScreen(path: String, nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val showHidden = Prefs.showHidden
    val sort = Prefs.sort
    val foldersFirst = Prefs.foldersFirst

    var listing by remember { mutableStateOf<PhoneFs.Listing?>(null) }
    var error by remember { mutableStateOf<PhoneFs.Failure?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    var copying by remember { mutableStateOf<Entry?>(null) }
    var copied by remember { mutableLongStateOf(0L) }

    LaunchedEffect(path, reload, showHidden, sort, foldersFirst) {
        loading = true
        val r = withContext(Dispatchers.IO) { runCatching { PhoneFs.list(context, path, showHidden, sort, foldersFirst) } }
        r.onSuccess { listing = it; error = null }
            .onFailure { e -> error = e as? PhoneFs.Failure ?: PhoneFs.Failure("failed", e.message ?: "Couldn't read your phone's files.") }
        loading = false
    }

    fun copy(e: Entry) {
        if (copying != null) return
        copying = e
        copied = 0
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { PhoneFs.copyToWatch(context, e) { copied = it } } }
            copying = null
            r.onSuccess { f ->
                Prefs.addRecent(f.path)
                if (viewsInApp(Fs.kindOf(f)) || Fs.kindOf(f) == Kind.Apk) nav.view(context, f)
                else { Fs.toast(context, "Saved to Download › From phone"); nav.navigate(Route.item(f)) }
            }.onFailure { x -> Fs.toast(context, x.message ?: "Couldn't copy ${e.name}") }
        }
    }

    val l = listing
    val isRoot = l == null || l.path == l.root
    val name = when {
        l == null -> "Phone"
        isRoot -> "Phone"
        else -> l.path.substringAfterLast('/')
    }
    val c = copying
    Page(
        title = name,
        accent = Palette.sky,
        behind = if (c != null) {
            { BezelRing(listOf(animatedFraction(if (c.size > 0) copied.toFloat() / c.size else 0f) to Palette.sky), stroke = 6.dp) }
        } else null,
        bottom = when {
            c != null -> "Copying ${Fs.bytes(copied)} of ${Fs.bytes(c.size)}"
            loading && l == null -> "Asking your phone…"
            error != null -> "Phone"
            l != null && isRoot -> "${Fs.items(l.entries.size)} · on your phone"
            l != null -> "${Fs.items(l.entries.size)} · ${Fs.pretty(l.path.removePrefix(l.root).substringBeforeLast('/').ifEmpty { "Phone" })}"
            else -> null
        },
    ) {
        if (c != null) item { Caption("Copying ${c.name} to your watch…", color = Palette.sky) }
        val err = error
        if (err != null && l == null) {
            item { Caption(err.message ?: "Couldn't read your phone's files.", color = Palette.red) }
            if (err.code == "nosyndesia") {
                item { Caption("Syndesia on your watch carries Arkheia's requests to your phone over Bluetooth.") }
            }
            item { ActionChip("Try again", Icons.Rounded.Replay, { reload++ }, primary = true) }
            return@Page
        }
        if (l == null) {
            item { Caption("Asking your phone over Bluetooth…", color = Palette.sky) }
            return@Page
        }
        if (isRoot) item { Caption("Tap a file to copy it to Download › From phone on your watch and open it.") }
        if (l.entries.isEmpty()) item { Caption("Empty folder") }
        items(l.entries, key = { it.path }) { e ->
            EntryChip(
                e,
                onClick = { if (e.isDir) nav.navigate(Route.phone(e.path)) else copy(e) },
                detail = if (e.isDir) (if (e.children < 0) "Can't open" else Fs.items(e.children)) else null,
            )
        }
        if (isRoot) {
            item {
                ActionChip(
                    "Copied files", Icons.Rounded.Download,
                    { nav.navigate(Route.folder(PhoneFs.inbox.apply { mkdirs() })) },
                    secondary = "Download › From phone, on the watch", tint = Palette.sky,
                )
            }
        }
    }
}
