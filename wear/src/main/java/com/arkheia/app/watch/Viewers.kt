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
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.foundation.layout.Column
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

// ------------------------------------------------------------------ Audio

/** Music: a spinning record in the middle, progress round the edge, crown to scrub. */
@Composable
fun AudioScreen(file: File) {
    val context = LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
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
    var spin by remember { mutableFloatStateOf(0f) }
    val duration = remember(player) { player?.duration?.coerceAtLeast(0) ?: 0 }
    val art by produceState<ImageBitmap?>(null, file.path) {
        value = withContext(Dispatchers.IO) { Thumbs.load(context, file, 240) }
    }
    val focus = remember { FocusRequester() }
    RequestFocusWhenResumed(focus)
    var crownAcc by remember { mutableFloatStateOf(0f) }
    var volume by remember { mutableFloatStateOf(Volume.level(context)) }
    var volumeShownAt by remember { mutableStateOf(0L) }
    LaunchedEffect(volumeShownAt) {
        if (volumeShownAt > 0) {
            delay(1_500)
            volumeShownAt = 0L
        }
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
        // Spin the record about 33⅓ rpm-ish while playing.
        var last = 0L
        while (playing) {
            androidx.compose.runtime.withFrameMillis { now ->
                if (last != 0L) spin = (spin + (now - last) * 0.2f) % 360f
                last = now
            }
            position = runCatching { player?.currentPosition ?: 0 }.getOrDefault(position)
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

    if (player == null) {
        Page(title = file.name) {
            item { Caption("Arkheia can't play this file.") }
            item { ActionChip("Open with…", Icons.Rounded.Apps, { Fs.openWith(context, file) }) }
        }
        return
    }

    androidx.wear.compose.material.Scaffold(timeText = { androidx.wear.compose.material.TimeText() }) {
        Box(
            Modifier
                .fillMaxSize()
                .onRotaryScrollEvent {
                    if (Prefs.crownVolume) {
                        crownAcc += it.verticalScrollPixels
                        if (kotlin.math.abs(crownAcc) > 38f) {
                            Volume.step(context, up = crownAcc > 0)
                            volume = Volume.level(context)
                            volumeShownAt = System.currentTimeMillis()
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                            crownAcc = 0f
                        }
                    } else {
                        seek((it.verticalScrollPixels * 60).toInt())
                    }
                    true
                }
                .focusRequester(focus)
                .focusable(),
            contentAlignment = Alignment.Center,
        ) {
            val p = if (duration > 0) position.toFloat() / duration else 0f
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                ring(Arcs.topGap, listOf(p to Palette.violet), 1f, 6.dp, 3.dp)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // The record.
                Box(
                    Modifier
                        .size(96.dp)
                        .graphicsLayer { rotationZ = spin }
                        .clip(CircleShape)
                        .background(
                            androidx.compose.ui.graphics.Brush.sweepGradient(
                                listOf(Color(0xFF1B1622), Color(0xFF3A2F4A), Color(0xFF1B1622), Color(0xFF3A2F4A), Color(0xFF1B1622))
                            )
                        )
                        .clickable { toggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        // Grooves.
                        val r = size.minDimension / 2
                        for (i in 1..6) {
                            drawCircle(Color.White.copy(alpha = 0.05f), r * (0.45f + i * 0.085f), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
                        }
                    }
                    val a = art
                    Box(Modifier.size(44.dp).clip(CircleShape).background(Palette.violet.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                        if (a != null) Image(a, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else Icon(Icons.Rounded.MusicNote, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Color.Black))
                }
                Text(
                    file.nameWithoutExtension,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 4.dp),
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Palette.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundButton(Icons.Rounded.Replay10, "Back 10 seconds", { seek(-10_000) }, tint = Palette.violet, size = 32.dp)
                    RoundButton(
                        if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play",
                        { toggle() }, tint = Palette.violet, size = 42.dp, filled = true,
                    )
                    RoundButton(Icons.Rounded.Forward10, "Forward 10 seconds", { seek(10_000) }, tint = Palette.violet, size = 32.dp)
                }
            }
            CurvedCaption("${clock(position)} / ${clock(duration)}", color = Palette.violet)
            Box(Modifier.align(Alignment.BottomEnd).padding(end = 26.dp, bottom = 34.dp)) {
                RoundButton(
                    if (Prefs.crownVolume) Icons.AutoMirrored.Rounded.VolumeUp else Icons.Rounded.FastForward,
                    "Switch what the crown does",
                    {
                        Prefs.crownVolume = !Prefs.crownVolume
                        Fs.toast(context, if (Prefs.crownVolume) "Crown: volume" else "Crown: skip")
                        if (Prefs.crownVolume) {
                            volume = Volume.level(context)
                            volumeShownAt = System.currentTimeMillis()
                        }
                    },
                    tint = Palette.violet, size = 28.dp, filled = Prefs.crownVolume,
                )
            }
            if (volumeShownAt > 0) VolumeArc(volume, Palette.violet)
        }
    }
}

private fun clock(ms: Int): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
