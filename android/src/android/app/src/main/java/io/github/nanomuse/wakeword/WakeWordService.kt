package io.github.nanomuse.wakeword

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.MainActivity
import com.openminis.app.logging.AppLogger
import io.github.nanomuse.assistant.NmAssistantSession
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * nanoMuse: always-on wake-word listening. A microphone-type foreground
 * service that streams 16 kHz mono audio through the on-device sherpa-onnx
 * keyword spotter; on a hit it opens the same voice-chat deep link the
 * assistant gesture uses ([NmAssistantSession]).
 *
 * Deliberate behaviours:
 *  - Detection pauses while any activity of ours is foreground: the chat
 *    screen owns the mic then (SpeechRecognizer) and two recorders would
 *    fight over it. [MinisApp.isAppForegroundFlow] is only the change
 *    signal; every decision re-reads [MinisApp.isAppForeground] because the
 *    StateFlow's seeded `true` is stale for a process started in the
 *    background (boot, sticky restart).
 *  - The notification is IMPORTANCE_LOW: a quiet, permanent "listening"
 *    entry, which the OS requires while the mic is held in the background.
 *  - START_STICKY so a system kill is retried; restarts re-check the
 *    permission and the foreground state before spinning audio up.
 */
class WakeWordService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var listenJob: Job? = null
    // isAppForeground and its flow are instance members of MinisApp.
    private val app by lazy { applicationContext as MinisApp }

    @Volatile private var spotter: SherpaKeywordSpotter? = null
    // Held for the whole listening span so the spotter keeps realtime pace
    // with the screen off (see micLoop).
    private val powerManagerWakeLock: android.os.PowerManager.WakeLock by lazy {
        (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "nanomuse:wakeword")
            .apply { setReferenceCounted(false) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startInForeground()
        AppLogger.info(TAG, "wake-word service created")
        scope.launch {
            handleState(app.isAppForeground())
            // Event stream only; the count is the truth (see class doc).
            app.isAppForegroundFlow.collect { handleState(app.isAppForeground()) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch { stopLoop() }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_DUMP -> {
                dumpRequested = true
                AppLogger.info(TAG, "mic dump requested")
            }
            else -> handleState(app.isAppForeground())
        }
        return START_STICKY
    }

    private fun handleState(foreground: Boolean) {
        if (foreground) {
            scope.launch { stopLoop() }
        } else {
            startLoopIfNeeded()
        }
    }

    private fun startLoopIfNeeded() {
        if (listenJob != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            AppLogger.warning(TAG, "RECORD_AUDIO missing; not listening")
            return
        }
        listenJob = scope.launch {
            try {
                micLoop()
            } finally {
                // Clear on every exit (foreground flip, wake hit, cancel) so
                // startLoopIfNeeded actually starts again next time.
                listenJob = null
            }
        }
    }

    private suspend fun stopLoop() {
        listenJob?.cancelAndJoin()
        listenJob = null
    }

    /**
     * Own the mic while the app is backgrounded. Exits when the app comes
     * back (loop condition), the job is cancelled, or the wake word fires
     * (the chat screen is about to claim the mic). If the recorder cannot be
     * opened (mic held by a call or the vendor assistant) it retries with
     * backoff instead of dying — the toggle stays on.
     */
    private suspend fun micLoop() {
        val kws = ensureSpotter() ?: return
        // Hold a partial wake lock for the whole listening span: with the
        // screen off the CPU otherwise throttles to a fraction of realtime
        // (measured 68% → 17%) and the spotter drops audio and misses the
        // phrase. Same pattern as AgentForegroundService's T50 lock.
        powerManagerWakeLock.acquire()
        try {
            val buffer = ShortArray(CHUNK)
            while (coroutineContext.isActive && !app.isAppForeground()) {
                val recorder = openRecorder()
                if (recorder == null) {
                    AppLogger.warning(TAG, "AudioRecord unavailable; retrying in ${RETRY_MS / 1000} s")
                    delay(RETRY_MS)
                    continue
                }
                kws.restart()
                recorder.startRecording()
                AppLogger.info(TAG, "listening for ${WakeWordSettings.DEFAULT_PHRASE}")
                var hit = false
                var dumped = false
                try {
                    while (coroutineContext.isActive && !app.isAppForeground()) {
                        val read = recorder.read(buffer, 0, buffer.size)
                        if (read <= 0) continue
                        if (dumpRequested) {
                            dumpRequested = false
                            dumped = writeDump(recorder, buffer, read)
                        }
                        if (kws.accept(buffer, read).isNotEmpty()) {
                            AppLogger.info(TAG, "wake word heard")
                            hit = true
                            break
                        }
                    }
                } finally {
                    try {
                        recorder.stop()
                    } catch (_: IllegalStateException) {
                        // never started, nothing to stop
                    }
                    recorder.release()
                }
                if (hit) onWakeWord()
            }
        } finally {
            powerManagerWakeLock.release()
        }
    }

    private fun ensureSpotter(): SherpaKeywordSpotter? {
        spotter?.let { return it }
        return try {
            SherpaKeywordSpotter(assets).also { spotter = it }
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "keyword spotter init failed: ${e.message}")
            null
        }
    }

    private fun onWakeWord() {
        val intent = Intent(Intent.ACTION_VIEW, NmAssistantSession.VOICE_CHAT_URI)
            .setClass(this, MainActivity::class.java)
            // nm_wake: MainActivity shows above the keyguard and turns the
            // screen on for this launch (see its onNewIntent/onCreate).
            .putExtra("nm_wake", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    /**
     * Capture a few seconds of raw mic audio (starting with the current
     * chunk) to filesDir/wake-dump.pcm for offline analysis of what the
     * spotter is actually being fed.
     */
    private fun writeDump(recorder: AudioRecord, first: ShortArray, firstRead: Int): Boolean {
        return try {
            val total = DUMP_SECONDS * SherpaKeywordSpotter.SAMPLE_RATE
            val collected = ArrayList<ShortArray>()
            var have = 0
            val rest = ShortArray(DUMP_CHUNK)
            while (have < total) {
                val target = when {
                    collected.isEmpty() -> first.copyOf(firstRead)
                    else -> rest
                }
                if (collected.isNotEmpty()) {
                    val n = recorder.read(target, 0, target.size)
                    if (n <= 0) break
                    collected.add(target.copyOf(n))
                    have += n
                } else {
                    collected.add(target)
                    have += target.size
                }
            }
            openFileOutput("wake-dump.pcm", MODE_PRIVATE).use { out ->
                val bytes = ByteArray(2)
                for (chunk in collected) {
                    for (s in chunk) {
                        bytes[0] = (s.toInt() and 0xFF).toByte()
                        bytes[1] = ((s.toInt() shr 8) and 0xFF).toByte()
                        out.write(bytes)
                    }
                }
            }
            AppLogger.info(TAG, "mic dump written: $have samples")
            true
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "mic dump failed: ${e.message}")
            false
        }
    }

    private fun openRecorder(): AudioRecord? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) return null
        val minBuf = AudioRecord.getMinBufferSize(
            SherpaKeywordSpotter.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return null
        return try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SherpaKeywordSpotter.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, CHUNK * 4),
            )
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "AudioRecord init failed: ${e.message}")
            null
        }
    }

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(
                getString(R.string.nm_wake_word_notification, WakeWordSettings.DEFAULT_PHRASE),
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(CHANNEL_ID) == null
        ) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.nm_wake_word_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) },
            )
        }
    }

    override fun onDestroy() {
        scope.cancel()
        spotter?.release()
        spotter = null
        AppLogger.info(TAG, "wake-word service destroyed")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WakeWordService"
        private const val CHANNEL_ID = "nanomuse_wake_word"
        private const val NOTIFICATION_ID = 4242
        private const val CHUNK = 640 // 40 ms of 16 kHz audio per read
        private const val RETRY_MS = 5_000L
        private const val DUMP_SECONDS = 6
        private const val DUMP_CHUNK = 1600 // 100 ms per read while dumping
        const val ACTION_START = "io.github.nanomuse.wakeword.START"
        const val ACTION_STOP = "io.github.nanomuse.wakeword.STOP"
        const val ACTION_DUMP = "io.github.nanomuse.wakeword.DUMP"

        // Diagnostics: set from anywhere (WakeWordDumpReceiver, adb-driven);
        // the listening loop then captures the next few seconds of raw mic
        // audio to a file for offline inspection.
        @Volatile internal var dumpRequested: Boolean = false
            private set

        /** Diagnostics entry used by WakeWordDumpReceiver (adb-triggered). */
        fun requestDumpNow() {
            dumpRequested = true
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WakeWordService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WakeWordService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
