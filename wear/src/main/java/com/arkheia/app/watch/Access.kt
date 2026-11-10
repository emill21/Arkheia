package com.arkheia.app.watch

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Looks3
import androidx.compose.material.icons.rounded.LooksOne
import androidx.compose.material.icons.rounded.LooksTwo
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import com.arkheia.app.Fs
import com.arkheia.app.adb.Notify
import com.arkheia.app.adb.Unlock
import kotlinx.coroutines.delay

/** Everything the unlock steps need, gathered in a composable so the list builder can stay plain. */
class UnlockUi(
    val context: Context,
    val developerOn: Boolean,
    val wirelessOn: Boolean,
    val notifications: Boolean,
    val state: Unlock.State,
    val enterCode: () -> Unit,
    val askNotifications: () -> Unit,
)

@Composable
fun rememberUnlockUi(): UnlockUi {
    val context = LocalContext.current
    val state by Unlock.state.collectAsStateWithLifecycle()
    var developerOn by remember { mutableStateOf(Unlock.developerOptionsOn(context)) }
    var wirelessOn by remember { mutableStateOf(Unlock.wirelessDebuggingOn(context)) }
    var notifications by remember { mutableStateOf(Notify.allowed(context)) }
    LaunchedEffect(Unit) {
        Unlock.start(context)
        while (true) {
            developerOn = Unlock.developerOptionsOn(context)
            wirelessOn = Unlock.wirelessDebuggingOn(context)
            notifications = Notify.allowed(context)
            delay(1_200)
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifications = Notify.allowed(context) }
    val code = rememberTextInput { Unlock.submitCode(context, it) }
    return UnlockUi(
        context = context,
        developerOn = developerOn,
        wirelessOn = wirelessOn,
        notifications = notifications,
        state = state,
        enterCode = { code("6-digit pairing code") },
        askNotifications = {
            if (Build.VERSION.SDK_INT >= 33) {
                runCatching { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }
            } else {
                open(context, Settings.ACTION_APP_NOTIFICATION_SETTINGS, withPackage = true)
            }
        },
    )
}

private fun open(c: Context, action: String, withPackage: Boolean = false): Boolean = try {
    val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (withPackage) i.putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName)
    c.startActivity(i)
    true
} catch (e: Exception) {
    false
}

/**
 * The three steps to turn on All files access from the watch itself:
 * Developer options → Wireless debugging → pair Arkheia with the code.
 */
fun ScalingLazyListScope.unlockSteps(ui: UnlockUi) {
    val c = ui.context
    val s = ui.state
    if (s is Unlock.State.Done) {
        item { ActionChip("File access is on", Icons.Rounded.CheckCircle, {}, tint = Palette.sage) }
        return
    }
    item { Caption("Arkheia can switch this on by itself through Wireless debugging. No computer or phone needed, just Wi-Fi.") }

    item {
        ActionChip(
            "Developer options",
            if (ui.developerOn) Icons.Rounded.CheckCircle else Icons.Rounded.LooksOne,
            {
                if (!open(c, Settings.ACTION_DEVICE_INFO_SETTINGS)) open(c, Settings.ACTION_SETTINGS)
            },
            secondary = if (ui.developerOn) "On" else "Tap Build number 7 times (in About or Versions)",
            tint = if (ui.developerOn) Palette.sage else Palette.primary,
        )
    }
    item {
        ActionChip(
            "Wireless debugging",
            if (ui.wirelessOn) Icons.Rounded.CheckCircle else Icons.Rounded.LooksTwo,
            {
                if (!open(c, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) open(c, Settings.ACTION_SETTINGS)
            },
            secondary = if (ui.wirelessOn) "On" else "Developer options › Wireless debugging (or Debug over Wi-Fi)",
            tint = if (ui.wirelessOn) Palette.sage else Palette.primary,
        )
    }
    if (!ui.notifications) {
        item {
            ActionChip(
                "Allow notifications", Icons.Rounded.Notifications, ui.askNotifications,
                secondary = "So you can type the code without leaving Settings",
            )
        }
    }
    item {
        ActionChip(
            "Pair Arkheia",
            Icons.Rounded.Looks3,
            {
                if (!open(c, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) open(c, Settings.ACTION_SETTINGS)
            },
            secondary = if (ui.notifications) "In Wireless debugging tap Pair new device, then type the code into Arkheia's notification"
            else "In Wireless debugging tap Pair new device, then come back and enter the code",
        )
    }
    item {
        ActionChip("Enter code here", Icons.Rounded.Dialpad, ui.enterCode, secondary = "If the notification didn't show")
    }

    when (s) {
        is Unlock.State.Working -> item { Caption(s.message, color = Palette.primary) }
        is Unlock.State.Failed -> {
            item { Caption(s.message, color = Palette.red) }
            item { ActionChip("Try again", Icons.Rounded.Refresh, { Unlock.dismissFailure(c) }) }
        }
        is Unlock.State.Ready -> item {
            Caption(
                if (s.pairing) "Pairing code is on screen: enter it now." else "Wireless debugging found. Now tap Pair new device.",
                color = Palette.primary,
            )
        }
        Unlock.State.Searching -> if (ui.wirelessOn) item { Caption("Looking for Wireless debugging… Make sure the watch is on Wi-Fi.") }
        else -> {}
    }
    item { Caption("Arkheia pairs only with this watch, runs one command to allow file access, then disconnects. You can turn Wireless debugging off afterwards.") }

    item { Section("Other ways") }
    item {
        ActionChip("Settings switch", Icons.Rounded.Tune, {
            if (!Fs.openAccessSettings(c)) Fs.toast(c, "Your watch doesn't have one")
        }, secondary = "Some watches have an All files access switch")
    }
    item { Caption("From a computer:") }
    item { CommandCard(Fs.ADB_GRANT) }
}
