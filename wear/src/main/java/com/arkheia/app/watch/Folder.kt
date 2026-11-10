package com.arkheia.app.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Text
import com.arkheia.app.Clip
import com.arkheia.app.Entry
import com.arkheia.app.Fs
import com.arkheia.app.Prefs
import com.arkheia.app.Sort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun FolderScreen(dir: File, nav: NavHostController) {
    val context = LocalContext.current
    val version by Fs.version.collectAsStateWithLifecycle()
    val clip by Clip.content.collectAsStateWithLifecycle()
    val op by Clip.op.collectAsStateWithLifecycle()
    val showHidden = Prefs.showHidden
    val sort = Prefs.sort
    val foldersFirst = Prefs.foldersFirst

    var entries by remember { mutableStateOf<List<Entry>?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var gone by remember { mutableStateOf(false) }
    val access = rememberAccess()

    LaunchedEffect(dir.path, version, showHidden, sort, foldersFirst, access) {
        val list = withContext(Dispatchers.IO) { Fs.list(dir, showHidden, sort, foldersFirst) }
        entries = list
        gone = !dir.exists()
        loaded = true
    }
    // Renamed, moved or deleted while we were away: step back out of it.
    LaunchedEffect(gone) {
        if (gone && Route.isCurrentFolder(nav, dir)) nav.popBackStack()
    }

    val newFolder = rememberTextInput { name ->
        if (Fs.mkdir(dir, name) == null) Fs.toast(context, "Couldn't create “$name”")
    }
    val isRoot = dir.path == Fs.root.path
    val parent = dir.parentFile?.takeIf { !isRoot && Fs.isInside(it, Fs.root) }
    val list = entries

    Page {
        item { PageTitle(if (isRoot) "Internal storage" else dir.name) }
        item {
            Caption(
                when {
                    !loaded -> "Loading…"
                    list == null -> "Can't open"
                    isRoot -> Fs.items(list.size)
                    else -> "${Fs.items(list.size)} · ${Fs.pretty(dir.parent ?: "")}"
                }
            )
        }

        // Paste bar
        val o = op
        val c = clip
        if (o is Clip.Op.Running) {
            item {
                Caption(
                    "${if (o.move) "Moving" else "Copying"} ${o.done + 1}/${o.total}: ${o.current}",
                    color = Palette.primary,
                )
            }
        } else if (c != null && list != null) {
            item {
                if (Clip.canPasteInto(dir)) {
                    ActionChip(
                        "Paste here", Icons.Rounded.ContentPaste, { Clip.pasteInto(dir) },
                        secondary = "${if (c.move) "Move" else "Copy"} ${c.label}", primary = true,
                    )
                } else {
                    Caption("Can't paste a folder into itself", color = Palette.red)
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CompactChip(
                        onClick = { Clip.clear() },
                        label = { Text("Cancel ${if (c.move) "move" else "copy"}") },
                        colors = ChipDefaults.secondaryChipColors(),
                    )
                }
            }
        }

        if (loaded && list == null) {
            item {
                Caption(
                    if (!access) "Arkheia needs file access to open this."
                    else "Android keeps this folder private.",
                )
            }
            if (!access) {
                item { ActionChip("Allow file access", Icons.Rounded.Lock, { nav.navigate(Route.SETTINGS) }, primary = true) }
            }
        }
        if (list != null) {
            if (list.isEmpty()) item { Caption("Nothing here yet") }
            items(list, key = { it.path }) { e -> EntryChip(e, { nav.openEntry(e) }) }
        }

        if (list != null) {
            item { Section("This folder") }
            item { ActionChip("New folder", Icons.Rounded.CreateNewFolder, { newFolder("Folder name") }) }
            item {
                ActionChip(
                    "Sort by ${sort.title.lowercase()}", Icons.AutoMirrored.Rounded.Sort,
                    { Prefs.sort = Sort.entries[(sort.ordinal + 1) % Sort.entries.size] },
                )
            }
            if (!isRoot) {
                val pinned = Prefs.isPinned(dir.path)
                item {
                    ActionChip(
                        if (pinned) "Unpin from home" else "Pin to home", Icons.Rounded.PushPin,
                        { Prefs.togglePin(dir.path) }, tint = if (pinned) Palette.primary else Palette.muted,
                    )
                }
                item {
                    ActionChip(
                        "Copy, move, rename…", Icons.Rounded.MoreHoriz, { nav.navigate(Route.item(dir)) },
                        secondary = "Size and more",
                    )
                }
            }
            parent?.let { p ->
                item {
                    ActionChip(
                        "Up to ${if (p.path == Fs.root.path) "Internal storage" else p.name}", Icons.Rounded.ArrowUpward,
                        { nav.navigate(Route.folder(p)) },
                    )
                }
            }
        }
    }
}
