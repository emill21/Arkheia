package com.arkheia.app.watch

import android.os.Environment
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.arkheia.app.Fs
import com.arkheia.app.MainActivity
import com.arkheia.app.Prefs

/** Tile: free space, and buttons straight into All files and your first pinned folder (or Download). */
class FilesTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        Prefs.init(this)
        val s = Fs.storage()
        val second = Prefs.pins.firstOrNull()?.let { java.io.File(it) }?.takeIf { it.isDirectory }
            ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val secondLabel = if (Prefs.pins.isNotEmpty() && second.path == Prefs.pins.first()) second.name else "Download"

        val column = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text("Arkheia", 13f, MUTED, bold = false))
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(2f)).build())
            .addContent(text(if (s.total > 0) "${Fs.bytes(s.free)} free" else "Files", 18f, ON_SURFACE, bold = true))
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(10f)).build())
            .addContent(button("all", "All files", Fs.root.path, filled = true))
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(6f)).build())
            .addContent(button("second", secondLabel.take(14), second.path, filled = false))
            .build()

        val root = LayoutElementBuilders.Box.Builder()
            .setWidth(expand()).setHeight(expand())
            .addContent(column)
            .build()

        return Futures.immediateFuture(
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RES_VERSION)
                .setFreshnessIntervalMillis(30 * 60_000L)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
                .build()
        )
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion(RES_VERSION).build())

    private fun button(id: String, label: String, path: String, filled: Boolean): LayoutElementBuilders.LayoutElement {
        val launch = ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(packageName)
                    .setClassName(MainActivity::class.java.name)
                    .addKeyToExtraMapping(MainActivity.EXTRA_OPEN, ActionBuilders.AndroidStringExtra.Builder().setValue(path).build())
                    .build()
            ).build()
        val mods = ModifiersBuilders.Modifiers.Builder()
            .setClickable(ModifiersBuilders.Clickable.Builder().setId(id).setOnClick(launch).build())
            .setBackground(
                ModifiersBuilders.Background.Builder()
                    .setColor(argb(if (filled) PRIMARY else SURFACE))
                    .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(20f)).build())
                    .build()
            )
            .build()
        return LayoutElementBuilders.Box.Builder()
            .setWidth(dp(124f)).setHeight(dp(40f))
            .setModifiers(mods)
            .addContent(text(label, 14f, if (filled) ON_PRIMARY else ON_SURFACE, bold = true))
            .build()
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean) =
        LayoutElementBuilders.Text.Builder()
            .setText(value)
            .setMaxLines(1)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(size))
                    .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                    .setColor(argb(color))
                    .build()
            ).build()

    companion object {
        private const val RES_VERSION = "1"
        const val PRIMARY = 0xFFFFC870.toInt()
        const val ON_PRIMARY = 0xFF422C00.toInt()
        const val SURFACE = 0xFF2E281F.toInt()
        const val ON_SURFACE = 0xFFEDE1D4.toInt()
        const val MUTED = 0xFFD3C4B4.toInt()
    }
}
