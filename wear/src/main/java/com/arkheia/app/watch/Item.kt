package com.arkheia.app.watch

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.curvedText
import com.arkheia.app.Clip
import com.arkheia.app.Entry
import com.arkheia.app.Fs
import com.arkheia.app.Kind
import com.arkheia.app.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private enum class Mode { Wheel, Info, ConfirmDelete }

/** A file or folder: its preview in the middle, its actions on a wheel around it. */
@Composable
fun ItemScreen(file: File, nav: NavHostController) {
    val context = LocalContext.current
    val version by Fs.version.collectAsStateWithLifecycle()
    val entry by produceState<Entry?>(null, file.path, version) {
        value = withContext(Dispatchers.IO) { if (file.exists()) Fs.entry(file) else null }
    }
    val folderSize by produceState<Pair<Long, Int>?>(null, file.path, version) {
        if (file.isDirectory) value = withContext(Dispatchers.IO) { Fs.folderSize(file) }
    }
    val thumb by produceState<ImageBitmap?>(null, file.path) {
        value = withContext(Dispatchers.IO) { Thumbs.load(context, file, 240) }
    }
    var mode by remember { mutableStateOf(Mode.Wheel) }
    var selected by remember { mutableIntStateOf(-1) }
    val rename = rememberTextInput { name ->
        val renamed = Fs.rename(file, name)
        if (renamed == null) {
            Fs.toast(context, "Couldn't rename to “$name”")
        } else if (renamed != file) {
            nav.popBackStack()
            nav.navigate(Route.item(renamed))
        }
    }

    val e = entry
    val kind = e?.kind ?: Fs.kindOf(file)
    val isDir = e?.isDir ?: file.isDirectory
    val tint = kindTint(kind)
    val title = file.name.ifEmpty { "Storage" }

    if (e == null) {
        Page(title = title) {
            item { Caption(if (file.exists()) "Loading…" else "This is no longer here") }
        }
        return
    }

    val sizeLine = if (isDir) {
        folderSize?.let { (b, n) -> "${Fs.bytes(b)} · $n ${if (n == 1) "file" else "files"}" } ?: "Counting…"
    } else {
        "${kind.label} · ${Fs.bytes(e.size)} · ${Fs.date(e.modified)}"
    }

    if (mode == Mode.Info) {
        Page(title = title, bottom = sizeLine, accent = tint) {
            item { PageTitle(file.name) }
            item {
                InfoCard(
                    "",
                    listOfNotNull(
                        "Type" to (if (isDir) "Folder" else file.extension.uppercase().ifEmpty { kind.label }),
                        if (isDir) ("Inside" to Fs.items(e.children)) else ("Size" to "%,d bytes".format(e.size)),
                        if (isDir) folderSize?.let { "Total" to Fs.bytes(it.first) } else null,
                        "Modified" to Fs.fullDate(e.modified),
                        "In" to Fs.pretty(file.parent ?: ""),
                        if (file.name.startsWith(".")) ("Hidden" to "Yes") else null,
                        if (!file.canWrite()) ("Read only" to "Yes") else null,
                    ),
                )
            }
            item { ActionChip("Back to actions", Icons.AutoMirrored.Rounded.ArrowBack, { mode = Mode.Wheel }) }
        }
        return
    }

    fun delete() {
        if (Fs.delete(file)) {
            Fs.toast(context, "Deleted ${file.name}")
            nav.popBackStack()
        } else {
            Fs.toast(context, "Couldn't delete everything")
            mode = Mode.Wheel
        }
    }

    val confirming = mode == Mode.ConfirmDelete
    val inApp = viewsInApp(kind)
    val actions = buildList {
        add(
            when {
                isDir -> RadialItem("Open", Icons.Rounded.FolderOpen, tint, "Go into the folder") { nav.navigate(Route.folder(file)) }
                kind == Kind.Audio || kind == Kind.Video -> RadialItem("Play", Icons.Rounded.PlayArrow, tint, "In Arkheia") { nav.view(context, file) }
                kind == Kind.Apk -> RadialItem("Install", Icons.Rounded.Android, tint, "Install this app") { nav.view(context, file) }
                else -> RadialItem("Open", Icons.Rounded.OpenInNew, tint, if (inApp) "In Arkheia" else "With another app") { nav.view(context, file) }
            }
        )
        if (!isDir && (inApp || kind == Kind.Apk)) {
            add(RadialItem("Open with", Icons.Rounded.Apps, Palette.sky, "Another app") { Fs.openWith(context, file) })
        }
        add(RadialItem("Copy", Icons.Rounded.ContentCopy, Palette.primary, "Then paste in a folder") {
            Clip.copy(file)
            Fs.toast(context, "Copied. Open a folder and tap Paste here")
            nav.popBackStack()
        })
        add(RadialItem("Move", Icons.Rounded.ContentCut, Palette.primary, "Then paste in a folder") {
            Clip.cut(file)
            Fs.toast(context, "Open where it should go and tap Paste here")
            nav.popBackStack()
        })
        add(
            RadialItem(
                "Rename", Icons.Rounded.DriveFileRenameOutline, Palette.sage,
                if (!isDir && file.extension.isNotEmpty()) "Keeps .${file.extension}" else null,
            ) { rename("New name") }
        )
        if (isDir) {
            val pinned = Prefs.isPinned(file.path)
            add(RadialItem(if (pinned) "Unpin" else "Pin", Icons.Rounded.PushPin, Palette.sage, if (pinned) "Take off Recent" else "Add to Recent") {
                Prefs.togglePin(file.path)
            })
        }
        add(RadialItem("Info", Icons.Rounded.Info, Palette.muted, "Size, date, place") { mode = Mode.Info })
        add(RadialItem("Delete", Icons.Rounded.Delete, Palette.red, "Asks first", danger = true) { mode = Mode.ConfirmDelete })
    }.map { a ->
        // While confirming a delete, any other button cancels it.
        if (confirming) a.copy(onClick = { mode = Mode.Wheel }) else a
    }
    val sel = selected.takeIf { it in actions.indices }

    Scaffold(
        timeText = {
            TimeText(
                startLinearContent = { Text(title, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                startCurvedContent = { curvedText(title.take(20), color = tint) },
            )
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            BezelRing(
                listOf(1f to (if (confirming) Palette.red else tint).copy(alpha = 0.55f)),
                arcs = Arcs.sides, stroke = 3.dp, inset = 2.dp,
            )
            RadialDial(
                items = actions,
                radius = 0.36f,
                bubble = 38.dp,
                selected = if (confirming) actions.lastIndex else selected,
                onSelect = { if (!confirming) selected = it },
                onCenter = {
                    when {
                        confirming -> delete()
                        sel != null -> actions[sel].onClick()
                        else -> actions.first().onClick()
                    }
                },
            ) {
                val t = thumb
                when {
                    confirming -> CenterDisc(glow = Palette.red) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.Delete, null, tint = Palette.red, modifier = Modifier.size(22.dp))
                            Text("Delete?", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Palette.onSurface)
                            Text("Tap to confirm", fontSize = 9.sp, color = Palette.muted)
                        }
                    }
                    t != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Image(t, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        if (sel != null) {
                            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                                CenterLabel(actions[sel])
                            }
                        }
                    }
                    else -> CenterDisc(glow = sel?.let { actions[it].tint } ?: tint) {
                        if (sel != null) {
                            CenterLabel(actions[sel])
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(kindIcon(kind, isDir && e.children < 0), null, tint = tint, modifier = Modifier.size(28.dp))
                                Text(
                                    if (isDir) Fs.items(e.children) else file.extension.uppercase().ifEmpty { kind.label },
                                    fontSize = 10.sp, color = Palette.muted,
                                )
                            }
                        }
                    }
                }
            }
            CurvedCaption(
                if (confirming) {
                    if (isDir) "Folder and everything in it" else "This can't be undone"
                } else {
                    sizeLine
                },
                color = if (confirming) Palette.red else Palette.muted,
            )
        }
    }
}

@Composable
private fun CenterLabel(item: RadialItem) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(6.dp)) {
        Icon(item.icon, null, tint = if (item.danger) Palette.red else item.tint, modifier = Modifier.size(20.dp))
        Text(item.label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Palette.onSurface, maxLines = 1)
        item.detail?.let {
            Text(it, fontSize = 9.sp, color = Palette.muted, maxLines = 2, textAlign = TextAlign.Center)
        }
    }
}
