package com.arkheia.app.watch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ThumbnailUtils
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.exifinterface.media.ExifInterface
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import com.arkheia.app.Fs
import com.arkheia.app.Kind
import com.arkheia.app.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

// ------------------------------------------------------------------ Thumbnails

object Thumbs {
    /** A small picture for a file (photo, video frame, album art or app icon), or null. */
    fun load(context: Context, f: File, max: Int): ImageBitmap? = runCatching {
        val bmp: Bitmap? = when (Fs.kindOf(f)) {
            Kind.Image -> decode(f, max)
            Kind.Video -> ThumbnailUtils.createVideoThumbnail(f, Size(max, max), null)
            Kind.Audio -> ThumbnailUtils.createAudioThumbnail(f, Size(max, max), null)
            Kind.Apk -> {
                val pm = context.packageManager
                pm.getPackageArchiveInfo(f.path, 0)?.applicationInfo?.let { ai ->
                    ai.sourceDir = f.path
                    ai.publicSourceDir = f.path
                    ai.loadIcon(pm).toBitmap(max / 2, max / 2)
                }
            }
            else -> null
        }
        bmp?.asImageBitmap()
    }.getOrNull()

    /** Decodes a photo at roughly [max] px, the right way up. */
    fun decode(f: File, max: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= max && bounds.outHeight / (sample * 2) >= max) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rotation = runCatching { ExifInterface(f.path).rotationDegrees }.getOrDefault(0)
        if (rotation == 0) return bmp
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }
}

// ------------------------------------------------------------------ Text

private const val TEXT_LIMIT = 128 * 1024

private data class TextDoc(val chunks: List<String>, val truncated: Boolean, val binary: Boolean)

@Composable
fun TextScreen(file: File) {
    val context = LocalContext.current
    val doc by produceState<TextDoc?>(null, file.path) {
        value = withContext(Dispatchers.IO) { readText(file) }
        Prefs.addRecent(file.path)
    }
    Page {
        item { PageTitle(file.name) }
        item { Caption(Fs.bytes(file.length())) }
        val d = doc
        when {
            d == null -> item { Caption("Opening…", color = Palette.primary) }
            d.binary -> item { Caption("This doesn't look like text.") }
            d.chunks.isEmpty() -> item { Caption("This file is empty") }
            else -> d.chunks.forEach { chunk ->
                item {
                    Text(
                        chunk,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Palette.onSurface,
                    )
                }
            }
        }
        if (d?.truncated == true) item { Caption("Showing the first 128 KB") }
        item { ActionChip("Open with…", Icons.Rounded.Apps, { Fs.openWith(context, file) }) }
    }
}

private fun readText(f: File): TextDoc = try {
    val bytes = f.inputStream().buffered().use { ins ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (total < TEXT_LIMIT) {
            val n = ins.read(buf, 0, minOf(buf.size, TEXT_LIMIT - total))
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        out.toByteArray()
    }
    val binary = bytes.take(4096).any { it == 0.toByte() }
    // Small chunks scroll and scale nicely in a round list.
    val chunks = if (binary) emptyList() else String(bytes, Charsets.UTF_8)
        .replace("\r\n", "\n")
        .lines()
        .chunked(8)
        .map { it.joinToString("\n") }
        .flatMap { if (it.length > 600) it.chunked(600) else listOf(it) }
        .dropLastWhile { it.isBlank() }
    TextDoc(chunks, f.length() > TEXT_LIMIT, binary)
} catch (e: Exception) {
    TextDoc(listOf("Couldn't read this file: ${e.message}"), false, false)
}

// ------------------------------------------------------------------ Images

@Composable
fun ImageScreen(file: File) {
    val context = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    val image by produceState<ImageBitmap?>(null, file.path) {
        val b = withContext(Dispatchers.IO) { runCatching { Thumbs.decode(file, 1024)?.asImageBitmap() }.getOrNull() }
        value = b
        failed = b == null
        if (b != null) Prefs.addRecent(file.path)
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val zoomed = scale > 1.01f
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Turn the crown to zoom.
            .onRotaryScrollEvent {
                scale = (scale * (1f + it.verticalScrollPixels / 400f)).coerceIn(1f, 6f)
                if (scale <= 1.01f) offset = Offset.Zero
                true
            }
            .focusRequester(focus)
            .focusable()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1.01f) {
                        scale = 1f; offset = Offset.Zero
                    } else scale = 2.5f
                })
            }
            // Drag only while zoomed in, so a swipe still goes back when you aren't.
            .then(
                if (zoomed) Modifier.pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        offset += drag
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
            failed -> Box(Modifier.padding(24.dp)) {
                androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Caption("Arkheia can't show this picture.")
                    ActionChip("Open with…", Icons.Rounded.Apps, { Fs.openWith(context, file) })
                }
            }
            else -> Caption("Opening…", color = Palette.primary)
        }
    }
}

// ------------------------------------------------------------------ Audio

@Composable
fun AudioScreen(file: File) {
    val context = LocalContext.current
    val player = remember(file.path) {
        runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(file.path)
                prepare()
            }
        }.getOrNull()
    }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableIntStateOf(0) }
    val duration = remember(player) { player?.duration?.coerceAtLeast(0) ?: 0 }
    val art by produceState<ImageBitmap?>(null, file.path) {
        value = withContext(Dispatchers.IO) { Thumbs.load(context, file, 200) }
    }

    DisposableEffect(player) {
        player?.setOnCompletionListener {
            playing = false
            position = 0
        }
        onDispose { runCatching { player?.release() } }
    }
    LaunchedEffect(player) {
        if (player != null) {
            player.start()
            playing = true
            Prefs.addRecent(file.path)
        }
    }
    LaunchedEffect(playing) {
        while (playing) {
            position = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
            delay(300)
        }
    }

    fun toggle() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause(); playing = false
        } else {
            p.start(); playing = true
        }
    }
    fun seek(by: Int) {
        val p = player ?: return
        val to = (p.currentPosition + by).coerceIn(0, duration)
        p.seekTo(to)
        position = to
    }

    Page {
        if (player == null) {
            item { PageTitle(file.name) }
            item { Caption("Arkheia can't play this file.") }
            item { ActionChip("Open with…", Icons.Rounded.Apps, { Fs.openWith(context, file) }) }
            return@Page
        }
        item {
            Orb(
                progress = if (duration > 0) position.toFloat() / duration else 0f,
                modifier = Modifier.size(104.dp),
                color = Palette.violet,
            ) {
                val a = art
                Box(contentAlignment = Alignment.Center) {
                    if (a != null) {
                        Image(
                            a, null, contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(78.dp)
                                .clip(CircleShape),
                        )
                    }
                    Button(
                        onClick = { toggle() },
                        colors = ButtonDefaults.primaryButtonColors(),
                        modifier = Modifier.size(ButtonDefaults.DefaultButtonSize),
                    ) {
                        Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play")
                    }
                }
            }
        }
        item {
            Text(
                file.nameWithoutExtension,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                textAlign = TextAlign.Center,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Palette.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        item { Caption("${clock(position)} / ${clock(duration)}") }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
                Button(onClick = { seek(-10_000) }, colors = ButtonDefaults.secondaryButtonColors(), modifier = Modifier.size(ButtonDefaults.SmallButtonSize)) {
                    Icon(Icons.Rounded.Replay10, "Back 10 seconds")
                }
                Button(onClick = { seek(10_000) }, colors = ButtonDefaults.secondaryButtonColors(), modifier = Modifier.size(ButtonDefaults.SmallButtonSize)) {
                    Icon(Icons.Rounded.Forward10, "Forward 10 seconds")
                }
            }
        }
        item {
            ActionChip("Volume", Icons.AutoMirrored.Rounded.VolumeUp, {
                val am = context.getSystemService(AudioManager::class.java)
                am?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
            }, secondary = "Plays while this screen is open")
        }
    }
}

private fun clock(ms: Int): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
