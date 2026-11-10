package com.arkheia.app.adb

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.arkheia.app.Fs
import com.arkheia.app.MainActivity
import com.arkheia.app.R
import io.github.muntashirakon.adb.AdbPairingRequiredException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Turns on All files access without a computer: Arkheia pairs with the watch's own
 * Wireless debugging, runs `appops set … MANAGE_EXTERNAL_STORAGE allow`, and disconnects.
 */
object Unlock {
    sealed interface State {
        data object Idle : State
        /** Waiting for Wireless debugging to show up. */
        data object Searching : State
        /** Wireless debugging is on; waiting for a pairing code. */
        data class Ready(val pairing: Boolean) : State
        data class Working(val message: String) : State
        data object Done : State
        data class Failed(val message: String) : State
    }

    val state = MutableStateFlow<State>(State.Idle)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val lock = Mutex()
    private var watcher: Job? = null
    private var lastPairPort: Int? = null
    private var askedForPort: Int? = null
    private val triedPorts = mutableSetOf<Int>()

    fun developerOptionsOn(c: Context) =
        runCatching { Settings.Global.getInt(c.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1 }.getOrDefault(false)

    fun wirelessDebuggingOn(c: Context) =
        runCatching { Settings.Global.getInt(c.contentResolver, "adb_wifi_enabled", 0) == 1 }.getOrDefault(false) ||
            SelfDiscovery.ports.value.any

    /** Start watching for Wireless debugging. Safe to call often. */
    fun start(context: Context) {
        val c = context.applicationContext
        if (Fs.hasAccess()) {
            state.value = State.Done
            return
        }
        SelfDiscovery.start(c)
        if (watcher?.isActive == true) return
        if (state.value is State.Idle || state.value is State.Done) state.value = State.Searching
        watcher = scope.launch {
            // Give up quietly after a while so we're not browsing mDNS forever.
            withTimeoutOrNull(30 * 60_000L) {
                SelfDiscovery.ports.collect { p -> onPorts(c, p) }
            }
            if (state.value !is State.Done) SelfDiscovery.stop()
        }
    }

    fun stop() {
        watcher?.cancel()
        watcher = null
        SelfDiscovery.stop()
    }

    private fun busy() = state.value is State.Working || state.value is State.Done

    private fun onPorts(c: Context, p: Ports) {
        if (busy()) return
        p.pair?.let { lastPairPort = it }
        if (p.pair != null && askedForPort != p.pair) {
            askedForPort = p.pair
            Notify.askForCode(c)
        }
        if (p.pair == null && p.connect == null && p.legacy == null) {
            if (state.value !is State.Failed) state.value = State.Searching
            return
        }
        if (state.value !is State.Failed || p.pair != null) state.value = State.Ready(pairing = p.pair != null)

        // Paired before, or an older watch that just asks "Allow debugging?": no code needed.
        val port = p.connect ?: p.legacy
        if (port != null && triedPorts.add(port)) scope.launch { grant(c, port, quiet = true) }
    }

    /** The six-digit code from Wireless debugging › Pair new device (from the notification or the app). */
    fun submitCode(context: Context, code: String, done: () -> Unit = {}) {
        val c = context.applicationContext
        scope.launch {
            try {
                pairAndGrant(c, code)
            } finally {
                done()
            }
        }
    }

    private suspend fun pairAndGrant(c: Context, code: String) {
        val digits = code.filter { it.isDigit() }
        if (digits.length != 6) {
            fail(c, "The pairing code has 6 digits. Tap Pair new device again for a new one.")
            return
        }
        val pairPort = SelfDiscovery.ports.value.pair ?: lastPairPort
        if (pairPort == null) {
            fail(c, "Open Wireless debugging › Pair new device first, then enter the code it shows.")
            return
        }
        state.value = State.Working("Pairing…")
        Notify.progress(c, "Pairing…")
        val paired = runCatching { SelfAdb.pair(c, pairPort, digits) }
        if (paired.isFailure) {
            fail(c, "Pairing didn't work. Codes only last while Pair new device is open: open it again and use the new code.")
            return
        }
        state.value = State.Working("Paired. Connecting…")
        Notify.progress(c, "Connecting…")
        val port = withTimeoutOrNull(15_000) {
            SelfDiscovery.ports.first { it.connect != null }.connect
        } ?: SelfDiscovery.ports.value.legacy
        if (port == null) {
            fail(c, "Paired, but Wireless debugging isn't showing a port. Switch it off and on, and Arkheia will finish by itself.")
            triedPorts.clear()
            return
        }
        triedPorts.add(port)
        grant(c, port, quiet = false)
    }

    /** Connect and run the appops command. [quiet]: don't complain if the watch just wants pairing first. */
    private suspend fun grant(c: Context, port: Int, quiet: Boolean) = lock.withLock {
        if (Fs.hasAccess()) {
            finish(c)
            return@withLock
        }
        val before = state.value
        state.value = State.Working("Connecting…")
        val connected = runCatching { SelfAdb.connect(c, port) }
        val ok = connected.getOrDefault(false)
        if (!ok) {
            SelfAdb.disconnect()
            val needsPairing = connected.exceptionOrNull()?.let { it is AdbPairingRequiredException || it.cause is AdbPairingRequiredException } ?: false
            if (quiet || needsPairing) {
                state.value = if (before is State.Working) State.Ready(SelfDiscovery.ports.value.pair != null) else before
                if (!quiet) fail(c, "The watch wants Arkheia paired first: tap Pair new device and enter the code.")
            } else {
                fail(c, "Couldn't connect to Wireless debugging. If the watch asked \"Allow debugging?\", tap Allow, then try again.")
            }
            return@withLock
        }
        state.value = State.Working("Turning on file access…")
        val pkg = c.packageName
        val out = runCatching {
            SelfAdb.sh(
                "appops set --uid $pkg MANAGE_EXTERNAL_STORAGE allow; " +
                    "appops set $pkg REQUEST_INSTALL_PACKAGES allow"
            )
        }
        SelfAdb.disconnect()
        // The change is immediate, but give the system a moment.
        repeat(10) {
            if (Fs.hasAccess()) return@repeat
            delay(300)
        }
        if (Fs.hasAccess()) {
            finish(c)
        } else {
            val msg = out.getOrNull()?.trim()?.take(120).orEmpty()
            fail(c, "The watch didn't accept the change${if (msg.isNotEmpty()) ": $msg" else "."}")
        }
    }

    private fun finish(c: Context) {
        state.value = State.Done
        Notify.done(c)
        stop()
        Fs.changed()
    }

    private fun fail(c: Context, message: String) {
        state.value = State.Failed(message)
        Notify.failed(c, message)
    }

    fun dismissFailure(c: Context) {
        if (state.value is State.Failed) {
            state.value = if (SelfDiscovery.ports.value.any) State.Ready(SelfDiscovery.ports.value.pair != null) else State.Searching
        }
        triedPorts.clear()
        SelfDiscovery.restart(c)
    }
}

/** The "type the pairing code here" notification, so the code screen can stay open in Settings. */
object Notify {
    private const val CHANNEL = "unlock"
    private const val ID = 7
    const val KEY_CODE = "code"

    fun allowed(c: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(c).areNotificationsEnabled()

    private fun channel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Unlocking file access", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Asks for the Wireless debugging pairing code"
                }
            )
        }
    }

    private fun openApp(c: Context) = PendingIntent.getActivity(
        c, 0,
        Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun post(c: Context, b: NotificationCompat.Builder) {
        if (!allowed(c)) return
        channel(c)
        runCatching { NotificationManagerCompat.from(c).notify(ID, b.build()) }
    }

    private fun base(c: Context, title: String, text: String) =
        NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(c))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(false)

    fun askForCode(c: Context) {
        val reply = PendingIntent.getBroadcast(
            c, 1, Intent(c, PairReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val action = NotificationCompat.Action.Builder(R.drawable.ic_tile, "Enter code", reply)
            .addRemoteInput(RemoteInput.Builder(KEY_CODE).setLabel("6-digit pairing code").build())
            .setAllowGeneratedReplies(false)
            .build()
        post(
            c,
            base(c, "Enter the pairing code", "Type the 6-digit code shown under Pair new device. Keep that screen open.")
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .addAction(action)
                .extend(NotificationCompat.WearableExtender().addAction(action))
                .setAutoCancel(false)
        )
    }

    fun progress(c: Context, text: String) =
        post(c, base(c, "Arkheia", text).setOngoing(true).setSilent(true).setProgress(0, 0, true))

    fun done(c: Context) =
        post(c, base(c, "File access is on", "Open Arkheia to see all your files. You can turn Wireless debugging off again.").setAutoCancel(true))

    fun failed(c: Context, message: String) =
        post(c, base(c, "Couldn't unlock yet", message).setAutoCancel(true).setSilent(true))
}

/** Gets the code typed into the notification. */
class PairReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notify.KEY_CODE)?.toString() ?: return
        val pending = goAsync()
        Unlock.submitCode(context, code) { pending.finish() }
    }
}
