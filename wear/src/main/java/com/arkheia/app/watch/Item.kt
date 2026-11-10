package com.arkheia.app.watch

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.wear.compose.material.Icon
import com.arkheia.app.Clip
import com.arkheia.app.Entry
import com.arkheia.app.Fs
import com.arkheia.app.Kind
import com.arkheia.app.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Details and actions for one file or folder. */
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
    var confirmDelete by remember { mutableStateOf(false) }
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

    Page {
        item {
            val t = thumb
            if (t != null) {
                Image(
                    t, null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(110.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .clickable { nav.view(context, file) },
                )
            } else {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Icon(kindIcon(kind), null, tint = kindTint(kind), modifier = Modifier.size(40.dp))
                }
            }
        }
        item { PageTitle(file.name.ifEmpty { "Internal storage" }) }

        if (e == null) {
            item { Caption(if (file.exists()) "Loading…" else "This is no longer here") }
            return@Page
        }

        item {
            Caption(
                if (isDir) folderSize?.let { (b, n) -> "${Fs.bytes(b)} · $n ${if (n == 1) "file" else "files"}" } ?: "Counting…"
                else "${kind.label} · ${Fs.bytes(e.size)}"
            )
        }

        if (confirmDelete) {
            item {
                Caption(
                    if (isDir) "Delete this folder and everything in it? This can't be undone."
                    else "Delete this file? This can't be undone.",
                    color = Palette.red,
                )
            }
            item {
                ActionChip("Delete", Icons.Rounded.Delete, {
                    if (Fs.delete(file)) {
                        Fs.toast(context, "Deleted ${file.name}")
                        nav.popBackStack()
                    } else {
                        Fs.toast(context, "Couldn't delete everything")
                        confirmDelete = false
                    }
                }, tint = Palette.red)
            }
            item { ActionChip("Cancel", Icons.Rounded.Close, { confirmDelete = false }) }
            return@Page
        }

        if (isDir) {
            item { ActionChip("Open folder", Icons.Rounded.FolderOpen, { nav.navigate(Route.folder(file)) }, primary = true) }
        } else {
            val inApp = viewsInApp(kind)
            item {
                ActionChip(
                    when (kind) {
                        Kind.Audio -> "Play"
                        Kind.Apk -> "Install"
                        else -> "Open"
                    },
                    when (kind) {
                        Kind.Audio -> Icons.Rounded.PlayArrow
                        Kind.Apk -> Icons.Rounded.Android
                        else -> Icons.Rounded.OpenInNew
                    },
                    { nav.view(context, file) },
                    secondary = if (inApp || kind == Kind.Apk) null else "With another app",
                    primary = true,
                )
            }
            if (inApp || kind == Kind.Apk) {
                item { ActionChip("Open with…", Icons.Rounded.Apps, { Fs.openWith(context, file) }) }
            }
        }

        item {
            ActionChip("Copy", Icons.Rounded.ContentCopy, {
                Clip.copy(file)
                Fs.toast(context, "Copied. Open a folder and tap Paste here")
                nav.popBackStack()
            })
        }
        item {
            ActionChip("Move", Icons.Rounded.ContentCut, {
                Clip.cut(file)
                Fs.toast(context, "Open where it should go and tap Paste here")
                nav.popBackStack()
            })
        }
        item {
            ActionChip(
                "Rename", Icons.Rounded.DriveFileRenameOutline, { rename("New name") },
                secondary = if (!isDir && file.extension.isNotEmpty()) "Leave off .${file.extension} to keep it" else null,
            )
        }
        if (isDir) {
            val pinned = Prefs.isPinned(file.path)
            item {
                ActionChip(
                    if (pinned) "Unpin from home" else "Pin to home", Icons.Rounded.PushPin,
                    { Prefs.togglePin(file.path) }, tint = if (pinned) Palette.primary else Palette.muted,
                )
            }
        }
        item { ActionChip("Delete", Icons.Rounded.Delete, { confirmDelete = true }, tint = Palette.red) }

        item { Section("Details") }
        item {
            InfoCard(
                "",
                listOfNotNull(
                    "Type" to (if (isDir) "Folder" else file.extension.uppercase().ifEmpty { kind.label }),
                    if (isDir) ("Inside" to Fs.items(e.children)) else ("Size" to "%,d bytes".format(e.size)),
                    "Modified" to Fs.fullDate(e.modified),
                    "In" to Fs.pretty(file.parent ?: ""),
                    if (file.name.startsWith(".")) ("Hidden" to "Yes") else null,
                    if (!file.canWrite()) ("Read only" to "Yes") else null,
                ),
            )
        }
    }
}
