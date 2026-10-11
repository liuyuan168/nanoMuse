package io.github.nanomuse.wakeword

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * nanoMuse: bring wake-word listening back after a reboot when the toggle is
 * on. Whether this fires at all is the OS's call (HyperOS needs the app's
 * autostart permission once, the same gate the hub service passes through);
 * when it does fire, the service still re-checks the persisted toggle before
 * claiming the mic.
 */
class WakeWordBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (WakeWordSettings(context).isEnabled()) {
                    AppLogger.info(TAG, "boot: wake word enabled, starting service")
                    WakeWordService.start(context)
                } else {
                    AppLogger.info(TAG, "boot: wake word disabled, nothing to do")
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "WakeWordBoot"
    }
}
