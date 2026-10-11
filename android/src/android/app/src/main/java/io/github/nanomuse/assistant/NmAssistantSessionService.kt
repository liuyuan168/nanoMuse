package io.github.nanomuse.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * nanoMuse: instantiates the assistant session the framework asks for when the
 * user triggers the digital-assistant gesture while nanoMuse is the system
 * assistant. Referenced from res/xml/nm_voice_interaction_service.xml.
 */
class NmAssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return NmAssistantSession(this)
    }
}
