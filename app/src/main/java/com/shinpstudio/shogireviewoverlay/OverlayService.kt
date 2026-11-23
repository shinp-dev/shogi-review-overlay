package com.shinpstudio.shogireviewoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

class OverlayService : Service() {
    companion object {
        const val ACTION_UPDATE = "overlay.UPDATE"
        const val ACTION_REFRESH = "overlay.REFRESH"
        const val ACTION_CALIBRATE = "overlay.CALIBRATE"
        const val ACTION_STOP = "overlay.STOP"
        const val ACTION_HIDE = "overlay.HIDE"
        const val ACTION_RESUME = "overlay.RESUME"
        private const val NOTIFICATION_ID = 310
        private const val CHANNEL_ID = "shogi_review_overlay"
    }

    private lateinit var windows: WindowManager
    private var overlayView: View? = null
    private var values: List<OverlayCandidate> = emptyList()
    private var calibrating = false

    override fun onCreate() {
        super.onCreate()
        windows = getSystemService(WINDOW_SERVICE) as WindowManager
        val channel = NotificationChannel(
            CHANNEL_ID, "将棋レビュー・オーバーレイ", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val stopIntent = Intent(this, OverlayService::class.java).apply { action = ACTION_STOP }
        val stopAction = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("将棋レビュー・オーバーレイ")
            .setContentText("対局後の振り返りのみ使用してください")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopAction)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        when (intent?.action) {
            ACTION_HIDE -> overlayView?.visibility = View.INVISIBLE
            ACTION_RESUME -> overlayView?.visibility = View.VISIBLE
            ACTION_UPDATE -> {
                val usi = intent.getStringArrayListExtra("moves").orEmpty()
                val labels = intent.getStringArrayListExtra("labels").orEmpty()
                val scores = intent.getIntArrayExtra("scores") ?: intArrayOf()
                values = usi.mapIndexedNotNull { i, raw ->
                    try {
                        OverlayCandidate(
                            MoveParser.fromUsi(raw),
                            labels.getOrElse(i) { raw },
                            scores.getOrElse(i) { 0 }
                        )
                    } catch (_: Exception) {
                        null
                    }
                }
                if (!calibrating) showPassive()
            }
            ACTION_CALIBRATE -> showCalibration()
            else -> if (!calibrating) showPassive()
        }
        return START_NOT_STICKY
    }

    private fun passiveParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Android 12+ restricts touches through untrusted overlays above a maximum opacity.
            alpha = 0.74f
            if (Build.VERSION.SDK_INT >= 28)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

    private fun calibrationParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

    private fun clearView() {
        overlayView?.let {
            try { windows.removeView(it) } catch (_: Exception) { }
        }
        overlayView = null
    }

    private fun showPassive() {
        calibrating = false
        clearView()
        val view = CandidateOverlayView(this).apply { setCandidates(values) }
        windows.addView(view, passiveParams())
        overlayView = view
    }

    private fun showCalibration() {
        calibrating = true
        clearView()
        val view = BoardCalibrationView(this) {
            calibrating = false
            showPassive()
        }
        windows.addView(view, calibrationParams())
        overlayView = view
    }

    override fun onDestroy() {
        clearView()
        super.onDestroy()
    }
}

/** Conversion of engine-first-move USI strings to shared arrow-rendering moves. */
object MoveParser {
    fun fromUsi(usi: String): ShogiMove {
        val input = usi.trim()
        if (input.length == 4 && input[1] == '*') {
            return ShogiMove(
                null,
                Squares.fromUsi(input.substring(2, 4)),
                drop = PieceType.fromSfen(input[0])
            )
        }
        require(input.length == 4 || input.length == 5) { "USI指し手が不正です" }
        require(input.length == 4 || input[4] == '+') { "成り指定が不正です" }
        return ShogiMove(
            Squares.fromUsi(input.substring(0, 2)),
            Squares.fromUsi(input.substring(2, 4)),
            promote = input.length == 5
        )
    }
}
