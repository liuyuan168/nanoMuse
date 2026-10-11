package io.github.nanomuse.assistant

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * nanoMuse: inert recognition service referenced from
 * nm_voice_interaction_service.xml. The framework requires the assistant's
 * meta-data to name a recognitionService component; nanoMuse does its speech
 * recognition through the system recognizer (SpeechRecognitionManager), so
 * this one only exists to make the assistant entry valid and refuses every
 * request pointed at it.
 */
class NmRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent?, callback: Callback?) {
        callback?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onStopListening(callback: Callback?) {}

    override fun onCancel(callback: Callback?) {}
}
