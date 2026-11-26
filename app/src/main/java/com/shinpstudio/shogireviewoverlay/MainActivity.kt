package com.shinpstudio.shogireviewoverlay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import android.graphics.Color
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
            setPadding(dp(18), dp(38), dp(18), dp(30))
            setBackgroundColor(0xFFF4F8FB.toInt())
        }
        scroll.addView(content)

        fun text(message: String, size: Float = 15f): TextView =
            TextView(this).apply {
                this.text = message
                textSize = size
                setTextColor(if (size >= 18f) 0xFF20597A.toInt() else Color.DKGRAY)
                setPadding(0, dp(7), 0, dp(7))
            }

        var destination = content
        fun button(message: String, prominent: Boolean = false, handler: () -> Unit) {
            val view = Button(this).apply {
                text = message
                isAllCaps = false
                if (prominent) {
                    setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF137365.toInt()))
                    setTextColor(Color.WHITE)
                }
                setOnClickListener { handler() }
            }
            destination.addView(view, LinearLayout.LayoutParams(-1, dp(52)))
        }

        fun expandable(label: String): LinearLayout {
            val toggle = Button(this).apply {
                text = "$label ＋"
                isAllCaps = false
            }
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            content.addView(toggle, LinearLayout.LayoutParams(-1, dp(48)))
            content.addView(body)
            toggle.setOnClickListener {
                val open = body.visibility != View.VISIBLE
                body.visibility = if (open) View.VISIBLE else View.GONE
                toggle.text = label + if (open) " −" else " ＋"
            }
            return body
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
                    OverlayService.stopAll(this@MainActivity)
                    if (::status.isInitialized)
                        status.text = "対象アプリを切り替えました。「盤面解析を開始」を押してください。"
                }
            }
        }
        content.addView(targetSelector)
        content.addView(text("盤面位置はアプリ別に記憶します。開始後に緑枠が出ます。位置が違う場合だけ画面上の「枠調整」を使ってください。", 13f))

        button("盤面解析を開始", prominent = true) { requestReview() }
        content.addView(text("画面共有を許可し、対象アプリの振り返り画面に移動してください。画面上の「解析」「枠調整」「終了」で操作できます。", 13f))
        status = text(prefs.getString("last_auto_status", "未解析") ?: "未解析", 14f)
        content.addView(status)
        button("すべて停止") {
            OverlayService.stopAll(this)
            status.text = "画面共有・枠・解析を停止しました"
        }

        content.addView(text("解析設定", 18f))
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
        val thinkLabel = text("解析時間: " + prefs.getInt("think_ms", 1200) + "ms（400〜3000ms）")
        content.addView(thinkLabel)
        val thinkSlider = SeekBar(this).apply {
            max = 13
            progress = ((prefs.getInt("think_ms", 1200) - 400) / 200).coerceIn(0, 13)
        }
        thinkSlider.setOnSeekBarChangeListener(changeListener { value ->
            val millis = 400 + value * 200
            prefs.edit().putInt("think_ms", millis).apply()
            thinkLabel.text = "解析時間: " + millis + "ms（400〜3000ms）"
        })
        content.addView(thinkSlider)

        fun engineChoice(label: String, key: String, choices: List<Int>, initial: Int, unit: String) {
            val valueText = text(label + ": " + initial + unit, 14f)
            content.addView(valueText)
            val slider = SeekBar(this).apply {
                max = choices.lastIndex
                progress = choices.indexOf(initial).coerceAtLeast(0)
            }
            slider.setOnSeekBarChangeListener(changeListener { index ->
                val selected = choices[index.coerceIn(0, choices.lastIndex)]
                prefs.edit().putInt(key, selected).apply()
                valueText.text = label + ": " + selected + unit
            })
            content.addView(slider)
        }
        engineChoice("探索スレッド数", "engine_threads", UsiEngineOptions.threadChoices,
            OverlaySettings.threads(this), "")
        engineChoice("探索Hash", "engine_hash_mb", UsiEngineOptions.hashChoices,
            OverlaySettings.hashMb(this), " MB")
        content.addView(text("高い設定は発熱・電池消費・メモリ使用量が増えます。変更は次回の探索から反映します。", 13f))

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

        content.addView(text("オーバーレイ設定", 18f))
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

        val manualBody = expandable("SFEN手動解析")
        destination = manualBody
        button("認識した最新SFENを入力欄に取り込む") {
            val value = prefs.getString("last_auto_sfen", null)
            if (value != null) sfenInput.setText(value)
            status.text = prefs.getString("last_auto_status", "認識結果がありません")
        }
        manualBody.addView(text("解析する局面（SFEN）", 15f))
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
        manualBody.addView(sfenInput, LinearLayout.LayoutParams(-1, -2))
        button("初期局面を入力") { sfenInput.setText(Sfen.INITIAL) }

        button("候補を計算・表示") { calculateAndShow() }

        val advanced = expandable("詳細設定・動作確認")
        destination = advanced
        val cropLabel = text("枠線の除外: 外周8%（盤面の線を含めない）", 14f)
        advanced.addView(cropLabel)
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
        advanced.addView(cropSlider)

        button("他のアプリの上に表示を許可") { requestOverlayPermission() }
        button("枠だけ表示（解析なし）") {
            OverlayService.stopAll(this)
            if (startOverlay(OverlayService.ACTION_TEST)) {
                status.text = "枠の表示テストを開始しました。画面上の「終了」で停止できます。"
                moveTaskToBack(true)
            }
        }
        button("枠の表示状態を確認") {
            status.text = prefs.getString("last_overlay_status", "まだ枠表示サービスの記録がありません")
                ?: "表示状態を取得できません"
        }
        destination = content
        setContentView(scroll)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
    }

    private fun requestOverlayPermission() {
        startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + packageName)), 62)
    }

    private var waitingForOverlayPermission = false

    private fun requestReview() {
        if (!Settings.canDrawOverlays(this)) {
            waitingForOverlayPermission = true
            requestOverlayPermission()
            return
        }
        // One explicit start creates a fresh capture/overlay session.
        OverlayService.stopAll(this)
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = if (Build.VERSION.SDK_INT >= 34)
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else manager.createScreenCaptureIntent()
        startActivityForResult(captureIntent, 61)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 62) {
            val resume = waitingForOverlayPermission
            waitingForOverlayPermission = false
            if (resume && Settings.canDrawOverlays(this)) requestReview()
            else if (!Settings.canDrawOverlays(this)) status.text = "オーバーレイ権限が必要です"
            return
        }
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
            if (!startOverlay(OverlayService.ACTION_START_REVIEW)) return
            startForegroundService(intent)
            status.text = "認識を開始しました。画面上の「終了」で停止できます"
            moveTaskToBack(true)
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
        if (Settings.canDrawOverlays(this)) OverlayService.deliver(
            Intent(this, OverlayService::class.java).apply { action = OverlayService.ACTION_REFRESH }
        )
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
        if (!startOverlay(OverlayService.ACTION_START_REVIEW)) return
        val session = OverlayService.sessionEpoch()
        status.text = "やねうら王で探索中..."
        val count = OverlaySettings.count(this)
        Thread {
            try {
                val candidates = UsiEngine(this).analyze(text, position, count, OverlaySettings.preferences(this).getInt("think_ms", 1200))
                runOnUiThread {
                    if (session != OverlayService.sessionEpoch()) return@runOnUiThread
                    if (candidates.isEmpty()) {
                        status.text = "合法手がありません。王手・終局を確認してください。"
                        return@runOnUiThread
                    }
                    OverlayService.deliver(Intent(this, OverlayService::class.java).apply {
                        action = OverlayService.ACTION_UPDATE
                        putStringArrayListExtra("moves", ArrayList(candidates.map { it.move.usi() }))
                        putStringArrayListExtra("labels", ArrayList(candidates.map { it.label }))
                        putExtra("scores", candidates.map { it.score }.toIntArray())
                    }, session)
                    status.text = "やねうら王Material評価値\n" +
                        candidates.mapIndexed { i, candidate ->
                            (i + 1).toString() + ". " + candidate.label +
                                "  " + formatEngineScore(candidate.score)
                        }.joinToString("\n")
                }
            } catch (error: Exception) {
                runOnUiThread { status.text = "解析失敗: " + error.message }
            }
        }.start()
    }
}
