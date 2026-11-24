package com.shinpstudio.shogireviewoverlay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
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
        content.addView(text("駒認識は白黒画像比較（上下左右8%を除外）、探索はやねうら王Material版です。不確実な場合はSFENを手入力できます。", 13f))
        content.addView(text("対象アプリ", 18f))
        val targetSelector = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            val wars = RadioButton(this@MainActivity).apply {
                id = View.generateViewId()
                text = "将棋ウォーズ"
            }
            val quest = RadioButton(this@MainActivity).apply {
                id = View.generateViewId()
                text = "将棋クエスト"
            }
            addView(wars)
            addView(quest)
            check(if (OverlaySettings.target(this@MainActivity) == ReviewTarget.SHOGI_QUEST)
                quest.id else wars.id)
            setOnCheckedChangeListener { _, checked ->
                val selected = if (checked == quest.id) ReviewTarget.SHOGI_QUEST
                    else ReviewTarget.SHOGI_WARS
                if (selected != OverlaySettings.target(this@MainActivity)) {
                    OverlaySettings.setTarget(this@MainActivity, selected)
                    // Never transfer a Wars capture / candidate position to Quest.
                    stopService(Intent(this@MainActivity, ReviewCaptureService::class.java))
                    stopService(Intent(this@MainActivity, OverlayService::class.java))
                    if (::status.isInitialized)
                        status.text = "対象アプリを切り替えました。枠を調整し、画面共有から再開してください。"
                }
            }
        }
        content.addView(targetSelector)
        content.addView(text("切替後は対象アプリで枠調整を行ってください。盤面位置はアプリ別に記憶します。将棋クエストは9路・先手が下の振り返り画面向けです。", 13f))

        button("① ほかのアプリの上に表示を許可") {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + packageName)
            )
            startActivity(intent)
        }
        button("枠だけ表示（動作確認・解析なし）") {
            if (!Settings.canDrawOverlays(this)) {
                status.text = "表示権限がありません。先に①を許可してください。"
            } else {
                if (startOverlay(OverlayService.ACTION_TEST)) {
                    status.text = "緑の枠と「枠表示テスト：起動中」が出るか確認してください。" +
                        "出なければ「枠の表示状態を確認」を押してください。"
                }
            }
        }
        button("枠の表示状態を確認") {
            status.text = prefs.getString("last_overlay_status",
                "まだ枠表示サービスの記録がありません。先に表示テストを押してください。")
                ?: "表示状態を取得できません"
        }
        content.addView(text("初回は①で権限を許可し、「枠だけ表示」で動作確認。" +
            "手動の盤面位置調整は必須ではなく、対象ごとに初期位置があります。", 13f))
        button("② 盤面位置を調整（完了ボタンで戻る）") {
            startOverlay(OverlayService.ACTION_CALIBRATE)
            Toast.makeText(this, "対象アプリの振り返り画面に切り替え、枠を合わせて「完了」", Toast.LENGTH_LONG).show()
        }

        val cropLabel = text("枠線の除外: 外周8%（盤面の線を含めない）", 14f)
        content.addView(cropLabel)
        val cropSlider = SeekBar(this).apply {
            max = 6
            progress = prefs.getInt("crop_margin_pct", 8).coerceIn(6, 12) - 6
        }
        cropLabel.text = "枠線の除外: 外周" + (cropSlider.progress + 6) + "%"
        cropSlider.setOnSeekBarChangeListener(changeListener { step ->
            val pct = step + 6
            prefs.edit().putInt("crop_margin_pct", pct).apply()
            cropLabel.text = "枠線の除外: 外周" + pct + "%"
        })
        content.addView(cropSlider)

        button("③ 盤面を自動認識・解析（画面共有を許可）") {
            if (!Settings.canDrawOverlays(this)) {
                status.text = "先にオーバーレイ権限を許可してください"
            } else {
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(manager.createScreenCaptureIntent(), 61)
            }
        }
        button("自動認識を停止") {
            stopService(Intent(this, ReviewCaptureService::class.java))
            status.text = "画面認識を停止しました"
        }
        button("認識した最新SFENを入力欄に取り込む") {
            val value = prefs.getString("last_auto_sfen", null)
            if (value != null) sfenInput.setText(value)
            status.text = prefs.getString("last_auto_status", "認識結果がありません")
        }
        content.addView(text("振り返り時の手番"))
        switchTurn@ run {
            val toggle = Switch(this).apply {
                text = "先手番（OFFなら後手番）"
                isChecked = prefs.getBoolean("black_to_move", true)
                setOnCheckedChangeListener { _, checked ->
                    prefs.edit().putBoolean("black_to_move", checked).apply()
                }
            }
            content.addView(toggle)
        }
        content.addView(text("解析時間: 1手約1.2秒（400〜3000ms）"))
        val thinkSlider = SeekBar(this).apply {
            max = 13
            progress = ((prefs.getInt("think_ms", 1200) - 400) / 200).coerceIn(0, 13)
        }
        thinkSlider.setOnSeekBarChangeListener(changeListener { value ->
            prefs.edit().putInt("think_ms", 400 + value * 200).apply()
        })
        content.addView(thinkSlider)

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
        status = text(prefs.getString("last_auto_status", "未解析。画面取得またはSFEN入力で開始してください") ?: "未解析", 14f)
        content.addView(status)
        setContentView(scroll)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 61) return
        if (resultCode != RESULT_OK || data == null) {
            status.text = "画面取得の許可がキャンセルされました"
            return
        }
        val intent = Intent(this, ReviewCaptureService::class.java).apply {
            action = ReviewCaptureService.ACTION_START
            putExtra(ReviewCaptureService.EXTRA_CODE, resultCode)
            putExtra(ReviewCaptureService.EXTRA_DATA, data)
        }
        try {
            if (!startOverlay(OverlayService.ACTION_REFRESH)) return
            startForegroundService(intent)
            status.text = "認識を開始しました。選択したアプリの対局後振り返りへ切り替えてください"
        } catch (e: Exception) {
            status.text = "画面認識を開始できません: " + e.message
        }
    }

    private fun changeListener(change: (Int) -> Unit): SeekBar.OnSeekBarChangeListener =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) change(progress)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        }

    private fun startOverlay(action: String, extras: (Intent.() -> Unit)? = null): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            val message = "先に「他のアプリの上に表示」を許可してください"
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            if (::status.isInitialized) status.text = message
            return false
        }
        val intent = Intent(this, OverlayService::class.java).apply {
            this.action = action
            extras?.invoke(this)
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent)
            else startService(intent)
            return true
        } catch (exception: Exception) {
            val message = "オーバーレイ開始に失敗: " +
                exception.javaClass.simpleName + " " + exception.message
            OverlaySettings.preferences(this).edit()
                .putString("last_overlay_status", message).apply()
            if (::status.isInitialized) status.text = message
            return false
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
        status.text = "やねうら王で探索中..."
        val count = OverlaySettings.count(this)
        Thread {
            try {
                val candidates = UsiEngine(this).analyze(text, position, count, OverlaySettings.preferences(this).getInt("think_ms", 1200))
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
                    status.text = "やねうら王Material評価値\n" +
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
