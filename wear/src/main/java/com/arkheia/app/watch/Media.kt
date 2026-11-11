package com.arkheia.app.watch

import android.content.Context
import android.net.Uri
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.SwipeToDismissBoxState
import androidx.wear.compose.foundation.edgeSwipeToDismiss
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import com.arkheia.app.Entry
import com.arkheia.app.Fs
import com.arkheia.app.Kind
import com.arkheia.app.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

// ------------------------------------------------------------------ Thumbnail cache

object ThumbCache {
    private val cache = LruCache<String, ImageBitmap>(90)

    fun peek(f: File): ImageBitmap? = cache.get(f.path + f.lastModified())

    /** Call off the main thread. */
    fun load(c: Context, f: File, max: Int = 160): ImageBitmap? {
        val k = f.path + f.lastModified()
        cache.get(k)?.let { return it }
        return Thumbs.load(c, f, max)?.also { cache.put(k, it) }
    }
}

@Composable
private fun Thumb(e: Entry, onClick: () -> Unit) {
    val context = LocalContext.current
    val img by produceState(ThumbCache.peek(e.file), e.path) {
        if (value == null) value = withContext(Dispatchers.IO) { ThumbCache.load(context, e.file) }
    }
    Box(
        Modifier
            .size(50.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val i = img
        if (i != null) {
            Image(i, e.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(kindIcon(e.kind), null, tint = kindTint(e.kind), modifier = Modifier.size(20.dp))
        }
        if (e.kind == Kind.Video) {
            Box(
                Modifier.size(20.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}

// ------------------------------------------------------------------ Gallery

/** Every photo and video in a folder, as a grid of thumbnails. */
@Composable
fun GalleryScreen(dir: File, nav: NavHostController) {
    val context = LocalContext.current
    val version by Fs.version.collectAsStateWithLifecycle()
    val sort = Prefs.sort
    val showHidden = Prefs.showHidden
    val media by produceState<List<Entry>?>(null, dir.path, version, sort, showHidden) {
        value = withContext(Dispatchers.IO) {
            Fs.list(dir, showHidden, sort, foldersFirst = false)?.filter { it.kind == Kind.Image || it.kind == Kind.Video }
        }
    }
    Page {
        item { PageTitle(if (dir.path == Fs.root.path) "Internal storage" else dir.name) }
        val m = media
        if (m == null) {
            item { Caption("Loading…", color = Palette.primary) }
        } else {
            val photos = m.count { it.kind == Kind.Image }
            val videos = m.size - photos
            item {
                Caption(
                    listOfNotNull(
                        if (photos > 0) "$photos ${if (photos == 1) "photo" else "photos"}" else null,
                        if (videos > 0) "$videos ${if (videos == 1) "video" else "videos"}" else null,
                    ).joinToString(" · ").ifEmpty { "No photos or videos here" }
                )
            }
            m.chunked(3).forEach { row ->
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally)) {
                        row.forEach { e -> Thumb(e) { nav.view(context, e.file) } }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Photos

/** Full-screen photos: swipe between every photo in the folder, crown or double-tap to zoom. */
@Composable
fun PhotoScreen(file: File, swipe: SwipeToDismissBoxState) {
    val photos by produceState<List<File>?>(null, file.path) {
        value = withContext(Dispatchers.IO) {
            val dir = file.parentFile
            val list = dir?.let { Fs.list(it, true, Prefs.sort, foldersFirst = false) }
                ?.filter { it.kind == Kind.Image }?.map { it.file }.orEmpty()
            if (list.any { it.path == file.path }) list else listOf(file)
        }
    }
    val list = photos
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Swipe in from the left edge to go back; anywhere else swipes between photos.
            .edgeSwipeToDismiss(swipe),
        contentAlignment = Alignment.Center,
    ) {
        if (list == null) {
            Caption("Opening…", color = Palette.primary)
            return@Box
        }
        key(list.size) {
            val start = list.indexOfFirst { it.path == file.path }.coerceAtLeast(0)
            val pager = rememberPagerState(initialPage = start) { list.size }
            var zoomed by remember { mutableStateOf(false) }
            var chrome by remember { mutableStateOf(true) }

            LaunchedEffect(pager.currentPage) {
                Prefs.addRecent(list[pager.currentPage].path)
                chrome = true
            }
            LaunchedEffect(chrome, pager.currentPage) {
                if (chrome) {
                    delay(2_500)
                    chrome = false
                }
            }

            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = !zoomed,
                beyondViewportPageCount = 1,
                key = { list[it].path },
            ) { page ->
                ZoomableImage(
                    file = list[page],
                    active = page == pager.currentPage,
                    onZoomChange = { zoomed = it },
                    onTap = { chrome = !chrome },
                )
            }

            AnimatedVisibility(chrome && !zoomed, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    Text(
                        "${pager.currentPage + 1} / ${list.size}",
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 14.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        fontSize = 12.sp,
                        color = Color.White,
                    )
                    Text(
                        list[pager.currentPage].name,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp, start = 36.dp, end = 36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        fontSize = 11.sp,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoomableImage(file: File, active: Boolean, onZoomChange: (Boolean) -> Unit, onTap: () -> Unit) {
    val context = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    val image by produceState<ImageBitmap?>(null, file.path) {
        val b = withContext(Dispatchers.IO) { runCatching { Thumbs.decode(file, 1024)?.asImageBitmap() }.getOrNull() }
        value = b
        failed = b == null
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val zoomed = scale > 1.01f
    val tap by rememberUpdatedState(onTap)
    val zoomChange by rememberUpdatedState(onZoomChange)
    val focus = remember { FocusRequester() }

    fun clamp(o: Offset): Offset {
        val maxX = size.width * (scale - 1f) / 2f
        val maxY = size.height * (scale - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    LaunchedEffect(active) {
        if (active) {
            runCatching { focus.requestFocus() }
        } else {
            scale = 1f
            offset = Offset.Zero
        }
    }
    LaunchedEffect(zoomed, active) { if (active) zoomChange(zoomed) }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .onRotaryScrollEvent {
                scale = (scale * (1f + it.verticalScrollPixels / 400f)).coerceIn(1f, 6f)
                offset = if (scale <= 1.01f) Offset.Zero else clamp(offset)
                true
            }
            .focusRequester(focus)
            .focusable()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { tap() },
                    onDoubleTap = { at ->
                        if (scale > 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                            // Zoom in on where you tapped.
                            val centre = Offset(size.width / 2f, size.height / 2f)
                            offset = clamp((centre - at) * (scale - 1f))
                        }
                    },
                )
            }
            // Drag only while zoomed in, so a swipe still moves to the next photo when you aren't.
            .then(
                if (zoomed) Modifier.pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        offset = clamp(offset + drag)
                    }
                } else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        val img = image
        when {
            img != null -> Image(
                img, file.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            )
            failed -> Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Caption("Arkheia can't show this picture.")
                ActionChip("Open with…", Icons.Rounded.Apps, { Fs.openWith(context, file) })
            }
            else -> Caption("Opening…", color = Palette.primary)
        }
    }
}

// ------------------------------------------------------------------ Video

/**
 * Full-screen video. Tap for controls, double-tap to fill the screen. The crown skips through the
 * video or changes the volume; the little crown button in the controls switches between them.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoScreen(file: File) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = remember(file.path) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
            playWhenReady = true
        }
    }
    var playing by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var controls by remember { mutableStateOf(true) }
    var fill by remember { mutableStateOf(false) }
    var lastTouch by remember { mutableLongStateOf(0L) }
    val focus = remember { FocusRequester() }
    RequestFocusWhenResumed(focus)
    var crownAcc by remember { mutableFloatStateOf(0f) }
    var volume by remember { mutableFloatStateOf(Volume.level(context)) }
    var volumeShownAt by remember { mutableLongStateOf(0L) }
    var hint by remember { mutableStateOf<String?>(null) }
    val haptics = LocalView.current

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                view.keepScreenOn = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    controls = true
                    player.pause()
                    player.seekTo(0)
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                error = when (e.errorCode) {
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> "Your watch can't decode this video."
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "This video format isn't supported."
                    else -> "Couldn't play this video (${e.errorCodeName})."
                }
            }
        }
        player.addListener(listener)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycle.addObserver(observer)
        Prefs.addRecent(file.path)
        onDispose {
            lifecycle.removeObserver(observer)
            view.keepScreenOn = false
            player.removeListener(listener)
            player.release()
        }
    }
    DisposableEffect(Unit) {
        com.arkheia.app.StemButton.listening++
        onDispose { com.arkheia.app.StemButton.listening-- }
    }
    LaunchedEffect(player) {
        com.arkheia.app.StemButton.presses.collect {
            if (player.isPlaying) player.pause() else player.play()
            controls = true
        }
    }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.takeIf { it > 0 } ?: 0L
            delay(250)
        }
    }
    // Hide the controls a few seconds after the last touch while playing.
    LaunchedEffect(controls, playing, lastTouch) {
        if (controls && playing) {
            delay(3_000)
            controls = false
        }
    }

    LaunchedEffect(volumeShownAt) {
        if (volumeShownAt > 0) {
            delay(1_500)
            volumeShownAt = 0
        }
    }
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(1_400)
            hint = null
        }
    }

    fun touch() {
        lastTouch = System.currentTimeMillis()
        controls = true
    }
    fun switchCrown() {
        Prefs.crownVolume = !Prefs.crownVolume
        hint = if (Prefs.crownVolume) "Crown: volume" else "Crown: skip"
        if (Prefs.crownVolume) {
            volume = Volume.level(context)
            volumeShownAt = System.currentTimeMillis()
        }
        touch()
    }
    fun seekBy(ms: Long) {
        val to = (player.currentPosition + ms).coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE)
        player.seekTo(to)
        position = to
        touch()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onRotaryScrollEvent {
                if (Prefs.crownVolume) {
                    // Volume: one step per notch of the crown.
                    crownAcc += it.verticalScrollPixels
                    if (abs(crownAcc) > 38f) {
                        Volume.step(context, up = crownAcc > 0)
                        volume = Volume.level(context)
                        volumeShownAt = System.currentTimeMillis()
                        haptics.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        crownAcc = 0f
                    }
                } else if (abs(it.verticalScrollPixels) > 0.5f) {
                    seekBy((it.verticalScrollPixels * 40).toLong())
                }
                true
            }
            .focusRequester(focus)
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(true)
                    this.player = player
                }
            },
            update = {
                it.resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Touch layer above the video view (which would otherwise swallow taps).
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { if (controls) controls = false else touch() },
                        onDoubleTap = {
                            fill = !fill
                            touch()
                        },
                    )
                }
        )

        val err = error
        if (err != null) {
            Column(
                Modifier.fillMaxSize().background(Color.Black).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Caption(err)
                ActionChip("Open with…", Icons.Rounded.Apps, { Fs.openWith(context, file) })
            }
            return@Box
        }

        AnimatedVisibility(volumeShownAt > 0, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            VolumeArc(volume, Palette.sky)
        }
        hint?.let { h ->
            Text(
                h,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = 96.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                fontSize = 11.sp,
                color = Palette.sky,
            )
        }
        AnimatedVisibility(controls || !playing, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                // Progress ring around the edge of the round screen.
                Canvas(Modifier.fillMaxSize().padding(3.dp)) {
                    val stroke = 4.dp.toPx()
                    val inset = stroke / 2
                    val arc = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
                    drawArc(Color.White.copy(alpha = 0.2f), 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
                    val p = if (duration > 0) position.toFloat() / duration else 0f
                    if (p > 0f) drawArc(Palette.sky, -90f, 360f * p.coerceIn(0f, 1f), false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Text(
                    file.nameWithoutExtension,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 26.dp, start = 40.dp, end = 40.dp),
                    fontSize = 11.sp,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { seekBy(-10_000) }, colors = ButtonDefaults.secondaryButtonColors(), modifier = Modifier.size(ButtonDefaults.SmallButtonSize)) {
                        Icon(Icons.Rounded.Replay10, "Back 10 seconds")
                    }
                    Button(
                        onClick = {
                            if (player.isPlaying) player.pause() else player.play()
                            touch()
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Palette.sky, contentColor = Color(0xFF0B2A4A)),
                        modifier = Modifier.size(ButtonDefaults.LargeButtonSize),
                    ) {
                        Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", modifier = Modifier.size(30.dp))
                    }
                    Button(onClick = { seekBy(10_000) }, colors = ButtonDefaults.secondaryButtonColors(), modifier = Modifier.size(ButtonDefaults.SmallButtonSize)) {
                        Icon(Icons.Rounded.Forward10, "Forward 10 seconds")
                    }
                }
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("${clockMs(position)} / ${clockMs(duration)}", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Medium)
                    Icon(
                        if (Prefs.crownVolume) Icons.AutoMirrored.Rounded.VolumeUp else Icons.Rounded.FastForward,
                        "Switch what the crown does",
                        tint = Palette.sky,
                        modifier = Modifier.size(18.dp).clickable { switchCrown() },
                    )
                    Icon(
                        Icons.Rounded.AspectRatio, "Fill screen",
                        tint = if (fill) Palette.sky else Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp).clickable {
                            fill = !fill
                            touch()
                        },
                    )
                }
            }
        }
    }
}

private fun clockMs(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    return if (h > 0) "%d:%02d:%02d".format(h, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
