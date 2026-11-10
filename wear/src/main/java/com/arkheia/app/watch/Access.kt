package com.arkheia.app.watch

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import com.arkheia.app.Fs

/** File access status, and the button that asks for it. */
class AccessUi(
    val context: Context,
    val full: Boolean,
    /** The pop-up was turned down for good: only App info can change it now. */
    val blocked: Boolean,
    val allow: () -> Unit,
)

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
fun rememberAccessUi(): AccessUi {
    val context = LocalContext.current
    val full = rememberAccess()
    var blocked by remember { mutableStateOf(false) }
    val dialog = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (!Fs.hasFilesPermission(context)) {
            val activity = context.activity()
            // No pop-up shown and no chance to ask again: Android has stopped asking.
            blocked = activity != null && Fs.filePermissions.none { p -> ActivityCompat.shouldShowRequestPermissionRationale(activity, p) }
            if (blocked) Fs.toast(context, "Turn on Files in Permissions")
            if (blocked) Fs.openAppInfo(context)
        }
        Fs.changed()
    }
    return AccessUi(
        context = context,
        full = full,
        blocked = blocked,
        allow = {
            if (blocked) {
                Fs.toast(context, "Turn on Files in Permissions")
                Fs.openAppInfo(context)
            } else {
                runCatching { dialog.launch(Fs.filePermissions) }.onFailure { Fs.openAppInfo(context) }
            }
        },
    )
}

/** Ask for the watch's Files permission. */
fun ScalingLazyListScope.accessSteps(ui: AccessUi) {
    if (ui.full) {
        item { ActionChip("Files permission is on", Icons.Rounded.CheckCircle, {}, tint = Palette.sage) }
        return
    }
    item {
        ActionChip(
            "Allow file access", Icons.Rounded.Folder, ui.allow,
            secondary = if (ui.blocked) "Opens App info: Permissions › Files" else "Tap Allow on the pop-up",
            primary = true,
        )
    }
    if (!ui.blocked) {
        item {
            ActionChip(
                "Open App info", Icons.Rounded.Settings, { Fs.openAppInfo(ui.context) },
                secondary = "Or turn on Files under Permissions",
            )
        }
    }
}
