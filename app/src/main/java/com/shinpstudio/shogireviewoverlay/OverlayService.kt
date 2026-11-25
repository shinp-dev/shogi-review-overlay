package com.shinpstudio.shogireviewoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

class OverlayService : Service() {
    companion object {
        const val ACTION_UPDATE = "overlay.UPDATE"
        const val ACTION_REFRESH = "overlay.REFRESH"
        const val ACTION_CALIBRATE = "overlay.CALIBRATE"
        const val ACTION_STOP = "overlay.STOP"
        const val ACTION_START_REVIEW = "overlay.START_REVIEW"
        const val ACTION_PREPARE_CAPTURE = "overlay.PREPARE_CAPTURE"
        const val ACTION_FINISH_CAPTURE = "overlay.FINISH_CAPTURE"
        const val ACTION_CLEAR = "overlay.CLEAR"
        const val ACTION_TEST = "overlay.TEST"
        private const val NOTIFICATION_ID = 310
        const val ACTION_STATUS = "overlay.STATUS"
        private const val CHANNEL_ID = "shogi_review_overlay"

        @Volatile private var running: OverlayService? = null
        @Volatile private var epoch: Long = 0
        fun sessionEpoch(): Long = epoch
        fun isCalibrating(): Boolean = running?.calibrating == true

        // Capture callbacks may only update an existing overlay. They must
        // never start a stopped service or recreate a window after Exit.
        fun deliver(intent: Intent, expectedEpoch: Long = epoch) {
            val owner = running ?: return
            Handler(Looper.getMainLooper()).post {
                if (running === owner && !owner.closing && epoch == expectedEpoch) {
                    owner.handleAction(intent)
                }
            }
        }

        fun stopAll(context: Context) {
            epoch++
            running?.closeSession()
            context.stopService(Intent(context, ReviewCaptureService::class.java))
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }

    private lateinit var windows: WindowManager
    private var overlayView: View? = null
    private var values: List<OverlayCandidate> = emptyList()
    private var uncertainSquares = intArrayOf()
    private var uncertainDx = 0
    private var uncertainDy = 0
    @Volatile private var calibrating = false
    private var controls: LinearLayout? = null
    private var controlStatus: TextView? = null
    private var adjustButton: Button? = null
    private var statusMessage = "盤面を待っています（9×9の外周に枠を合わせてください）"
    private var closing = false
    private var capturingFrame = false
    private var diagnostic = false
    private var hasWindowError = false

    private fun publishStatus(message: String) {
        OverlaySettings.preferences(this).edit()
            .putString("last_overlay_status", message)
            .apply()
    }

    override fun onCreate() {
        super.onCreate()
        windows = getSystemService(WINDOW_SERVICE) as WindowManager
        running = this
        val channel = NotificationChannel(
            CHANNEL_ID, "将棋レビュー・オーバーレイ", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAll(this)
            return START_NOT_STICKY
        }
        if (closing) return START_NOT_STICKY
        if (!Settings.canDrawOverlays(this)) {
            publishStatus("表示権限なし：設定で「他のアプリの上に表示」を許可してください")
            hasWindowError = true
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
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            hasWindowError = true
            Log.e("ShogiOverlay", "Failed to start overlay foreground service", e)
            publishStatus("オーバーレイの常駐開始に失敗: " + e.javaClass.simpleName +
                " " + (e.message ?: ""))
            stopSelf()
            return START_NOT_STICKY
        }

        handleAction(intent)
        // An explicit start is needed after service destruction or Exit.
        return START_NOT_STICKY
    }

    private fun clearUncertainSquares() {
        uncertainSquares = intArrayOf()
        uncertainDx = 0
        uncertainDy = 0
    }

    private fun handleAction(intent: Intent?) {
        if (closing) return
        when (intent?.action) {
            ACTION_STATUS -> setStatus(intent.getStringExtra("message") ?: "")
            ACTION_PREPARE_CAPTURE -> {
                capturingFrame = true
                (overlayView as? CandidateOverlayView)?.setCapturingFrame(true)
                // Avoid reading the control panel as holding pieces. The
                // green board outline itself stays visible.
                controls?.visibility = View.INVISIBLE
            }
            ACTION_FINISH_CAPTURE -> {
                capturingFrame = false
                // Also recreates the frame if the service lost its window.
                if (!calibrating) showPassive()
                controls?.visibility = View.VISIBLE
            }
            ACTION_START_REVIEW -> {
                clearUncertainSquares()
                values = emptyList()
                diagnostic = false
                setStatus("盤面を待っています（9×9の外周に枠を合わせてください）")
                showPassive()
            }
            ACTION_TEST -> {
                clearUncertainSquares()
                values = emptyList() // Display test must be independent of stale engine candidates.
                diagnostic = true
                setStatus("枠表示中・解析なし")
                showPassive()
            }
            ACTION_CLEAR -> {
                values = emptyList()
                clearUncertainSquares()
                if (!calibrating) {
                    uncertainSquares = intent.getIntArrayExtra("uncertain_squares") ?: intArrayOf()
                    uncertainDx = intent.getIntExtra("uncertain_dx", 0)
                    uncertainDy = intent.getIntExtra("uncertain_dy", 0)
                    showPassive()
                }
            }
            ACTION_UPDATE -> {
                clearUncertainSquares()
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
            ACTION_CALIBRATE -> if (!calibrating) showCalibration()
            else -> {
                if (intent?.action == ACTION_REFRESH) diagnostic = false
                if (!calibrating) showPassive()
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun setStatus(message: String) {
        statusMessage = message
        controlStatus?.text = message
    }

    private fun clearControls() {
        controls?.let { try { windows.removeView(it) } catch (_: Exception) {} }
        controls = null
        controlStatus = null
        adjustButton = null
    }

    private fun ensureControls() {
        if (closing) return
        if (controls != null) {
            controls?.visibility = if (capturingFrame) View.INVISIBLE else View.VISIBLE
            return
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = GradientDrawable().apply {
                setColor(0xED263342.toInt())
                cornerRadius = dp(13).toFloat()
            }
        }
        controlStatus = TextView(this).apply {
            text = statusMessage
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        panel.addView(controlStatus, LinearLayout.LayoutParams(-1, -2))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun addButton(label: String, click: () -> Unit): Button {
            val button = Button(this).apply {
                text = label
                textSize = 15f
                setOnClickListener { click() }
            }
            buttons.addView(button, LinearLayout.LayoutParams(0, dp(44), 1f))
            return button
        }
        addButton("解析") {
            if (calibrating) setStatus("先に枠調整を完了してください")
            else if (ReviewCaptureService.requestScan()) setStatus("盤面を再解析します")
            else setStatus("設定画面で画面共有を開始してください")
        }
        adjustButton = addButton(if (calibrating) "調整完了" else "枠調整") {
            val calibration = overlayView as? BoardCalibrationView
            if (calibration != null) calibration.finish()
            else showCalibration()
        }
        addButton("終了") { stopAll(this) }
        panel.addView(buttons, LinearLayout.LayoutParams(-1, -2))
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(18)
            width = (resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(dp(160))
            alpha = 0.9f
        }
        try {
            windows.addView(panel, params)
            controls = panel
            panel.visibility = if (capturingFrame) View.INVISIBLE else View.VISIBLE
        } catch (e: Exception) {
            controlStatus = null
            adjustButton = null
            Log.e("ShogiOverlay", "Control panel could not be displayed", e)
            publishStatus("操作パネルの表示に失敗: " + e.message)
        }
    }

    private fun closeSession() {
        if (closing) return
        closing = true
        clearControls()
        clearView()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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

    private fun addWindow(view: View, params: WindowManager.LayoutParams): Boolean {
        return try {
            windows.addView(view, params)
            overlayView = view
            hasWindowError = false
            publishStatus("枠のウィンドウ作成成功（表示許可あり）")
            true
        } catch (e: Exception) {
            hasWindowError = true
            Log.e("ShogiOverlay", "WindowManager.addView failed", e)
            publishStatus("枠の表示に失敗: " + e.javaClass.simpleName +
                " " + (e.message ?: ""))
            overlayView = null
            false
        }
    }

    private fun showPassive() {
        calibrating = false
        val existing = overlayView as? CandidateOverlayView
        if (existing != null && existing.parent != null) {
            // Reuse the same window. Recreating a fullscreen application
            // overlay every 4.5 seconds causes flicker and can lose the
            // window when Android briefly suspends our background service.
            existing.setCandidates(values)
            existing.setUncertainSquares(uncertainSquares, uncertainDx, uncertainDy)
            existing.setDiagnostic(diagnostic)
            existing.setCapturingFrame(capturingFrame)
            publishStatus("枠のウィンドウ維持中（表示許可あり）")
            ensureControls()
            return
        }
        clearView()
        val view = CandidateOverlayView(this).apply {
            setCandidates(values)
            setUncertainSquares(uncertainSquares, uncertainDx, uncertainDy)
            setDiagnostic(diagnostic)
            setCapturingFrame(capturingFrame)
        }
        addWindow(view, passiveParams())
        ensureControls()
    }

    private fun showCalibration() {
        calibrating = true
        capturingFrame = false
        clearUncertainSquares()
        values = emptyList()
        clearControls()
        clearView()
        val view = BoardCalibrationView(this) {
            calibrating = false
            clearControls()
            showPassive()
            ReviewCaptureService.requestScan()
        }
        addWindow(view, calibrationParams())
        ensureControls()
    }

    override fun onDestroy() {
        closing = true
        if (running === this) running = null
        clearControls()
        clearView()
        if (!hasWindowError) publishStatus("枠表示サービス停止")
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
