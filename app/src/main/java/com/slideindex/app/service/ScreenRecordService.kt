package com.slideindex.app.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.slideindex.app.R
import com.slideindex.app.screencapture.RecordingDock
import com.slideindex.app.screendraw.ScreenDrawOverlay
import com.slideindex.app.util.ForegroundNotificationChannels
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that records the screen using the official MediaProjection + MediaRecorder APIs.
 */
class ScreenRecordService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var outputUri: Uri? = null
    private var outputPfd: android.os.ParcelFileDescriptor? = null
    private var recordingDock: RecordingDock? = null
    /** 正在执行停止流程：防 [MediaProjection.Callback.onStop] 回调重入。 */
    private var stopInProgress = false
    /** 最近一次保存成功的文件：回调重跑时 `outputUri` 已被清空，用它兜底。 */
    private var lastSavedUri: Uri? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = readResultData(intent)
                if (resultCode == Activity.RESULT_OK && data != null) {
                    // 渠道先落地再 startForeground：渠道缺失会被系统判为非法前台服务通知并杀进程。
                    // 但「用户关掉了通知渠道」不是放弃的理由（见 Result.isUsable）：不调
                    // startForeground() 会被 AMS 以前台服务超时为由杀进程，录屏跟着一起断。
                    val channel = ensureChannel()
                    if (!channel.isUsable) {
                        Log.e(TAG, "录屏通知渠道不可用，放弃前台化")
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    if (channel.blocked) {
                        Log.w(TAG, "渠道存在但被用户关闭：通知不可见，仍照常前台化以免前台服务超时杀进程")
                    }
                    startForeground(NOTIFICATION_ID, buildNotification(recording = true))
                    startRecording(resultCode, data)
                } else {
                    stopSelf()
                }
            }
            ACTION_STOP -> stopRecording()
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRecordingInternal(notify = false)
        super.onDestroy()
    }

    private fun startRecording(resultCode: Int, data: Intent) {
        if (isRecording) return
        com.slideindex.app.util.MediaProjectionStore.save(this, resultCode, data)
        ScreenCaptureService.stop(this)
        val projectionManager = getSystemService(MediaProjectionManager::class.java) ?: return
        val projection = projectionManager.getMediaProjection(resultCode, data) ?: return
        mediaProjection = projection
        projection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    mainHandler.post { stopRecordingInternal(notify = true) }
                }
            },
            mainHandler
        )

        val wm = getSystemService(WindowManager::class.java) ?: return
        val bounds = wm.currentWindowMetrics.bounds
        val width = bounds.width().coerceAtLeast(2).let { if (it % 2 != 0) it - 1 else it }
        val height = bounds.height().coerceAtLeast(2).let { if (it % 2 != 0) it - 1 else it }
        val density = resources.displayMetrics.densityDpi

        val recorder = createMediaRecorder()
        val (uri, pfd) = createOutputTarget() ?: run {
            recorder.release()
            stopSelf()
            return
        }
        outputUri = uri
        outputPfd = pfd
        // 新一次录制：上一次的兜底 uri 作废（否则通知可能指向旧文件）。
        lastSavedUri = null
        recorder.setOutputFile(pfd.fileDescriptor)
        runCatching { recorder.prepare() }.onFailure { error ->
            Log.e(TAG, "MediaRecorder prepare failed", error)
            recorder.release()
            closeOutput()
            stopSelf()
            return
        }
        mediaRecorder = recorder

        virtualDisplay = projection.createVirtualDisplay(
            "SlideIndexScreenRecord",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            recorder.surface,
            null,
            null
        )
        runCatching { recorder.start() }.onFailure { error ->
            Log.e(TAG, "MediaRecorder start failed", error)
            stopRecordingInternal(notify = true)
            return
        }
        isRecording = true
        showDock()
    }

    private fun createMediaRecorder(): MediaRecorder {
        val wm = getSystemService(WindowManager::class.java) ?: throw IllegalStateException("no WindowManager")
        val bounds = wm.currentWindowMetrics.bounds
        val width = bounds.width().coerceAtLeast(2).let { if (it % 2 != 0) it - 1 else it }
        val height = bounds.height().coerceAtLeast(2).let { if (it % 2 != 0) it - 1 else it }
        return MediaRecorder(this).apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(width, height)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(6_000_000)
        }
    }

    @SuppressLint("Recycle") // Closed later via outputPfd in finalizeOutput()/releaseResources()
    private fun createOutputTarget(): Pair<Uri, android.os.ParcelFileDescriptor>? {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val displayName = "SlideIndex_$timestamp.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/SlideIndex")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        val pfd = contentResolver.openFileDescriptor(uri, "w") ?: return null
        return uri to pfd
    }

    private fun stopRecording() {
        // 前台通知的移除交给 stopRecordingInternal：必须先 stopForeground 再发"已保存"通知，
        // 否则同一个 id（NOTIFICATION_ID）的保存通知会被 stopForeground(REMOVE) 一起撤掉。
        stopRecordingInternal(notify = true)
        stopSelf()
    }

    private fun pauseRecording() {
        if (!isRecording || isPaused) return
        val recorder = mediaRecorder ?: return
        if (runCatching { recorder.pause() }.isFailure) {
            Log.w(TAG, "MediaRecorder.pause failed")
            return
        }
        isPaused = true
        recordingDock?.setPaused(true)
    }

    private fun resumeRecording() {
        if (!isRecording || !isPaused) return
        val recorder = mediaRecorder ?: return
        if (runCatching { recorder.resume() }.isFailure) {
            Log.w(TAG, "MediaRecorder.resume failed")
            return
        }
        isPaused = false
        recordingDock?.setPaused(false)
    }

    /**
     * 录制期间挂一块小 dock：暂停/继续、停止、进入全局绘制。
     *
     * 绘制展开时把 dock 临时收起（绘制自带工具条，两个悬浮条会打架），
     * 退出绘制后再放回来——靠 [ScreenDrawOverlay.onShowingChanged] 回调，不用轮询。
     */
    private fun showDock() {
        if (recordingDock != null) {
            recordingDock?.setVisible(true)
            return
        }
        val dock = RecordingDock(
            appContext = applicationContext,
            onTogglePause = { if (isPaused) resumeRecording() else pauseRecording() },
            onStop = { stopRecording() },
            onToggleDraw = {
                if (ScreenDrawOverlay.isShowing) {
                    ScreenDrawOverlay.dismiss()
                } else {
                    ScreenDrawOverlay.toggle(this)
                }
            },
        )
        recordingDock = dock
        dock.setPaused(isPaused)
        dock.setDrawActive(ScreenDrawOverlay.isShowing)
        dock.setVisible(!ScreenDrawOverlay.isShowing)
        dock.show()
        ScreenDrawOverlay.onShowingChanged = { showing ->
            recordingDock?.setDrawActive(showing)
            recordingDock?.setVisible(!showing)
        }
    }

    private fun dismissDock() {
        ScreenDrawOverlay.onShowingChanged = null
        recordingDock?.hide()
        recordingDock = null
    }

    /**
     * 停止录制。
     *
     * 必须**幂等 + 防重入**：`mediaProjection.stop()` 会触发 [MediaProjection.Callback.onStop]，
     * 而那个回调是 `mainHandler.post { stopRecordingInternal(notify = true) }` —— 也就是会再排一次
     * 本方法。第二次进来时资源已经释放、`outputUri` 已被 [finalizeOutput] 清空，再发一次通知会把
     * 刚发出去、带点击意图的"已保存"通知**覆盖成没有 contentIntent 的空壳**（点通知没反应）。
     */
    private fun stopRecordingInternal(notify: Boolean) {
        if (stopInProgress) return
        val alreadyStopped = !isRecording &&
            mediaRecorder == null &&
            virtualDisplay == null &&
            mediaProjection == null
        if (alreadyStopped) return
        stopInProgress = true
        try {
            stopRecordingResources(notify)
        } finally {
            stopInProgress = false
        }
    }

    /** 真正释放录制资源、结束写盘，并在 [notify] 时发"已保存"通知；只由 [stopRecordingInternal] 调用。 */
    private fun stopRecordingResources(notify: Boolean) {
        isRecording = false
        dismissDock()
        // 暂停态直接 stop() 在部分机型上会抛 IllegalStateException，先 resume 再停。
        val wasPaused = isPaused
        isPaused = false
        if (wasPaused) runCatching { mediaRecorder?.resume() }
        runCatching { mediaRecorder?.stop() }
        runCatching { mediaRecorder?.reset() }
        runCatching { mediaRecorder?.release() }
        mediaRecorder = null
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.stop()
        mediaProjection = null
        // finalizeOutput() 会把 outputUri 清空，通知里要用它，先存一份。
        val savedUri = outputUri ?: lastSavedUri
        if (savedUri != null) lastSavedUri = savedUri
        finalizeOutput()
        if (notify) {
            // 先摘掉前台通知，再发"已保存"——同一个 id，顺序反了会被 REMOVE 一起撤掉。
            stopForeground(STOP_FOREGROUND_REMOVE)
            runCatching {
                getSystemService(NotificationManager::class.java)
                    ?.notify(
                        NOTIFICATION_ID,
                        buildNotification(recording = false, savedUri = savedUri),
                    )
            }
        }
    }

    private fun finalizeOutput() {
        outputUri?.let { uri ->
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            runCatching { contentResolver.update(uri, values, null, null) }
        }
        runCatching { outputPfd?.close() }
        outputPfd = null
        outputUri = null
    }

    private fun closeOutput() {
        runCatching { outputPfd?.close() }
        outputPfd = null
        outputUri = null
    }

    private fun buildNotification(recording: Boolean, savedUri: Uri? = null): Notification {
        ensureChannel()
        val title = if (recording) {
            getString(R.string.screen_record_notification_recording)
        } else {
            getString(R.string.screen_record_notification_saved)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(title)
            .setOngoing(recording)
            .setOnlyAlertOnce(true)
        if (!recording) {
            // 点"已保存"通知 → 系统「打开方式」选择器：有默认播放器也弹，让用户自己选。
            savedUri?.let { builder.setContentIntent(openVideoPendingIntent(it)) }
            builder.setAutoCancel(true)
        }
        return builder.build()
    }

    /** 打开视频的系统选择器（ACTION_VIEW + chooser），不做默认播放器判断。 */
    private fun openVideoPendingIntent(uri: Uri): PendingIntent {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(view, getString(R.string.screen_record_open_with)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            chooser,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun ensureChannel(): ForegroundNotificationChannels.Result =
        ForegroundNotificationChannels.ensureUsable(
            context = this,
            id = CHANNEL_ID,
            name = getString(R.string.screen_record_channel_name),
            importance = NotificationManager.IMPORTANCE_LOW,
            tag = TAG,
        )

    private fun readResultData(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
    }

    companion object {
        private const val TAG = "ScreenRecordService"
        private const val CHANNEL_ID = "screen_record"
        private const val NOTIFICATION_ID = 4207
        private const val ACTION_START = "com.slideindex.app.action.SCREEN_RECORD_START"
        private const val ACTION_STOP = "com.slideindex.app.action.SCREEN_RECORD_STOP"
        private const val ACTION_PAUSE = "com.slideindex.app.action.SCREEN_RECORD_PAUSE"
        private const val ACTION_RESUME = "com.slideindex.app.action.SCREEN_RECORD_RESUME"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        @Volatile
        var isRecording: Boolean = false
            private set

        @Volatile
        var isPaused: Boolean = false
            private set

        fun start(context: Context, resultCode: Int, data: Intent) {
            val app = context.applicationContext
            val intent = Intent(app, ScreenRecordService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            app.startForegroundService(intent)
        }

        fun pause(context: Context) {
            val app = context.applicationContext
            app.startService(Intent(app, ScreenRecordService::class.java).apply { action = ACTION_PAUSE })
        }

        fun resume(context: Context) {
            val app = context.applicationContext
            app.startService(Intent(app, ScreenRecordService::class.java).apply { action = ACTION_RESUME })
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, ScreenRecordService::class.java).apply {
                action = ACTION_STOP
            }
            app.startService(intent)
        }
    }
}
