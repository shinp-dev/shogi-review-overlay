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
import android.view.WindowManager
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * User-approved MediaProjection capture session.
 * The OCR + engine run entirely on the handset; images are never persisted.
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
    }

    private lateinit var reader: ImageReader
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var captureReady = false
    private var lastRequest = 0L
    private var lastSfen = ""
    private var lastState: ShogiPosition? = null
    private lateinit var recognizer: ShogiScreenRecognizer

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "将棋の盤面認識", NotificationManager.IMPORTANCE_LOW)
        )
        recognizer = ShogiScreenRecognizer()
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
            token.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { main.post { stopSelf() } }
            }, main)

            val metrics = resources.displayMetrics
            val w = metrics.widthPixels
            val h = metrics.heightPixels
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            reader.setOnImageAvailableListener({ source ->
                val frame = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    if (!captureReady || !busy.compareAndSet(false, true)) return@setOnImageAvailableListener
                    captureReady = false
                    val plane = frame.planes[0]
                    val stride = plane.rowStride / plane.pixelStride
                    val raw = Bitmap.createBitmap(stride, h, Bitmap.Config.ARGB_8888)
                    raw.copyPixelsFromBuffer(plane.buffer)
                    val bitmap = Bitmap.createBitmap(raw, 0, 0, w, h)
                    raw.recycle()
                    startService(Intent(this, OverlayService::class.java).apply {
                        action = OverlayService.ACTION_RESUME
                    })
                    work.execute {
                        try { scan(bitmap) } finally {
                            bitmap.recycle()
                            busy.set(false)
                        }
                    }
                } finally {
                    frame.close()
                }
            }, main)
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

    private val nextCapture = object : Runnable {
        override fun run() {
            if (projection == null) return
            if (!busy.get()) {
                captureReady = false
                startService(Intent(this@ReviewCaptureService, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_HIDE
                })
                main.postDelayed({ captureReady = true }, 300)
            }
            main.postDelayed(this, 4500)
        }
    }

    private fun scheduleCapture() { main.postDelayed(nextCapture, 1800) }

    private fun scan(bitmap: Bitmap) {
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
        val position = inferred ?: state.sfen?.let { Sfen.parse(it) } ?: return
        val sfen = ReviewStateTracker.format(position)
        if (sfen == lastSfen) return
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
            startService(updated)
            lastSfen = sfen
            lastState = position
            prefs.edit().putString("last_auto_sfen", sfen).apply()
            saveStatus("認識・探索成功: " + values.joinToString { it.label })
        } catch (error: Exception) {
            saveStatus("エンジン解析失敗: " + error.message)
        }
    }

    private fun saveStatus(text: String) {
        OverlaySettings.preferences(this).edit().putString("last_auto_status", text).apply()
    }

    override fun onDestroy() {
        main.removeCallbacks(nextCapture)
        captureReady = false
        virtualDisplay?.release()
        projection?.stop()
        if (::reader.isInitialized) reader.close()
        if (::recognizer.isInitialized) recognizer.close()
        work.shutdownNow()
        startService(Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_RESUME
        })
        super.onDestroy()
    }
}
