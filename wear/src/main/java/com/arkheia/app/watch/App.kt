package com.arkheia.app.watch

import android.content.Context
import android.os.Environment
import android.util.Base64
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.compose.navigation.rememberSwipeDismissableNavHostState
import androidx.wear.compose.foundation.rememberSwipeToDismissBoxState
import com.arkheia.app.Analysis
import com.arkheia.app.BuildConfig
import com.arkheia.app.Clip
import com.arkheia.app.Entry
import com.arkheia.app.Fs
import com.arkheia.app.Kind
import com.arkheia.app.Prefs
import com.arkheia.app.Release
import com.arkheia.app.Sort
import com.arkheia.app.StorageInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

// ------------------------------------------------------------------ Navigation

private const val B64 = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
private fun enc(path: String) = Base64.encodeToString(path.toByteArray(), B64)
private fun dec(s: String) = String(Base64.decode(s, B64))

object Route {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val STORAGE = "storage"
    const val SEARCH = "search"
    fun folder(f: File) = "folder/${enc(f.path)}"
    fun item(f: File) = "item/${enc(f.path)}"
    fun text(f: File) = "text/${enc(f.path)}"
    fun image(f: File) = "image/${enc(f.path)}"
    fun audio(f: File) = "audio/${enc(f.path)}"
    fun video(f: File) = "video/${enc(f.path)}"
    fun gallery(f: File) = "gallery/${enc(f.path)}"
    /** Is this folder the screen on top right now? */
    fun isCurrentFolder(nav: NavHostController, f: File): Boolean {
        val top = nav.currentBackStackEntry ?: return false
        return top.destination.route == "folder/{p}" && top.arguments?.getString("p") == enc(f.path)
    }
}

/** Tap on a list entry: folders open, files show their details and actions. */
fun NavHostController.openEntry(e: Entry) = navigate(if (e.isDir) Route.folder(e.file) else Route.item(e.file))

/** Open a file: in Arkheia when it can show it, otherwise in another app. */
fun NavHostController.view(c: Context, f: File) {
    when (Fs.kindOf(f)) {
        Kind.Folder -> navigate(Route.folder(f))
        Kind.Image -> navigate(Route.image(f))
        Kind.Text -> navigate(Route.text(f))
        Kind.Audio -> navigate(Route.audio(f))
        Kind.Video -> navigate(Route.video(f))
        Kind.Apk -> Fs.install(c, f)
        else -> Fs.openWith(c, f)
    }
}

fun viewsInApp(kind: Kind) = kind == Kind.Image || kind == Kind.Text || kind == Kind.Audio || kind == Kind.Video

@Composable
fun ArkheiaApp(openRequest: String?, onOpened: () -> Unit, onReplayTour: () -> Unit, onWhatsNew: () -> Unit) {
    val nav = rememberSwipeDismissableNavController()
    val context = LocalContext.current
    val op by Clip.op.collectAsStateWithLifecycle()

    LaunchedEffect(op) {
        val o = op
        if (o is Clip.Op.Finished) {
            Fs.toast(context, o.message)
            Clip.dismiss()
        }
    }
    LaunchedEffect(openRequest) {
        val path = openRequest ?: return@LaunchedEffect
        nav.popBackStack(Route.HOME, inclusive = false)
        nav.navigate(Route.folder(File(path)))
        onOpened()
    }

    // Shared with the photo viewer, which only lets you swipe back from the edge (the rest swipes photos).
    val swipe = rememberSwipeToDismissBoxState()
    val navState = rememberSwipeDismissableNavHostState(swipeToDismissBoxState = swipe)

    SwipeDismissableNavHost(navController = nav, startDestination = Route.HOME, state = navState) {
        composable(Route.HOME) { HomeScreen(nav) }
        composable(Route.SETTINGS) { SettingsScreen(onReplayTour, onWhatsNew) }
        composable(Route.STORAGE) { StorageScreen(nav) }
        composable(Route.SEARCH) { SearchScreen(nav) }
        composable("folder/{p}") { FolderScreen(File(dec(it.arguments?.getString("p").orEmpty())), nav) }
        composable("item/{p}") { ItemScreen(File(dec(it.arguments?.getString("p").orEmpty())), nav) }
        composable("text/{p}") { TextScreen(File(dec(it.arguments?.getString("p").orEmpty()))) }
        composable("image/{p}") { PhotoScreen(File(dec(it.arguments?.getString("p").orEmpty())), swipe) }
        composable("video/{p}") { VideoScreen(File(dec(it.arguments?.getString("p").orEmpty()))) }
        composable("gallery/{p}") { GalleryScreen(File(dec(it.arguments?.getString("p").orEmpty())), nav) }
        composable("audio/{p}") { AudioScreen(File(dec(it.arguments?.getString("p").orEmpty()))) }
    }
}

/** Re-checks file access every second or so while it's off. */
@Composable
fun rememberAccess(): Boolean {
    var a by remember { mutableStateOf(Fs.hasAccess()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_200)
            a = Fs.hasAccess()
        }
    }
    return a
}

// ------------------------------------------------------------------ Home

private data class Quick(val name: String, val icon: ImageVector, val dir: File)

private fun quickFolders(): List<Quick> {
    fun pub(type: String) = Environment.getExternalStoragePublicDirectory(type)
    return listOf(
        Quick("Download", Icons.Rounded.Download, pub(Environment.DIRECTORY_DOWNLOADS)),
        Quick("Music", Icons.Rounded.MusicNote, pub(Environment.DIRECTORY_MUSIC)),
        Quick("Pictures", Icons.Rounded.Photo, pub(Environment.DIRECTORY_PICTURES)),
        Quick("Camera", Icons.Rounded.PhotoCamera, pub(Environment.DIRECTORY_DCIM)),
        Quick("Movies", Icons.Rounded.Movie, pub(Environment.DIRECTORY_MOVIES)),
        Quick("Documents", Icons.Rounded.Description, pub(Environment.DIRECTORY_DOCUMENTS)),
        Quick("Recordings", Icons.Rounded.Mic, pub(Environment.DIRECTORY_RECORDINGS)),
    ).filter { it.dir.isDirectory }
}

@Composable
private fun HomeScreen(nav: NavHostController) {
    val version by Fs.version.collectAsStateWithLifecycle()
    val clip by Clip.content.collectAsStateWithLifecycle()
    val access = rememberAccess()
    val pins = Prefs.pins
    val recentPaths = Prefs.recent
    val storage by produceState<StorageInfo?>(null, version) { value = withContext(Dispatchers.IO) { Fs.storage() } }
    val quick by produceState(emptyList<Quick>(), access, version) { value = withContext(Dispatchers.IO) { quickFolders() } }
    val recent by produceState(emptyList<Entry>(), recentPaths, version) {
        value = withContext(Dispatchers.IO) { recentPaths.map { File(it) }.filter { it.exists() }.map { Fs.entry(it) } }
    }

    Page {
        item {
            Orb(
                progress = storage?.fraction ?: 0f,
                modifier = Modifier.size(96.dp).clickable { nav.navigate(Route.STORAGE) },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(storage?.let { Fs.bytes(it.free) } ?: "…", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Palette.onSurface)
                    Text("free", fontSize = 11.sp, color = Palette.primary)
                }
            }
        }
        storage?.let { s -> item { Caption("${Fs.bytes(s.used)} of ${Fs.bytes(s.total)} used") } }
        if (!access) {
            item {
                ActionChip(
                    "Allow file access", Icons.Rounded.Lock, { nav.navigate(Route.SETTINGS) },
                    secondary = "Needed to see your files", primary = true,
                )
            }
        }
        clip?.let { c ->
            item {
                ActionChip(
                    "Paste ${c.label}", Icons.Rounded.ContentPaste, { nav.navigate(Route.folder(Fs.root)) },
                    secondary = "Open a folder, then tap Paste here",
                )
            }
        }
        item {
            ActionChip(
                "All files", Icons.Rounded.Storage, { nav.navigate(Route.folder(Fs.root)) },
                secondary = "Internal storage", primary = access,
            )
        }
        quick.forEach { q ->
            item { ActionChip(q.name, q.icon, { nav.navigate(Route.folder(q.dir)) }) }
        }
        if (pins.isNotEmpty()) {
            item { Section("Pinned") }
            pins.forEach { p ->
                val f = File(p)
                item {
                    ActionChip(f.name.ifEmpty { p }, Icons.Rounded.PushPin, { nav.navigate(Route.folder(f)) }, secondary = Fs.pretty(f.parent ?: ""))
                }
            }
        }
        if (recent.isNotEmpty()) {
            item { Section("Recent") }
            recent.forEach { e -> item { EntryChip(e, { nav.openEntry(e) }) } }
        }
        item { Section("More") }
        item { ActionChip("Search", Icons.Rounded.Search, { nav.navigate(Route.SEARCH) }) }
        item { ActionChip("Storage", Icons.Rounded.PieChart, { nav.navigate(Route.STORAGE) }, secondary = "What's using space") }
        item { ActionChip("Settings", Icons.Rounded.Settings, { nav.navigate(Route.SETTINGS) }) }
    }
}

// ------------------------------------------------------------------ Storage

@Composable
private fun StorageScreen(nav: NavHostController) {
    val version by Fs.version.collectAsStateWithLifecycle()
    var scanned by remember { mutableIntStateOf(0) }
    val storage by produceState<StorageInfo?>(null, version) { value = withContext(Dispatchers.IO) { Fs.storage() } }
    val analysis by produceState<Analysis?>(null, version) {
        value = null
        value = withContext(Dispatchers.IO) { Fs.analyze { scanned = it } }
    }

    Page {
        item { PageTitle("Storage") }
        item {
            Orb(progress = storage?.fraction ?: 0f, modifier = Modifier.size(90.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${((storage?.fraction ?: 0f) * 100).toInt()}%", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.onSurface)
                    Text("used", fontSize = 11.sp, color = Palette.primary)
                }
            }
        }
        storage?.let { s ->
            item {
                InfoCard("", listOf("Total" to Fs.bytes(s.total), "Used" to Fs.bytes(s.used), "Free" to Fs.bytes(s.free)))
            }
        }
        val a = analysis
        if (a == null) {
            item { Caption(if (scanned == 0) "Looking through your files…" else "Scanned $scanned files…", color = Palette.primary) }
        } else {
            val total = a.byKind.values.sum().coerceAtLeast(1)
            item { Section("By type") }
            a.byKind.entries.sortedByDescending { it.value }.filter { it.value > 0 }.forEach { (kind, bytes) ->
                item { KindRow(kind, bytes, a.countByKind[kind] ?: 0, bytes.toFloat() / total) }
            }
            item { Caption("${a.files} files in ${a.folders} folders") }
            if (a.largest.isNotEmpty()) {
                item { Section("Largest files") }
                a.largest.forEach { e ->
                    item { EntryChip(e, { nav.openEntry(e) }, detail = "${Fs.bytes(e.size)} · ${Fs.pretty(e.file.parent ?: "")}") }
                }
            }
        }
    }
}

@Composable
private fun KindRow(kind: Kind, bytes: Long, count: Int, fraction: Float) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Palette.surface)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(kindIcon(kind), null, tint = kindTint(kind), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                when (kind) {
                    Kind.Folder -> "Folders"
                    Kind.Other -> "Other"
                    Kind.Apk -> "Apps (APK)"
                    else -> kind.label + "s"
                },
                fontSize = 12.sp, color = Palette.onSurface, modifier = Modifier.weight(1f),
            )
            Text(Fs.bytes(bytes), fontSize = 12.sp, color = Palette.onSurface, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.size(4.dp))
        Bar(fraction, kindTint(kind))
        Text("$count ${if (count == 1) "file" else "files"}", fontSize = 10.sp, color = Palette.muted, modifier = Modifier.padding(top = 2.dp))
    }
}

// ------------------------------------------------------------------ Search

@Composable
private fun SearchScreen(nav: NavHostController) {
    var query by rememberSaveable { mutableStateOf("") }
    val ask = rememberTextInput { query = it }
    val showHidden = Prefs.showHidden
    var searching by remember { mutableStateOf(false) }
    val results by produceState<List<Entry>?>(null, query, showHidden) {
        if (query.isEmpty()) return@produceState
        searching = true
        value = withContext(Dispatchers.IO) { Fs.search(query, showHidden) }
        searching = false
    }
    LaunchedEffect(Unit) {
        delay(250)
        if (query.isEmpty()) ask("Search files")
    }

    Page {
        item { PageTitle(if (query.isEmpty()) "Search" else "“$query”") }
        item {
            ActionChip(
                if (query.isEmpty()) "Search files" else "New search", Icons.Rounded.Search, { ask("Search files") },
                primary = query.isEmpty(),
            )
        }
        val r = results
        when {
            query.isEmpty() -> item { Caption("Type or say part of a file or folder name.") }
            searching -> item { Caption("Searching…", color = Palette.primary) }
            r.isNullOrEmpty() -> item { Caption("Nothing matches") }
            else -> {
                item { Caption(if (r.size >= 120) "First 120 matches" else "${r.size} found") }
                r.forEach { e ->
                    item { EntryChip(e, { nav.openEntry(e) }, detail = Fs.pretty(e.file.parent ?: "")) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Settings

@Composable
private fun SettingsScreen(onReplayTour: () -> Unit, onWhatsNew: () -> Unit) {
    val access = rememberAccess()
    val unlock = rememberAccessUi()
    Page {
        item { PageTitle("Settings") }
        item { Section("Browsing") }
        item {
            ToggleChip(
                checked = Prefs.showHidden,
                onCheckedChange = { Prefs.showHidden = it },
                label = { Text("Hidden files") },
                secondaryLabel = { Text("Names starting with a dot") },
                toggleControl = { Switch(checked = Prefs.showHidden) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            ToggleChip(
                checked = Prefs.foldersFirst,
                onCheckedChange = { Prefs.foldersFirst = it },
                label = { Text("Folders first") },
                toggleControl = { Switch(checked = Prefs.foldersFirst) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            ActionChip(
                "Sort by ${Prefs.sort.title.lowercase()}", Icons.AutoMirrored.Rounded.Sort,
                { Prefs.sort = Sort.entries[(Prefs.sort.ordinal + 1) % Sort.entries.size] },
                secondary = "Tap to change",
            )
        }

        item { Section("File access") }
        accessSteps(unlock)

        item { Section("About") }
        item { ActionChip("What's new", Icons.Rounded.NewReleases, onWhatsNew) }
        item { ActionChip("Welcome tour", Icons.Rounded.Replay, onReplayTour) }
        if (Prefs.recent.isNotEmpty()) {
            item { ActionChip("Clear recent files", Icons.Rounded.History, { Prefs.clearRecent() }) }
        }
        item { Caption("Arkheia ${BuildConfig.VERSION_NAME} · ἀρχεῖα, Greek for \"archives\"") }
    }
}

// ------------------------------------------------------------------ Setup & What's new

@Composable
fun SetupScreen(onDone: () -> Unit) {
    val access = rememberAccess()
    val unlock = rememberAccessUi()
    var step by remember { mutableIntStateOf(0) }
    Page {
        item {
            Orb(progress = (step + 1) / 3f, modifier = Modifier.size(80.dp)) {
                Text("${step + 1}/3", fontSize = 13.sp, color = Palette.onSurface, fontWeight = FontWeight.SemiBold)
            }
        }
        when (step) {
            0 -> {
                item { PageTitle("Arkheia") }
                item { Caption("Every file on your watch, on your wrist.", color = Palette.primary) }
                item { Caption("Browse folders, open photos, text and music, and copy, move, rename or delete anything.") }
                item { NextChip("Next") { step = 1 } }
            }
            1 -> {
                item { PageTitle("Allow file access") }
                item {
                    Caption(
                        if (access) "File access is on. You're set."
                        else "To show every folder, Arkheia needs your watch's Files permission. Nothing ever leaves your watch.",
                        color = if (access) Palette.primary else Palette.muted,
                    )
                }
                if (!access) accessSteps(unlock)
                item { NextChip(if (access) "Next" else "Skip for now") { step = 2 } }
            }
            else -> {
                item { PageTitle("Ready") }
                item { Caption("Swipe right to go back a folder. Tap a file for its actions. Pin folders you use to the home screen, and add the Arkheia tile.") }
                item { NextChip("Start") { onDone() } }
            }
        }
    }
}

@Composable
fun WhatsNewScreen(releases: List<Release>, onDone: () -> Unit) {
    Page {
        item { PageTitle("What's new") }
        releases.forEach { r ->
            item { Caption("${r.version} · ${r.title}", color = Palette.primary) }
            r.items.forEach { line -> item { Caption("• $line", color = Palette.onSurface) } }
        }
        item { NextChip("Got it", onDone) }
    }
}
