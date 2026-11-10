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

/** Arkheia: a file explorer for Wear OS. */
class MainActivity : ComponentActivity() {
    /** A folder the tile asked us to open. */
    private val openRequest = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        if (savedInstanceState == null) openRequest.value = intent?.getStringExtra(EXTRA_OPEN)
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
        intent.getStringExtra(EXTRA_OPEN)?.let { openRequest.value = it }
    }

    override fun onResume() {
        super.onResume()
        Fs.changed()
        runCatching {
            androidx.wear.tiles.TileService.getUpdater(this).requestUpdate(FilesTileService::class.java)
        }
    }

    enum class Screen { Setup, WhatsNew, Main }

    companion object {
        const val EXTRA_OPEN = "open"
    }
}
