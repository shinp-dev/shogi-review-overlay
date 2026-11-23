package com.shinpstudio.shogireviewoverlay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * A small native settings screen. The overlay is never used for entering SFEN,
 * to avoid stealing taps from the reviewed shogi application.
 */
class MainActivity : Activity() {
    private lateinit var sfenInput: EditText
    private lateinit var status: TextView

    private fun dp(amount: Int): Int = (amount * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = OverlaySettings.preferences(this)
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(30))
        }
        scroll.addView(content)

        fun text(message: String, size: Float = 15f): TextView =
            TextView(this).apply {
                this.text = message
                textSize = size
                setPadding(0, dp(7), 0, dp(7))
            }

        fun button(message: String, handler: () -> Unit) {
            val view = Button(this).apply {
                text = message
                setOnClickListener { handler() }
            }
            content.addView(view, LinearLayout.LayoutParams(-1, dp(52)))
        }

        content.addView(text("将棋レビュー・オーバーレイ", 23f))
        content.addView(text("対局後の振り返り専用です。対局中には使用しないでください。", 14f))
        content.addView(text("現在はSFEN手入力＋簡易探索による画面試作です。自動盤面認識と本格エンジンは未実装。", 13f))

        button("① ほかのアプリの上に表示を許可") {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + packageName)
            )
            startActivity(intent)
        }
        button("② 盤面位置を調整（完了ボタンで戻る）") {
            startOverlay(OverlayService.ACTION_CALIBRATE)
            Toast.makeText(this, "将棋ウォーズの振り返り画面に切り替え、枠を合わせて「完了」", Toast.LENGTH_LONG).show()
        }

        content.addView(text("③ 解析する局面（SFEN）", 18f))
        sfenInput = EditText(this).apply {
            setText(OverlaySettings.sfen(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 3
            maxLines = 5
            setHorizontallyScrolling(false)
            setBackgroundColor(0xFFF0F2F5.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            gravity = Gravity.TOP or Gravity.START
            textSize = 13f
        }
        content.addView(sfenInput, LinearLayout.LayoutParams(-1, -2))
        button("初期局面を入力") { sfenInput.setText(Sfen.INITIAL) }

        content.addView(text("候補数（1〜3）"))
        val countText = text("候補: " + OverlaySettings.count(this), 14f)
        content.addView(countText)
        val countSlider = SeekBar(this).apply {
            max = 2
            progress = OverlaySettings.count(this@MainActivity) - 1
        }
        countSlider.setOnSeekBarChangeListener(changeListener { value ->
            prefs.edit().putInt("count", value + 1).apply()
            countText.text = "候補: " + (value + 1)
        })
        content.addView(countSlider)

        content.addView(text("オーバーレイ文字サイズ"))
        val fontText = text("文字サイズ: " + OverlaySettings.fontSp(this) + "sp", 14f)
        content.addView(fontText)
        val fontSlider = SeekBar(this).apply {
            max = 14
            progress = OverlaySettings.fontSp(this@MainActivity) - 10
        }
        fontSlider.setOnSeekBarChangeListener(changeListener { value ->
            val sp = value + 10
            prefs.edit().putInt("font_sp", sp).apply()
            fontText.text = "文字サイズ: " + sp + "sp"
            refreshOverlay()
        })
        content.addView(fontSlider)

        fun switch(message: String, key: String, default: Boolean) {
            val view = Switch(this).apply {
                text = message
                isChecked = prefs.getBoolean(key, default)
                setPadding(0, dp(8), 0, dp(8))
                setOnCheckedChangeListener { _, checked ->
                    prefs.edit().putBoolean(key, checked).apply()
                    refreshOverlay()
                }
            }
            content.addView(view)
        }
        switch("移動矢印を表示", "arrows", true)
        switch("候補一覧を表示", "panel", true)

        button("④ 候補を計算・オーバーレイに表示") { calculateAndShow() }
        button("オーバーレイ停止") {
            stopService(Intent(this, OverlayService::class.java))
            status.text = "オーバーレイを停止しました"
        }
        status = text("未解析。初期局面から表示を試せます。", 14f)
        content.addView(status)
        setContentView(scroll)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
    }

    private fun changeListener(change: (Int) -> Unit): SeekBar.OnSeekBarChangeListener =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) change(progress)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        }

    private fun startOverlay(action: String, extras: (Intent.() -> Unit)? = null) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "先に「他のアプリの上に表示」を許可してください", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(this, OverlayService::class.java).apply {
            this.action = action
            extras?.invoke(this)
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent)
            else startService(intent)
        } catch (exception: Exception) {
            status.text = "オーバーレイ開始に失敗: " + exception.message
        }
    }

    private fun refreshOverlay() {
        if (Settings.canDrawOverlays(this)) startOverlay(OverlayService.ACTION_REFRESH)
    }

    private fun calculateAndShow() {
        val text = sfenInput.text.toString().trim()
        val position = try {
            Sfen.parse(text)
        } catch (error: Exception) {
            status.text = "SFENを確認してください: " + error.message
            return
        }
        OverlaySettings.preferences(this).edit().putString("sfen", text).apply()
        status.text = "簡易候補を探索中..."
        val count = OverlaySettings.count(this)
        Thread {
            try {
                val candidates = SimpleAnalyzer.analyze(position, count)
                runOnUiThread {
                    if (candidates.isEmpty()) {
                        status.text = "合法手がありません。王手・終局を確認してください。"
                        return@runOnUiThread
                    }
                    startOverlay(OverlayService.ACTION_UPDATE) {
                        putStringArrayListExtra("moves", ArrayList(candidates.map { it.move.usi() }))
                        putStringArrayListExtra("labels", ArrayList(candidates.map { it.label }))
                        putExtra("scores", candidates.map { it.score }.toIntArray())
                    }
                    status.text = "簡易参考値（本格エンジン未接続）\n" +
                        candidates.mapIndexed { i, candidate ->
                            (i + 1).toString() + ". " + candidate.label +
                                "  " + (if (candidate.score >= 0) "+" else "") + candidate.score
                        }.joinToString("\n")
                }
            } catch (error: Exception) {
                runOnUiThread { status.text = "解析失敗: " + error.message }
            }
        }.start()
    }
}
