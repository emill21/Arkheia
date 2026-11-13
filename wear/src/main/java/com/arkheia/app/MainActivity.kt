package com.arkheia.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.arkheia.app.watch.ArkheiaApp
import com.arkheia.app.watch.ArkheiaTheme
import com.arkheia.app.watch.FilesTileService
import com.arkheia.app.watch.SetupScreen
import com.arkheia.app.watch.WhatsNewScreen

/** Side-button presses, for screens that want them (the video player plays/pauses). */
object StemButton {
    val presses = kotlinx.coroutines.flow.MutableSharedFlow<Int>(extraBufferCapacity = 1)
    @Volatile var listening = 0
}

/** Arkheia: a file explorer for Wear OS. */
class MainActivity : ComponentActivity() {
    /** A folder the tile asked us to open. */
    private val openRequest = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        if (savedInstanceState == null) openRequest.value = requestFrom(intent)
        val latest = BuildConfig.VERSION_CODE
        setContent {
            ArkheiaTheme {
                var screen by remember {
                    mutableStateOf(
                        when {
                            !Prefs.onboarded -> Screen.Setup
                            Prefs.lastSeenVersion < latest && Changelog.load(this).isNotEmpty() -> Screen.WhatsNew
                            else -> Screen.Main
                        }
                    )
                }
                AnimatedContent(screen, transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(200)) }, label = "root") { s ->
                    when (s) {
                        Screen.Setup -> SetupScreen {
                            Prefs.onboarded = true
                            Prefs.lastSeenVersion = latest
                            screen = Screen.Main
                        }
                        Screen.WhatsNew -> WhatsNewScreen(Changelog.load(this@MainActivity).take(2)) {
                            Prefs.lastSeenVersion = latest
                            screen = Screen.Main
                        }
                        Screen.Main -> ArkheiaApp(
                            openRequest = openRequest.value,
                            onOpened = { openRequest.value = null },
                            onReplayTour = { screen = Screen.Setup },
                            onWhatsNew = { screen = Screen.WhatsNew },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestFrom(intent)?.let { openRequest.value = it }
    }

    override fun onResume() {
        super.onResume()
        Fs.changed()
        runCatching {
            androidx.wear.tiles.TileService.getUpdater(this).requestUpdate(FilesTileService::class.java)
        }
    }

    /** Extra side buttons, on watches that hand them to apps (Pixel Watch keeps both for itself). */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (event?.repeatCount == 0 && keyCode in listOf(
                android.view.KeyEvent.KEYCODE_STEM_1,
                android.view.KeyEvent.KEYCODE_STEM_2,
                android.view.KeyEvent.KEYCODE_STEM_3,
            )
        ) {
            if (StemButton.listening > 0 && StemButton.presses.tryEmit(keyCode)) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** A folder from the tile, or your phone's files from Syndesia. */
    private fun requestFrom(i: Intent?): String? =
        if (i?.getBooleanExtra(EXTRA_PHONE, false) == true) com.arkheia.app.watch.PHONE_REQUEST else i?.getStringExtra(EXTRA_OPEN)

    enum class Screen { Setup, WhatsNew, Main }

    companion object {
        const val EXTRA_OPEN = "open"
        const val EXTRA_PHONE = "phone"
    }
}
