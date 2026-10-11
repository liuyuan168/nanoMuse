package io.github.nanomuse.wakeword

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * nanoMuse: diagnostics hook. A broadcast to this receiver asks the running
 * wake-word loop to dump the next few seconds of raw mic audio to
 * filesDir/wake-dump.pcm (see WakeWordService.writeDump), for offline
 * inspection of what the spotter is actually being fed. Not exported; the
 * only sender that needs it is adb during development.
 */
class WakeWordDumpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WakeWordService.requestDumpNow()
    }
}
