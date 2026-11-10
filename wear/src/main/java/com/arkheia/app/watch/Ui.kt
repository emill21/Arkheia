package com.arkheia.app.watch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import androidx.wear.compose.material.curvedText
import com.arkheia.app.Entry
import com.arkheia.app.Fs
import com.arkheia.app.Kind

/** Arkheia's amber palette (from the icon), tuned for an OLED watch: true black behind everything. */
object Palette {
    val primary = Color(0xFFFFC870)
    val primaryContainer = Color(0xFF7A5200)
    val onPrimary = Color(0xFF422C00)
    val surface = Color(0xFF221D16)
    val surfaceHigh = Color(0xFF2E281F)
    val onSurface = Color(0xFFEDE1D4)
    val muted = Color(0xFFD3C4B4)
    val sage = Color(0xFFB8CFA0)
    val sky = Color(0xFFA8C8F0)
    val rose = Color(0xFFF2B8C6)
    val violet = Color(0xFFCDB8F2)
    val red = Color(0xFFFFB4AB)
    val track = Color(0x33FFDDA0)
}

@Composable
fun ArkheiaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = Colors(
            primary = Palette.primary,
            primaryVariant = Palette.primaryContainer,
            secondary = Palette.sage,
            secondaryVariant = Palette.primaryContainer,
            background = Color.Black,
            surface = Palette.surface,
            error = Palette.red,
            onPrimary = Palette.onPrimary,
            onSecondary = Color(0xFF233517),
            onBackground = Palette.onSurface,
            onSurface = Palette.onSurface,
            onSurfaceVariant = Palette.muted,
            onError = Color(0xFF690005),
        ),
        content = content,
    )
}

fun kindIcon(kind: Kind, locked: Boolean = false): ImageVector = when (kind) {
    Kind.Folder -> if (locked) Icons.Rounded.Lock else Icons.Rounded.Folder
    Kind.Image -> Icons.Rounded.Image
    Kind.Audio -> Icons.Rounded.AudioFile
    Kind.Video -> Icons.Rounded.VideoFile
    Kind.Text -> Icons.AutoMirrored.Rounded.Article
    Kind.Document -> Icons.Rounded.Description
    Kind.Archive -> Icons.Rounded.FolderZip
    Kind.Apk -> Icons.Rounded.Android
    Kind.Other -> Icons.Rounded.InsertDriveFile
}

fun kindTint(kind: Kind): Color = when (kind) {
    Kind.Folder -> Palette.primary
    Kind.Image -> Palette.rose
    Kind.Audio -> Palette.violet
    Kind.Video -> Palette.sky
    Kind.Text, Kind.Document -> Palette.onSurface
    Kind.Archive -> Palette.muted
    Kind.Apk -> Palette.sage
    Kind.Other -> Palette.muted
}

/**
 * One scrolling screen. [title] curves along the top next to the time, [bottom] curves along the
 * bottom edge, and [behind] draws under the list (e.g. a bezel ring).
 */
@Composable
fun Page(
    title: String? = null,
    bottom: String? = null,
    accent: Color = Palette.primary,
    inset: Dp = 10.dp,
    behind: (@Composable () -> Unit)? = null,
    content: ScalingLazyListScope.() -> Unit,
) {
    val state = rememberScalingLazyListState(initialCenterItemIndex = 0)
    Scaffold(
        timeText = {
            if (title != null) {
                TimeText(
                    startLinearContent = { Text(title, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    startCurvedContent = { curvedText(title.take(20), color = accent) },
                )
            } else {
                TimeText()
            }
        },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = state) },
    ) {
        Box(Modifier.fillMaxSize()) {
            behind?.invoke()
            ScalingLazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = state,
                contentPadding = PaddingValues(start = inset, end = inset, top = 28.dp, bottom = if (bottom != null) 44.dp else 36.dp),
                content = content,
            )
            if (bottom != null) CurvedCaption(bottom, color = accent.copy(alpha = 0.85f))
        }
    }
}

@Composable
fun PageTitle(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp, start = 12.dp, end = 12.dp),
        textAlign = TextAlign.Center,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        color = Palette.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun Caption(text: String, modifier: Modifier = Modifier, color: Color = Palette.muted) {
    Text(
        text,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        textAlign = TextAlign.Center,
        fontSize = 11.sp,
        color = color,
    )
}

@Composable
fun Section(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
        textAlign = TextAlign.Center,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = Palette.primary,
    )
}

/** Label/value lines inside a rounded card. */
@Composable
fun InfoCard(title: String, lines: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Palette.surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        if (title.isNotEmpty()) {
            Text(title, fontSize = 12.sp, color = Palette.primary, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
        }
        lines.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(k, fontSize = 12.sp, color = Palette.muted, modifier = Modifier.weight(1f), maxLines = 1)
                Spacer(Modifier.width(6.dp))
                Text(v, fontSize = 12.sp, color = Palette.onSurface, fontWeight = FontWeight.Medium, textAlign = TextAlign.End, maxLines = 3)
            }
        }
    }
}

/** A command line someone types on a computer: shown in full, small and monospace. */
@Composable
fun CommandCard(command: String) {
    Text(
        command,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        fontSize = 10.sp,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        color = Palette.onSurface,
        textAlign = TextAlign.Center,
    )
}

private val fileChipColors @Composable get() = ChipDefaults.chipColors(
    backgroundColor = Palette.surface,
    contentColor = Palette.onSurface,
    secondaryContentColor = Palette.muted,
    iconColor = Palette.primary,
)

/** A file or folder in a list. */
@Composable
fun EntryChip(e: Entry, onClick: () -> Unit, detail: String? = null) {
    val locked = e.isDir && e.children < 0
    Chip(
        onClick = onClick,
        label = { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = {
            Text(
                detail ?: if (e.isDir) Fs.items(e.children) else "${Fs.bytes(e.size)} · ${Fs.date(e.modified)}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        icon = { IconBlob(kindIcon(e.kind, locked), kindTint(e.kind)) },
        colors = fileChipColors,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** An icon sitting in a softly tinted circle. */
@Composable
fun IconBlob(icon: ImageVector, tint: Color) {
    Box(
        Modifier
            .size(26.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(Brush.radialGradient(listOf(tint.copy(alpha = 0.32f), tint.copy(alpha = 0.10f)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
    }
}

/** A plain action row: icon + label (+ optional second line). */
@Composable
fun ActionChip(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    secondary: String? = null,
    primary: Boolean = false,
    tint: Color = Palette.primary,
    enabled: Boolean = true,
) {
    Chip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = if (secondary != null) {
            { Text(secondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        } else null,
        icon = { if (primary) Icon(icon, null, tint = Palette.onPrimary) else IconBlob(icon, tint) },
        colors = if (primary) ChipDefaults.primaryChipColors() else fileChipColors,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun NextChip(text: String, onClick: () -> Unit) {
    Chip(
        onClick = onClick,
        label = { Text(text, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
        colors = ChipDefaults.primaryChipColors(),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
}

/** The glass orb with a comet ring, like the icon. [progress] fills the ring. */
@Composable
fun Orb(progress: Float, modifier: Modifier = Modifier, color: Color = Palette.primary, content: @Composable () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 6.dp.toPx()
            val inset = stroke / 2
            val arc = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
            drawArc(Palette.track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            if (progress > 0f) {
                drawArc(
                    Brush.sweepGradient(listOf(color.copy(alpha = 0.15f), color)),
                    -90f, 360f * progress.coerceIn(0f, 1f), false, Offset(inset, inset), arc,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            val r = size.minDimension / 2 - stroke * 2
            drawCircle(
                Brush.verticalGradient(listOf(Palette.primaryContainer.copy(alpha = 0.45f), Palette.primaryContainer.copy(alpha = 0.9f))),
                r,
            )
            drawCircle(
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
                    center = Offset(size.width * 0.38f, size.height * 0.36f),
                    radius = r,
                ),
                r,
            )
        }
        content()
    }
}

/** A thin horizontal bar, for "how much of the space is this". */
@Composable
fun Bar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Palette.track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(5.dp).clip(RoundedCornerShape(3.dp)).background(color))
    }
}
