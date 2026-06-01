package com.frank.videosubtitle.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.frank.videosubtitle.MainActivity
import com.frank.videosubtitle.R
import com.frank.videosubtitle.data.orchestrator.TaskOrchestrator
import com.frank.videosubtitle.data.repository.TaskRepository
import com.frank.videosubtitle.domain.model.TaskStage
import com.frank.videosubtitle.domain.model.TaskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Foreground service that keeps the process alive while any task is in an
 * in-progress stage. It does not own coroutines that drive the pipeline —
 * [TaskOrchestrator] still launches those on the application scope. The service
 * just observes task state, posts a single rolling notification, and stops
 * itself when no task is active anymore.
 */
class VideoProcessingService : Service() {

    private val taskRepository: TaskRepository by inject()
    private val orchestrator: TaskOrchestrator by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID)
                if (!taskId.isNullOrEmpty()) orchestrator.cancel(taskId)
                // Keep the service alive; the observer will stopSelf once
                // there are no in-progress tasks left.
            }
            else -> Unit
        }

        if (!started) {
            started = true
            startForegroundCompat(buildNotification(activeTitle = null, percent = null))
            observerJob = scope.launch {
                taskRepository.observeAll()
                    .map { tasks -> tasks.firstOrNull { it.stage.isInProgress() } }
                    .distinctUntilChanged()
                    .collect { active ->
                        if (active == null) {
                            stopForegroundCompat()
                            stopSelf()
                        } else {
                            notify(buildNotification(active))
                        }
                    }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observerJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // mediaProcessing was added in API 34 specifically for transcoding-style work.
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService<NotificationManager>() ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_pipeline),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_pipeline_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun notify(notification: Notification) {
        val nm = getSystemService<NotificationManager>() ?: return
        nm.notify(NOTIF_ID, notification)
    }

    private fun buildNotification(active: TaskState): Notification =
        buildNotification(
            activeTitle = active.video.displayName,
            percent = active.stage.progressPercent(),
            taskId = active.id,
            stageText = stageText(active.stage),
        )

    private fun buildNotification(
        activeTitle: String?,
        percent: Int?,
        taskId: String? = null,
        stageText: String? = null,
    ): Notification {
        val contentPi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            pendingIntentFlags(),
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(activeTitle ?: getString(R.string.notif_pipeline_title))
            .setContentText(stageText ?: getString(R.string.notif_pipeline_idle))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPi)

        if (percent != null) builder.setProgress(100, percent.coerceIn(0, 100), false)

        if (!taskId.isNullOrEmpty()) {
            val cancelIntent = Intent(this, VideoProcessingService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_TASK_ID, taskId)
            }
            val cancelPi = PendingIntent.getService(this, 1, cancelIntent, pendingIntentFlags())
            builder.addAction(0, getString(R.string.notif_action_cancel), cancelPi)
        }

        return builder.build()
    }

    private fun pendingIntentFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    private fun stageText(stage: TaskStage): String = when (stage) {
        is TaskStage.Extracting -> getString(R.string.task_stage_extracting, stage.percent)
        is TaskStage.Transcribing -> getString(R.string.task_stage_transcribing, stage.percent)
        is TaskStage.Translating -> getString(R.string.task_stage_translating, stage.percent)
        is TaskStage.Burning -> getString(R.string.task_stage_burning, stage.percent)
        TaskStage.Editing -> getString(R.string.task_stage_editing)
        is TaskStage.Done -> getString(R.string.task_stage_done)
        is TaskStage.Failed -> getString(R.string.task_stage_failed, stage.reason)
        TaskStage.Idle -> getString(R.string.task_stage_idle)
    }

    private fun TaskStage.progressPercent(): Int? = when (this) {
        is TaskStage.Extracting -> percent
        is TaskStage.Transcribing -> percent
        is TaskStage.Translating -> percent
        is TaskStage.Burning -> percent
        else -> null
    }

    companion object {
        const val CHANNEL_ID = "pipeline"
        const val NOTIF_ID = 1001

        const val ACTION_CANCEL = "com.frank.videosubtitle.action.CANCEL"
        const val EXTRA_TASK_ID = "taskId"

        fun start(context: Context) {
            val intent = Intent(context, VideoProcessingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

private fun TaskStage.isInProgress(): Boolean = when (this) {
    is TaskStage.Extracting,
    is TaskStage.Transcribing,
    is TaskStage.Translating,
    is TaskStage.Burning,
    -> true
    else -> false
}
