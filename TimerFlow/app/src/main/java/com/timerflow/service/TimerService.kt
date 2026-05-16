package com.timerflow.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.*
import androidx.core.app.NotificationCompat
import com.timerflow.MainActivity
import com.timerflow.data.model.TimerStep
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class TimerService : Service() {

    inner class TimerBinder : Binder() {
        fun getService(): TimerService = this@TimerService
    }

    data class TimerState(
        val steps: List<TimerStep> = emptyList(),
        val currentIndex: Int = 0,
        val remainingSeconds: Int = 0,
        val isRunning: Boolean = false,
        val isPaused: Boolean = false,
        val isLoop: Boolean = false,
        val isFinished: Boolean = false
    ) {
        val currentStep: TimerStep? get() = steps.getOrNull(currentIndex)
        val progress: Float get() {
            val total = currentStep?.durationSeconds?.takeIf { it > 0 } ?: return 0f
            return 1f - remainingSeconds.toFloat() / total
        }
    }

    private val _state = MutableStateFlow(TimerState())
    val state: StateFlow<TimerState> = _state

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var tickJob: Job? = null
    private val binder = TimerBinder()

    companion object {
        const val CHANNEL_ID = "timerflow_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_PAUSE_RESUME = "com.timerflow.PAUSE_RESUME"
        const val ACTION_STOP = "com.timerflow.STOP"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_RESUME -> togglePause()
            ACTION_STOP -> stop()
        }
        return START_STICKY
    }

    fun start(steps: List<TimerStep>, isLoop: Boolean) {
        val sorted = steps.sortedBy { it.position }
        if (sorted.isEmpty()) return
        _state.value = TimerState(
            steps = sorted,
            currentIndex = 0,
            remainingSeconds = sorted[0].durationSeconds,
            isRunning = true,
            isPaused = false,
            isLoop = isLoop
        )
        startForeground(NOTIFICATION_ID, buildNotification())
        startTicking()
    }

    fun togglePause() {
        val s = _state.value
        if (!s.isRunning) return
        if (s.isPaused) {
            _state.value = s.copy(isPaused = false)
            startTicking()
        } else {
            tickJob?.cancel()
            _state.value = s.copy(isPaused = true)
            updateNotification()
        }
    }

    fun stop() {
        tickJob?.cancel()
        _state.value = TimerState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startTicking() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (true) {
                delay(1000L)
                val s = _state.value
                if (!s.isRunning || s.isPaused) break
                if (s.remainingSeconds > 1) {
                    _state.value = s.copy(remainingSeconds = s.remainingSeconds - 1)
                    updateNotification()
                } else {
                    onStepFinished()
                    break
                }
            }
        }
    }

    private fun onStepFinished() {
        buzz()
        val s = _state.value
        val nextIndex = s.currentIndex + 1
        if (nextIndex < s.steps.size) {
            val next = s.steps[nextIndex]
            _state.value = s.copy(currentIndex = nextIndex, remainingSeconds = next.durationSeconds)
            updateNotification()
            startTicking()
        } else if (s.isLoop) {
            val first = s.steps[0]
            _state.value = s.copy(currentIndex = 0, remainingSeconds = first.durationSeconds)
            updateNotification()
            startTicking()
        } else {
            _state.value = s.copy(isRunning = false, isFinished = true, remainingSeconds = 0)
            updateNotification()
        }
    }

    private fun buzz() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 300), -1))

        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(applicationContext, uri)?.play()
        } catch (_: Exception) {}
    }

    private fun buildNotification(): Notification {
        val s = _state.value
        val step = s.currentStep
        val title = if (s.isFinished) "TimerFlow — Fertig!" else step?.name ?: "TimerFlow"
        val text = if (s.isFinished) "Alle Timer abgeschlossen" else formatSeconds(s.remainingSeconds)

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TimerService::class.java).apply { action = ACTION_PAUSE_RESUME },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, TimerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(!s.isFinished)
            .setSilent(true)
            .addAction(
                if (s.isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (s.isPaused) "Weiter" else "Pause",
                pauseIntent
            )
            .addAction(android.R.drawable.ic_media_previous, "Stop", stopIntent)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "TimerFlow",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Aktiver Timer"
            setSound(null, AudioAttributes.Builder().build())
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    fun formatSeconds(s: Int): String {
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }
}
