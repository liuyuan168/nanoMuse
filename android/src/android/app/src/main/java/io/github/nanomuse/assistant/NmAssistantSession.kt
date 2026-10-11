package io.github.nanomuse.assistant

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import com.openminis.app.MainActivity

/**
 * nanoMuse: what happens when the user triggers the digital-assistant gesture
 * (HyperOS long-press power, assistant corner swipe) with nanoMuse as the
 * system assistant.
 *
 * The session itself shows no UI. It forwards the invocation to the exact
 * voice-chat deep link the app-icon long-press uses
 * (`minis://action/voice_chat`): MainActivity resolves it, ChatScreen opens a
 * new chat and auto-fires the mic on first compose, so the user talks to
 * nanoMuse straight away — same path as the quick action, one behaviour.
 *
 * The empty session window is hidden and finished right after launching, so
 * the system animation hands over to the app without a blank flash.
 */
class NmAssistantSession constructor(context: android.content.Context) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (showFlags and VoiceInteractionSession.SHOW_WITH_ASSIST == 0) return
        val intent = Intent(Intent.ACTION_VIEW, VOICE_CHAT_URI)
            .setClass(getContext(), MainActivity::class.java)
            // nm_wake: MainActivity shows above the keyguard and turns the
            // screen on for this launch (see its onNewIntent/onCreate).
            .putExtra("nm_wake", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // The session's own context launches: while the session is showing,
        // the system exempts this launch from the background-activity rules.
        getContext().startActivity(intent)
        hide()
        finish()
    }

    companion object {
        /** Same deep link as the voice_chat launcher shortcut (res/xml/shortcuts.xml). */
        val VOICE_CHAT_URI: Uri = Uri.parse("minis://action/voice_chat")
    }
}
