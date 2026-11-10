package com.arkheia.app.watch

import android.app.RemoteInput
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.wear.input.RemoteInputIntentHelper
import com.arkheia.app.Fs

private const val KEY = "text"

/**
 * Asks for a line of text with the watch's own keyboard / voice input.
 * Returns a function that opens the input; [onText] gets what was typed.
 */
@Composable
fun rememberTextInput(onText: (String) -> Unit): (String) -> Unit {
    val context: Context = LocalContext.current
    val callback by rememberUpdatedState(onText)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data ?: return@rememberLauncherForActivityResult
        val text = RemoteInput.getResultsFromIntent(data)?.getCharSequence(KEY)?.toString()?.trim()
        if (!text.isNullOrEmpty()) callback(text)
    }
    return remember(launcher) {
        { label: String ->
            try {
                val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
                RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(RemoteInput.Builder(KEY).setLabel(label).build()))
                launcher.launch(intent)
            } catch (e: Exception) {
                Fs.toast(context, "Your watch has no keyboard for this")
            }
        }
    }
}
