package com.arkheia.app.watch

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import com.arkheia.app.Fs
import kotlinx.coroutines.delay

/** File access status, and the buttons that ask for it. */
class AccessUi(
    val context: Context,
    val full: Boolean,
    val media: Boolean,
    /** True once we've learned this watch has no All files access page. */
    val noSwitch: Boolean,
    val openSwitch: () -> Unit,
    val askMedia: () -> Unit,
)

@Composable
fun rememberAccessUi(): AccessUi {
    val context = LocalContext.current
    val full = rememberAccess()
    var media by remember { mutableStateOf(Fs.hasMediaAccess(context)) }
    var noSwitch by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            media = Fs.hasMediaAccess(context)
            delay(1_200)
        }
    }
    val dialog = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        media = Fs.hasMediaAccess(context)
    }
    val askMedia = { runCatching { dialog.launch(Fs.mediaPermissions) }; Unit }
    return AccessUi(
        context = context,
        full = full,
        media = media,
        noSwitch = noSwitch,
        openSwitch = {
            when (Fs.openAccessSettings(context)) {
                Fs.Opened.AllFiles -> Fs.toast(context, "Turn on Allow access to manage all files")
                Fs.Opened.AppInfo -> {
                    noSwitch = true
                    Fs.toast(context, "Look under Permissions › Files")
                }
                Fs.Opened.Nothing -> {
                    // No switch anywhere: go straight to the normal Allow pop-up.
                    noSwitch = true
                    askMedia()
                }
            }
        },
        askMedia = askMedia,
    )
}

/** One button to the watch's own switch, and the Allow pop-up if the watch doesn't have one. */
fun ScalingLazyListScope.accessSteps(ui: AccessUi) {
    if (ui.full) {
        item { ActionChip("All files access is on", Icons.Rounded.CheckCircle, {}, tint = Palette.sage) }
        return
    }
    item {
        ActionChip(
            "Allow file access", Icons.Rounded.Lock, ui.openSwitch,
            secondary = "Opens the switch in your watch's Settings", primary = true,
        )
    }
    if (ui.media) {
        item { Caption("Photos, videos and music are allowed. Other files need the switch above.", color = Palette.sage) }
    } else {
        item {
            ActionChip(
                "Just photos, videos & music", Icons.Rounded.PhotoLibrary, ui.askMedia,
                secondary = if (ui.noSwitch) "Your watch has no all-files switch: allow these instead" else "Quick Allow pop-up",
            )
        }
    }
}
