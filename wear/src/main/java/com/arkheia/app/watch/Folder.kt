package com.arkheia.app.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.runtime.mutableStateListOf
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
import com.arkheia.app.Kind
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
    var selecting by remember(dir.path) { mutableStateOf(false) }
    val chosen = remember(dir.path) { mutableStateListOf<String>() }
    var confirmDelete by remember(dir.path) { mutableStateOf(false) }
    fun chosenFiles() = chosen.map { File(it) }
    fun endSelect() {
        selecting = false
        confirmDelete = false
        chosen.clear()
    }
    val parent = dir.parentFile?.takeIf { !isRoot && Fs.isInside(it, Fs.root) }
    val list = entries

    val name = if (isRoot) "Storage" else dir.name
    val running = op as? Clip.Op.Running
    Page(
        title = name,
        behind = if (running != null) {
            { BezelRing(listOf(animatedFraction(running.fraction) to Palette.primary), stroke = 6.dp) }
        } else null,
        bottom = when {
            running != null -> "${running.verb} ${running.done + 1} of ${running.total}"
            selecting -> "${chosen.size} selected"
            !loaded -> "Loading…"
            list == null -> "Can't open"
            isRoot -> Fs.items(list.size)
            else -> "${Fs.items(list.size)} · ${Fs.pretty(dir.parent ?: "")}"
        },
    ) {
        // Paste bar
        val o = op
        val c = clip
        if (o is Clip.Op.Running) {
            item {
                Caption(
                    "${o.verb} ${o.current}",
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
            val media = list.count { it.kind == Kind.Image || it.kind == Kind.Video }
            if (media > 0 && !selecting) {
                item {
                    ActionChip(
                        "Gallery", Icons.Rounded.PhotoLibrary, { nav.navigate(Route.gallery(dir)) },
                        secondary = "$media ${if (media == 1) "photo or video" else "photos and videos"}",
                        tint = Palette.rose,
                    )
                }
            }
            if (selecting) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val none = chosen.isEmpty()
                        RoundButton(Icons.Rounded.ContentCopy, "Copy", {
                            if (!none) { Clip.copy(chosenFiles()); Fs.toast(context, "Copied ${chosen.size}. Open a folder and tap Paste here"); endSelect() }
                        }, size = 34.dp)
                        RoundButton(Icons.Rounded.ContentCut, "Move", {
                            if (!none) { Clip.cut(chosenFiles()); Fs.toast(context, "Open where they should go and tap Paste here"); endSelect() }
                        }, size = 34.dp)
                        RoundButton(Icons.Rounded.FolderZip, "Zip", {
                            if (!none) { Clip.zip(chosenFiles()); endSelect() }
                        }, tint = Palette.muted, size = 34.dp)
                        RoundButton(Icons.Rounded.Delete, "Delete", {
                            when {
                                none -> {}
                                confirmDelete -> { Clip.deleteAll(chosenFiles()); endSelect() }
                                else -> confirmDelete = true
                            }
                        }, tint = Palette.red, size = 34.dp, filled = confirmDelete)
                    }
                }
                item {
                    Caption(
                        when {
                            confirmDelete -> "Tap delete again to delete ${chosen.size}. This can't be undone."
                            chosen.isEmpty() -> "Tap files to select them"
                            else -> "${chosen.size} selected"
                        },
                        color = if (confirmDelete) Palette.red else Palette.muted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                        CompactChip(
                            onClick = {
                                confirmDelete = false
                                if (chosen.size == list.size) chosen.clear() else { chosen.clear(); chosen.addAll(list.map { it.path }) }
                            },
                            label = { Text(if (chosen.size == list.size && list.isNotEmpty()) "None" else "All") },
                            colors = ChipDefaults.secondaryChipColors(),
                        )
                        CompactChip(onClick = { endSelect() }, label = { Text("Done") }, colors = ChipDefaults.primaryChipColors())
                    }
                }
            }
            if (list.isEmpty()) item { Caption("Nothing here yet") }
            items(list, key = { it.path }) { e ->
                EntryChip(
                    e,
                    onClick = {
                        if (selecting) {
                            confirmDelete = false
                            if (e.path in chosen) chosen.remove(e.path) else chosen.add(e.path)
                        } else {
                            nav.openEntry(e)
                        }
                    },
                    selected = if (selecting) e.path in chosen else null,
                    onLongClick = {
                        // Hold a file to start selecting, with it already picked.
                        confirmDelete = false
                        if (!selecting) { selecting = true; chosen.clear() }
                        if (e.path !in chosen) chosen.add(e.path)
                    },
                )
            }
        }

        if (list != null && !selecting) {
            // Folder dock: round buttons along the bottom of the list.
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundButton(Icons.Rounded.CreateNewFolder, "New folder", { newFolder("Folder name") }, size = 31.dp)
                    if (list.isNotEmpty()) RoundButton(Icons.Rounded.DoneAll, "Select", { selecting = true }, tint = Palette.sage, size = 31.dp)
                    RoundButton(
                        Icons.AutoMirrored.Rounded.Sort, "Sort",
                        { Prefs.sort = Sort.entries[(sort.ordinal + 1) % Sort.entries.size] },
                        tint = Palette.sky, size = 31.dp,
                    )
                    if (!isRoot) {
                        val pinned = Prefs.isPinned(dir.path)
                        RoundButton(
                            Icons.Rounded.PushPin, if (pinned) "Unpin" else "Pin",
                            { Prefs.togglePin(dir.path) }, tint = Palette.sage, size = 31.dp, filled = pinned,
                        )
                        RoundButton(Icons.Rounded.MoreHoriz, "Folder actions", { nav.navigate(Route.item(dir)) }, tint = Palette.muted, size = 31.dp)
                    }
                }
            }
            item {
                Caption(
                    "Sorted by ${sort.title.lowercase()}" + if (!isRoot && Prefs.isPinned(dir.path)) " · pinned" else "",
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            parent?.let { p ->
                item {
                    ActionChip(
                        "Up to ${if (p.path == Fs.root.path) "Storage" else p.name}", Icons.Rounded.ArrowUpward,
                        { nav.navigate(Route.folder(p)) },
                    )
                }
            }
        }
    }
}
