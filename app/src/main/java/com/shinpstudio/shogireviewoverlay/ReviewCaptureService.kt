package com.shinpstudio.shogireviewoverlay

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import android.view.WindowManager
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * User-approved MediaProjection capture session.
 * The binary template matcher + engine run entirely on the handset; images are never persisted.
 * An invalid or partial position never replaces the preceding display.
 */
class ReviewCaptureService : Service() {
    companion object {
        const val ACTION_START = "reviewcapture.START"
        const val ACTION_STOP = "reviewcapture.STOP"
        const val EXTRA_CODE = "projection_code"
        const val EXTRA_DATA = "projection_data"
        private const val CHANNEL = "shogi_capture"
        private const val NOTIFICATION_ID = 311
        @Volatile private var running: ReviewCaptureService? = null
        fun requestScan(): Boolean {
            val service = running ?: return false
            if (service.stopped) return false
            service.forceScan.set(true)
            service.main.post {
                if (!service.stopped && !service.busy.get()) {
                    service.main.removeCallbacks(service.nextCapture)
                    service.main.post(service.nextCapture)
                }
            }
            return true
        }
    }

    private lateinit var reader: ImageReader
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val forceScan = AtomicBoolean(false)
    @Volatile private var stopped = false
    private var overlayEpoch = 0L

    private fun sendOverlay(intent: Intent) {
        if (!stopped) OverlayService.deliver(intent, overlayEpoch)
    }
    private val pendingFrame = Runnable { grabFrame() }
    private var activeTarget: ReviewTarget? = null
    private var lastSfen = ""
    private var lastBoardSignature = 0L
    private var lastRejectedSignature = 0L
    private var lastRejectedAt = 0L
    private var lastState: ShogiPosition? = null
    private lateinit var recognizer: ShogiScreenRecognizer

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "将棋の盤面認識", NotificationManager.IMPORTANCE_LOW)
        )
        recognizer = ShogiScreenRecognizer(this)
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || projection != null) return START_NOT_STICKY
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ReviewCaptureService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("将棋振り返りを解析中")
            .setContentText("画面取得・駒認識は端末内のみ。対局後レビュー専用です")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stop)
            .build()
        startForeground(
            NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        val code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
        if (code != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val token = manager.getMediaProjection(code, data)
            projection = token
            overlayEpoch = OverlayService.sessionEpoch()
            running = this
            token.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { main.post { stopSelf() } }
            }, main)

            val metrics = resources.displayMetrics
            val bounds = if (Build.VERSION.SDK_INT >= 30) {
                (getSystemService(WINDOW_SERVICE) as WindowManager).maximumWindowMetrics.bounds
            } else null
            val w = bounds?.width() ?: metrics.widthPixels
            val h = bounds?.height() ?: metrics.heightPixels
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            virtualDisplay = token.createVirtualDisplay(
                "ShogiReviewCapture", w, h, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, main
            )
            scheduleCapture()
        } catch (exception: Exception) {
            saveStatus("画面取得を開始できません: " + exception.message)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun reportOverlayFailure(operation: String, error: Exception) {
        val message = operation + ": " + error.javaClass.simpleName +
            " " + (error.message ?: "")
        Log.e("ShogiOverlay", message, error)
        OverlaySettings.preferences(this).edit()
            .putString("last_overlay_status", message)
            .apply()
    }

    private fun finishOverlayCapture() {
        try {
            sendOverlay(Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_FINISH_CAPTURE
            })
        } catch (error: Exception) {
            // Android may reject a background service start if the overlay
            // service was killed while MediaProjection continued capturing.
            // Never swallow this: the user otherwise sees no border and no
            // indication of why it disappeared.
            reportOverlayFailure("画面取得後の候補表示の復帰に失敗", error)
        }
    }

    private fun grabFrame() {
        if (stopped || OverlayService.isCalibrating()) {
            finishOverlayCapture()
            busy.set(false)
            return
        }
        val image = try { reader.acquireLatestImage() } catch (_: Exception) { null }
        if (image == null) {
            finishOverlayCapture()
            busy.set(false)
            return
        }
        try {
            val p = image.planes[0]
            val rowPixels = p.rowStride / p.pixelStride
            val raw = Bitmap.createBitmap(rowPixels, image.height, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(p.buffer)
            val bitmap = Bitmap.createBitmap(raw, 0, 0, image.width, image.height)
            if (bitmap !== raw) raw.recycle()
            work.execute {
                try { scan(bitmap) } finally {
                    bitmap.recycle()
                    busy.set(false)
                }
            }
        } catch (exception: Exception) {
            busy.set(false)
            saveStatus("画面の読み取りに失敗: " + exception.message)
        } finally {
            image.close()
            finishOverlayCapture()
        }
    }

    private val nextCapture = object : Runnable {
        override fun run() {
            if (projection == null || stopped) return
            if (OverlayService.isCalibrating()) {
                main.postDelayed(this, 4500)
                return
            }
            if (busy.compareAndSet(false, true) && ::reader.isInitialized) {
                // Give the display time to redraw without the arrows and
                // labels. Keep any pending frame for a static board, where
                // hiding empty candidates might not produce a new image.
                try {
                    sendOverlay(Intent(this@ReviewCaptureService, OverlayService::class.java).apply {
                        action = OverlayService.ACTION_PREPARE_CAPTURE
                    })
                } catch (error: Exception) {
                    reportOverlayFailure("画面取得前の候補の一時非表示に失敗", error)
                }
                main.postDelayed(pendingFrame, 350)
            }
            main.postDelayed(this, 4500)
        }
    }
    private fun scheduleCapture() { main.postDelayed(nextCapture, 1800) }

    private fun signature(bitmap: Bitmap): Long {
        val board = OverlaySettings.board(this).pixels(bitmap.width, bitmap.height)
        var hash = 1469598103934665603L
        for (row in 0..8) for (col in 0..8) for (step in 0..8) {
            val dx = (step % 3 - 1) * .20f
            val dy = (step / 3 - 1) * .20f
            val x = (board.left + (col + .5f + dx) * board.width() / 9)
                .toInt().coerceIn(0, bitmap.width - 1)
            val y = (board.top + (row + .5f + dy) * board.height() / 9)
                .toInt().coerceIn(0, bitmap.height - 1)
            hash = (hash xor bitmap.getPixel(x, y).toLong()) * 1099511628211L
        }
        return hash
    }

    private fun scan(bitmap: Bitmap) {
        if (stopped || OverlayService.isCalibrating()) return
        if (forceScan.getAndSet(false)) {
            lastSfen = ""
            lastBoardSignature = 0L
            lastRejectedSignature = 0L
            lastRejectedAt = 0L
        }
        val selected = OverlaySettings.target(this)
        if (activeTarget != selected) {
            // Captured state, inventory history, alignment and move count must
            // never be carried across different review applications.
            activeTarget = selected
            lastSfen = ""
            lastBoardSignature = 0L
            lastRejectedSignature = 0L
            lastRejectedAt = 0L
            lastState = null
            sendOverlay(Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_CLEAR
            })
        }
        val hash = signature(bitmap)
        if (hash == lastBoardSignature && lastSfen.isNotEmpty()) return
        if (hash == lastRejectedSignature &&
            SystemClock.elapsedRealtime() - lastRejectedAt < 15000) return
        val prefs = OverlaySettings.preferences(this)
        val turn = if (prefs.getBoolean("black_to_move", true)) Side.BLACK else Side.WHITE
        val state = try {
            recognizer.recognize(bitmap, OverlaySettings.board(this), turn)
        } catch (error: Exception) {
            BoardRecognition(null, "画像認識に失敗: " + error.message, 0)
        }
        saveStatus(state.message)
        val inferred = if (state.board != null && lastState != null)
            ReviewStateTracker.resolve(requireNotNull(lastState), state.board) else null
        // Prefer unique one-step tracking (it preserves the true next mover
        // and move number). If replay jumped several moves, Quest's verified
        // holding trays can re-establish a complete position from that frame.
        val position = inferred ?: state.sfen?.let { Sfen.parse(it) }
        if (position == null) {
            // The displayed candidates are cleared; a return to the previous
            // good board must not be skipped as an already displayed result.
            lastSfen = ""
            lastBoardSignature = 0L
            lastRejectedSignature = hash
            lastRejectedAt = SystemClock.elapsedRealtime()
            sendOverlay(Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_CLEAR
                putExtra("uncertain_squares", state.uncertainSquares.toIntArray())
                putExtra("uncertain_dx", state.offsetX)
                putExtra("uncertain_dy", state.offsetY)
            })
            return
        }
        lastRejectedSignature = 0L
        lastRejectedAt = 0L
        val sfen = ReviewStateTracker.format(position)
        // Even with unchanged SFEN, the board can shift on screen. Keep the
        // arrows aligned without restarting the engine on the same position.
        if (sfen == lastSfen) {
            OverlaySettings.saveAlignment(this, state.offsetX, state.offsetY)
            sendOverlay(Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_REFRESH
            })
            lastBoardSignature = hash
            return
        }
        try {
            val values = UsiEngine(this).analyze(
                sfen, position, OverlaySettings.count(this),
                prefs.getInt("think_ms", 1200)
            )
            val updated = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_UPDATE
                putStringArrayListExtra("moves", ArrayList(values.map { it.move.usi() }))
                putStringArrayListExtra("labels", ArrayList(values.map { it.label }))
                putExtra("scores", values.map { it.score }.toIntArray())
            }
            // Do not publish work that finished after Exit or during calibration.
            if (stopped || overlayEpoch != OverlayService.sessionEpoch() || OverlayService.isCalibrating()) return
            // Only accepted, engine-validated positions may move the overlay.
            OverlaySettings.saveAlignment(this, state.offsetX, state.offsetY)
            sendOverlay(updated)
            lastSfen = sfen
            lastBoardSignature = hash
            lastState = position
            prefs.edit().putString("last_auto_sfen", sfen).apply()
            saveStatus("認識・探索成功: " + values.joinToString { it.label })
        } catch (error: Exception) {
            lastRejectedSignature = hash
            lastRejectedAt = SystemClock.elapsedRealtime()
            sendOverlay(Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_CLEAR
            })
            saveStatus("エンジン解析失敗: " + error.message)
        }
    }

    private fun saveStatus(text: String) {
        if (stopped || overlayEpoch != OverlayService.sessionEpoch()) return
        OverlaySettings.preferences(this).edit().putString("last_auto_status", text).apply()
        sendOverlay(Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STATUS
            putExtra("message", text)
        })
    }

    override fun onDestroy() {
        stopped = true
        if (running === this) running = null
        main.removeCallbacks(nextCapture)
        main.removeCallbacks(pendingFrame)
        virtualDisplay?.release()
        projection?.stop()
        if (::reader.isInitialized) reader.close()
        if (::recognizer.isInitialized) recognizer.close()
        work.shutdownNow()
        OverlayService.deliver(Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_FINISH_CAPTURE
        }, overlayEpoch)
        super.onDestroy()
    }
}
