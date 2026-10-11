package io.github.nanomuse.assistant

import android.service.voice.VoiceInteractionService

/**
 * nanoMuse: the system digital-assistant entry point.
 *
 * Declaring this service (with the `android.service.voice.VoiceInteractionService`
 * intent filter and the `android.service.voice` meta-data below) is what makes
 * nanoMuse appear in the OS "digital assistant app" picker, next to ChatGPT and
 * the vendor assistant. Once the user picks nanoMuse there, the assistant
 * gestures — long-press power on HyperOS, corner swipe — route here, and
 * [NmAssistantSession] forwards the invocation to the same voice-chat deep link
 * the app-icon long-press uses.
 *
 * The class itself stays empty on purpose: the framework binds it, reads the
 * meta-data to find [NmAssistantSessionService], and instantiates sessions
 * through it. Assist payloads arrive on the session, not here.
 */
class NmAssistantService : VoiceInteractionService()
