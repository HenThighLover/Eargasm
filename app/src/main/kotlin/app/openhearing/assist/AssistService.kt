package app.openhearing.assist

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.openhearing.MainActivity
import app.openhearing.R
import app.openhearing.core.audio.dsp.ExposureTracker
import app.openhearing.data.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import javax.inject.Inject

/**
 * Foreground service that runs assist mode (continuous microphone capture +
 * processing). A foreground service with the `microphone` type is the required
 * Android pattern for ongoing mic use; the persistent notification keeps the user
 * aware the mic is live and offers a one-tap **Stop**.
 *
 * The actual engine lives in [AssistController]; this service owns the
 * foreground lifecycle and starts/stops it.
 */
@AndroidEntryPoint
class AssistService : Service() {
    @Inject
    lateinit var controller: AssistController

    @Inject
    lateinit var settingsRepository: SettingsRepository

    private var wakeLock: PowerManager.WakeLock? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var samplingJob: Job? = null

    // Headphones unplugged / Bluetooth disconnected: stop immediately rather than
    // fall back to the phone speaker, where mic->speaker feedback (howl) is likely.
    private val becomingNoisyReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) stopSelf()
            }
        }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this,
            becomingNoisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
                if (intent?.action == ACTION_STOP) {
            shutDown()
            return START_NOT_STICKY
        }
        val remote = intent?.getBooleanExtra(EXTRA_REMOTE_MIC, false) == true
        startForegroundNotification(remote)
        // Remote-mic sessions are expected to run with the screen off (phone
        // across the room); the wake lock keeps OEM battery managers from
        // suspending the process mid-stream. Released in onDestroy.
        if (remote) acquireWakeLock()
        controller.startEngine()
        startExposureSampling()
        return START_STICKY
    }

        /**
     * Remove the notification immediately, then stop the audio off the main
     * thread, so a slow device teardown can never keep the service (or UI) stuck.
     */
    private fun shutDown() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        Thread(
            {
                try {
                    controller.stopEngine()
                } finally {
                    stopSelf()
                }
            },
            "OpenHearing-Stop",
        ).start()
    }
    override fun onDestroy() {
        unregisterReceiver(becomingNoisyReceiver)
        samplingJob?.cancel()
        // Persist whatever the sampling loop hadn't flushed yet, then shut down.
        val leftover = controller.exposure.value.unflushedUnits
                if (leftover > 0) {
            val day = todayEpochDay()
            CoroutineScope(Dispatchers.IO).launch { settingsRepository.addExposureUnits(leftover, day) }
        }
        controller.stopEngine()
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    /**
     * Once a second: drain the post-limiter level tap, convert to relative
     * exposure units, publish a snapshot for the UI, and flush to DataStore
     * every [FLUSH_EVERY_TICKS] (a crash loses at most that much).
     */
    private fun startExposureSampling() {
        if (samplingJob?.isActive == true) return
        samplingJob =
            serviceScope.launch {
                var snapshot = ExposureSnapshot()
                var ticks = 0L
                while (isActive) {
                    delay(SAMPLE_PERIOD_MS)
                    val window = controller.drainOutputLevel()
                    val units = ExposureTracker.unitsFor(window, SAMPLE_PERIOD_MS / 1000.0)
                    snapshot =
                        ExposureSnapshot(
                            sessionSeconds = snapshot.sessionSeconds + 1,
                            sessionUnits = snapshot.sessionUnits + units,
                            unflushedUnits = snapshot.unflushedUnits + units,
                            lastRmsDbfs = window.rmsDbfs,
                        )
                    ticks++
                    if (ticks % FLUSH_EVERY_TICKS == 0L && snapshot.unflushedUnits > 0) {
                        settingsRepository.addExposureUnits(snapshot.unflushedUnits, todayEpochDay())
                        snapshot = snapshot.copy(unflushedUnits = 0.0)
                    }
                    controller.publishExposure(snapshot)
                }
            }
    }

    private fun todayEpochDay(): Long = LocalDate.now().toEpochDay()

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock =
            powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
        wakeLock = null
    }

    private fun startForegroundNotification(remote: Boolean) {
        createChannel()
        val openIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val stopIntent =
            PendingIntent.getService(
                this,
                1,
                Intent(this, AssistService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val title = if (remote) R.string.remote_mic_active_title else R.string.assist_active_title
        val text = if (remote) R.string.remote_mic_active_text else R.string.assist_active_text
        val notification =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(title))
                .setContentText(getString(text))
                .setSmallIcon(R.drawable.ic_stat_assist)
                .setOngoing(true)
                .setContentIntent(openIntent)
                .addAction(0, getString(R.string.assist_stop), stopIntent)
                .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.assist_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "assist"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "app.openhearing.assist.STOP"
        private const val EXTRA_REMOTE_MIC = "app.openhearing.assist.REMOTE_MIC"
        private const val WAKE_LOCK_TAG = "openhearing:remote_mic"

        // Backstop, not a lifecycle: refreshed only by restarting the session.
        private const val WAKE_LOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L

        private const val SAMPLE_PERIOD_MS = 1_000L
        private const val FLUSH_EVERY_TICKS = 30L

        fun start(context: Context, remoteMic: Boolean = false) {
            val intent =
                Intent(context, AssistService::class.java)
                    .putExtra(EXTRA_REMOTE_MIC, remoteMic)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AssistService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
