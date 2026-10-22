package com.realingo.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.realingo.app.MainActivity
import com.realingo.app.R
import com.realingo.app.core.Engine
import com.realingo.app.core.SettingsStore
import com.realingo.app.core.SourceSpec
import com.realingo.app.core.t
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Keeps a session alive with the screen off — the Android counterpart of ReaLingo's tray
 * app, including a way to stop and to toggle the floating subtitles from the shade.
 */
class TranslationService : Service() {

    companion object {
        const val ACTION_START = "com.realingo.app.action.START"
        const val ACTION_STOP = "com.realingo.app.action.STOP"
        const val ACTION_TOGGLE_SUB = "com.realingo.app.action.TOGGLE_SUB"
        const val EXTRA_KIND = "kind"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_URI = "uri"
        const val EXTRA_SPEAK = "speak"

        private const val CHANNEL_ID = "session"
        private const val NOTIF_ID = 42

        fun startIntent(context: Context, spec: SourceSpec, speak: Boolean): Intent {
            val i = Intent(context, TranslationService::class.java).setAction(ACTION_START)
            when (spec) {
                is SourceSpec.Mic -> i.putExtra(EXTRA_KIND, "mic")
                is SourceSpec.File -> {
                    i.putExtra(EXTRA_KIND, "file")
                    i.putExtra(EXTRA_URI, spec.uri.toString())
                }
                is SourceSpec.Playback -> {
                    i.putExtra(EXTRA_KIND, "system")
                    i.putExtra(EXTRA_RESULT_CODE, spec.resultCode)
                    i.putExtra(EXTRA_RESULT_DATA, spec.data)
                }
            }
            i.putExtra(EXTRA_SPEAK, speak)
            return i
        }

        fun stopIntent(context: Context): Intent =
            Intent(context, TranslationService::class.java).setAction(ACTION_STOP)

        fun toggleSubIntent(context: Context): Intent =
            Intent(context, TranslationService::class.java).setAction(ACTION_TOGGLE_SUB)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null
    private var overlay: OverlayController? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foreground = false
    private var audioSource = "mic"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> {
                Engine.stop()
                SettingsStore.setSub { it.copy(show = false) }
                syncOverlay()
                maybeFinish()
            }
            ACTION_TOGGLE_SUB -> {
                SettingsStore.setSub { it.copy(show = !it.show) }
                syncOverlay()
                maybeFinish()
            }
            else -> {
                // Restarted by the system with nothing to resume.
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        val kind = intent.getStringExtra(EXTRA_KIND) ?: "mic"
        audioSource = kind
        ensureChannel()
        startForegroundCompat(kind)
        acquireWake()

        val spec: SourceSpec = when (kind) {
            "system" -> {
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                if (data == null) {
                    stopSelf()
                    return
                }
                SourceSpec.Playback(code, data)
            }

            "file" -> {
                val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
                if (uri == null) {
                    stopSelf()
                    return
                }
                SourceSpec.File(uri)
            }

            else -> SourceSpec.Mic()
        }

        Engine.start(this, spec, intent.getBooleanExtra(EXTRA_SPEAK, false))
        syncOverlay()
        watch()
    }

    private fun watch() {
        if (watcher?.isActive == true) return
        watcher = scope.launch {
            Engine.status.collect {
                updateNotification()
                if (!Engine.isRunning) maybeFinish()
            }
        }
    }

    /** Leaves the last sentence time to arrive before the service goes away. */
    private fun maybeFinish() {
        if (Engine.isRunning) return
        if (overlay?.isShowing() == true) {
            // Subtitles are still on screen: keep the service (and the window) alive.
            updateNotification()
            return
        }
        releaseWake()
        if (foreground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            foreground = false
        }
        stopSelf()
    }

    fun syncOverlay() {
        val show = SettingsStore.value.sub.show
        if (show) {
            if (overlay == null) overlay = OverlayController(this)
            overlay?.show()
        } else {
            overlay?.hide()
        }
        updateNotification()
    }

    // ------------------------------------------------------------------ notification

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
        )
    }

    private fun statusLine(): String = when {
        SettingsStore.value.key().trim().isEmpty() -> t("请先填入 API Key", "Add an API key first")
        Engine.status.value == Engine.Status.ERROR -> t("出错了", "Error")
        Engine.status.value == Engine.Status.CONNECTING -> t("连接中…", "Connecting…")
        Engine.status.value == Engine.Status.STOPPING -> t("正在收尾…", "Finishing…")
        Engine.status.value == Engine.Status.CONNECTED ->
            if (Engine.speaking.value) t("正在聆听", "Listening") else t("翻译中", "Translating")
        else -> t("空闲", "Idle")
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val sub = PendingIntent.getService(
            this, 2, toggleSubIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val live = Engine.live.value
        val body = when {
            live.target.isNotEmpty() -> live.target
            live.source.isNotEmpty() -> live.source
            else -> t("点按返回应用", "Tap to return to the app")
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("ReaLingo · ${statusLine()}")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, t("停止", "Stop"), stop)
            .addAction(
                0,
                if (SettingsStore.value.sub.show) t("隐藏字幕", "Hide subtitles") else t("显示字幕", "Show subtitles"),
                sub
            )
            .build()
    }

    private fun updateNotification() {
        if (!foreground) return
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildNotification())
        }
    }

    private fun startForegroundCompat(kind: String) {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // File mode never touches the microphone, so it must not claim that type.
            val type = when (kind) {
                "system" -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                "file" -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIF_ID, n, type)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && kind == "system") {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
        foreground = true
    }

    // ------------------------------------------------------------------ power

    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReaLingo:session").apply {
            setReferenceCounted(false)
            acquire(3 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWake() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        watcher?.cancel()
        overlay?.hide()
        overlay = null
        releaseWake()
        if (Engine.isRunning) Engine.stop()
        super.onDestroy()
    }
}
