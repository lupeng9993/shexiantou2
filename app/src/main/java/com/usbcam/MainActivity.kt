package com.usbcam

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.serenegiant.opengl.renderer.MirrorMode
import com.serenegiant.usb.Size
import com.serenegiant.usb.UVCCamera
import com.serenegiant.widget.AspectRatioTextureView

/**
 * 全屏预览页：轻点唤出控件，底部上拉菜单收拢所有功能。
 */
class MainActivity : Activity(), CameraCore.Listener {

    companion object {
        private const val REQ_PERMISSIONS = 2001
        private const val KEY_ACTIVITY = "activity"

        private val COLOR_BLUE = 0xFF2F6BFF.toInt()
        private val COLOR_TEXT = 0xFFEEF2F7.toInt()
        private val COLOR_MUTED = 0xFF8E9BAD.toInt()
        private val COLOR_ACCENT_TEXT = 0xFF9DC0FF.toInt()
    }

    // ---------- 视图 ----------
    private lateinit var previewView: AspectRatioTextureView
    private lateinit var tapLayer: View
    private lateinit var hintText: TextView
    private lateinit var statusPill: TextView
    private lateinit var recBadge: TextView
    private lateinit var toolbar: LinearLayout
    private lateinit var scrim: View
    private lateinit var menuScroll: ScrollView
    private lateinit var menuPanel: LinearLayout
    private lateinit var btnRecord: TextView
    private lateinit var btnHotkey: TextView

    // ---------- 状态 ----------
    private val handler = Handler(Looper.getMainLooper())
    private var rotation = 0
    private var mirror = MirrorMode.MIRROR_NORMAL
    private var controlsVisible = false
    private var menuVisible = false
    private var recStartedAt = 0L
    private var serviceRunning = false

    private val hideRunnable = Runnable { hideControls() }

    private val recTick = object : Runnable {
        override fun run() {
            if (!CameraCore.isRecording) {
                recBadge.visibility = View.GONE
                return
            }
            val elapsed = (SystemClock.elapsedRealtime() - recStartedAt) / 1000
            recBadge.text = String.format(
                java.util.Locale.US,
                "● %02d:%02d:%02d",
                elapsed / 3600,
                elapsed % 3600 / 60,
                elapsed % 60
            )
            handler.postDelayed(this, 500)
        }
    }

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            CameraCore.addSurface(KEY_ACTIVITY, surface)
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            CameraCore.removeSurface(KEY_ACTIVITY)
            return false
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        }
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        CameraCore.init(this)
        AppState.attach(this)

        buildUi()
        applyImmersive()
        CameraCore.addListener(this)

        if (hasPermission(Manifest.permission.CAMERA)) {
            startCameraService()
        } else {
            requestBasePermissions()
        }
        refreshState()
    }

    override fun onStart() {
        super.onStart()
        AppState.isVisible = true
        previewView.surfaceTextureListener = surfaceListener
        if (previewView.isAvailable) {
            previewView.surfaceTexture?.let { CameraCore.addSurface(KEY_ACTIVITY, it) }
        }
        tryOpenDevice()
    }

    override fun onStop() {
        super.onStop()
        AppState.isVisible = false
        hideControls()
        closeMenu()
        if (!FloatWindow.isShowing) {
            if (CameraCore.isRecording) CameraCore.stopRecording()
            CameraCore.close()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        CameraCore.removeListener(this)
        previewView.surfaceTextureListener = null
        AppState.detach()
        if (!FloatWindow.isShowing) {
            CameraService.stop(this)
        }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            menuVisible -> closeMenu()
            controlsVisible -> hideControls()
            else -> super.onBackPressed()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return
        if (hasPermission(Manifest.permission.CAMERA)) {
            startCameraService()
            CameraCore.setAudioEnabled(hasPermission(Manifest.permission.RECORD_AUDIO))
            tryOpenDevice()
        } else {
            toast("没有相机权限，无法打开 USB 摄像头")
        }
    }

    // ==================================================================
    // 状态回调
    // ==================================================================

    override fun onCameraStateChanged() {
        runOnUiThread { refreshState() }
    }

    private fun refreshState() {
        val size = CameraCore.previewSize
        when (CameraCore.state) {
            CameraCore.State.PREVIEW -> {
                statusPill.text = "${CameraCore.deviceName} · " + (if (size != null) {
                    "${size.width}×${size.height} ${formatName(size.type)}"
                } else {
                    "已连接"
                })
                if (size != null) previewView.setAspectRatio(size.width, size.height)
                hintText.visibility = View.GONE
            }

            CameraCore.State.OPENING -> {
                statusPill.text = "正在连接摄像头…"
                hintText.visibility = View.VISIBLE
            }

            CameraCore.State.ERROR -> {
                statusPill.text = "摄像头打开失败"
                hintText.visibility = View.VISIBLE
            }

            else -> {
                statusPill.text = "未连接 · 等待 USB 摄像头"
                hintText.visibility = if (controlsVisible) View.GONE else View.VISIBLE
            }
        }

        btnRecord.text = if (CameraCore.isRecording) "⏹\n停止" else "⏺\n录像"
        recBadge.visibility = if (CameraCore.isRecording) View.VISIBLE else View.GONE
        if (CameraCore.isRecording && recStartedAt == 0L) {
            recStartedAt = SystemClock.elapsedRealtime()
            handler.removeCallbacks(recTick)
            handler.post(recTick)
        }
        if (!CameraCore.isRecording) {
            handler.removeCallbacks(recTick)
            recStartedAt = 0L
        }

        btnHotkey.alpha = if (Prefs.isHotkeyEnabled(this)) 1f else 0.55f
        if (menuVisible) buildMenu()
    }

    private fun formatName(type: Int): String = when (type) {
        UVCCamera.UVC_VS_FRAME_MJPEG -> "MJPEG"
        UVCCamera.UVC_VS_FRAME_UNCOMPRESSED -> "YUYV"
        else -> "TYPE$type"
    }

    // ==================================================================
    // 界面搭建
    // ==================================================================

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun pill(color: Int, radius: Int = 24): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        previewView = AspectRatioTextureView(this)
        previewView.setAspectRatio(16, 9)
        root.addView(
            previewView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        tapLayer = View(this).apply {
            isClickable = true
            setOnClickListener { toggleControls() }
        }
        root.addView(
            tapLayer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        hintText = TextView(this).apply {
            text = "轻点屏幕显示控件"
            setTextColor(0x66FFFFFF)
            textSize = 11.5f
        }
        root.addView(
            hintText,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dp(18) }
        )

        statusPill = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 11.5f
            background = pill(0x99000000.toInt(), 20)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            visibility = View.GONE
        }
        root.addView(
            statusPill,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply {
                topMargin = dp(14)
                leftMargin = dp(16)
            }
        )

        recBadge = TextView(this).apply {
            setTextColor(0xFFFF8A8A.toInt())
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            background = pill(0x99000000.toInt(), 20)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            visibility = View.GONE
        }
        root.addView(
            recBadge,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(14) }
        )

        toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = pill(0xCC11141A.toInt(), 26)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            visibility = View.GONE
        }

        val capture = toolButton("📷", "拍照") { takePhoto() }
        btnRecord = toolButton("⏺", "录像") { toggleRecord() }
        btnHotkey = toolButton("⌨", "快捷键") { toggleHotkeyFromToolbar() }
        val menuButton = toolButton("≡", "菜单") { openMenu() }

        toolbar.addView(capture, toolParams())
        toolbar.addView(btnRecord, toolParams())
        toolbar.addView(btnHotkey, toolParams())
        toolbar.addView(menuButton, toolParams())

        root.addView(
            toolbar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dp(14) }
        )

        scrim = View(this).apply {
            setBackgroundColor(0x99000000.toInt())
            visibility = View.GONE
            isClickable = true
            setOnClickListener { closeMenu() }
        }
        root.addView(
            scrim,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        menuPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(16))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xF21A1D24.toInt(), 0xF20C0E12.toInt())
            ).apply {
                cornerRadius = dp(24).toFloat()
            }
        }

        menuScroll = ScrollView(this).apply {
            isFillViewport = false
            setBackgroundColor(Color.TRANSPARENT)
            addView(menuPanel)
            visibility = View.GONE
        }

        val menuHeight = (resources.displayMetrics.heightPixels * 0.72f).toInt()
        root.addView(
            menuScroll,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                menuHeight,
                Gravity.BOTTOM
            ).apply {
                leftMargin = dp(10)
                rightMargin = dp(10)
                bottomMargin = dp(8)
            }
        )

        setContentView(root)
    }

    private fun toolButton(icon: String, label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = "$icon\n$label"
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT)
            textSize = 11f
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = pill(0x14FFFFFF, 20)
            isClickable = true
            setOnClickListener {
                onClick()
                scheduleHide()
            }
        }

    private fun toolParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = dp(3)
            rightMargin = dp(3)
        }

    // ==================================================================
    // 控件显隐
    // ==================================================================

    private fun toggleControls() {
        if (controlsVisible) hideControls() else showControls()
    }

    private fun showControls() {
        if (menuVisible) return
        controlsVisible = true
        toolbar.visibility = View.VISIBLE
        statusPill.visibility = View.VISIBLE
        hintText.visibility = View.GONE
        scheduleHide()
    }

    private fun hideControls() {
        if (menuVisible) return
        controlsVisible = false
        handler.removeCallbacks(hideRunnable)
        toolbar.visibility = View.GONE
        statusPill.visibility = View.GONE
        if (CameraCore.state != CameraCore.State.PREVIEW) {
            hintText.visibility = View.VISIBLE
        }
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, 3000)
    }

    // ==================================================================
    // 菜单
    // ==================================================================

    private fun openMenu() {
        controlsVisible = false
        toolbar.visibility = View.GONE
        statusPill.visibility = View.VISIBLE
        handler.removeCallbacks(hideRunnable)

        menuVisible = true
        buildMenu()
        scrim.visibility = View.VISIBLE
        menuScroll.visibility = View.VISIBLE
        menuScroll.alpha = 0f
        menuScroll.animate().alpha(1f).setDuration(140).start()
    }

    private fun closeMenu() {
        if (!menuVisible) return
        menuVisible = false
        scrim.visibility = View.GONE
        menuScroll.visibility = View.GONE
        toolbar.visibility = View.GONE
        statusPill.visibility = View.GONE
        hintText.visibility =
            if (CameraCore.state == CameraCore.State.PREVIEW) View.GONE else View.VISIBLE
    }

    private fun buildMenu() {
        menuPanel.removeAllViews()

        menuPanel.addView(
            TextView(this).apply {
                text = "菜单"
                setTextColor(0xFFCFD8E3.toInt())
                textSize = 13f
                setPadding(dp(6), dp(10), 0, dp(6))
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        addSection("画面")
        val size = CameraCore.previewSize
        addRow(
            "▣",
            "分辨率 / 格式",
            if (size != null) "${size.width}×${size.height} ${formatName(size.type)}" else "—"
        ) { showFormatDialog() }

        addRow("⟳", "旋转", "${rotation}°") {
            rotation = (rotation + 90) % 360
            CameraCore.setRotation(rotation)
            buildMenu()
        }

        addRow("⇋", "镜像", mirrorLabel()) {
            mirror = when (mirror) {
                MirrorMode.MIRROR_HORIZONTAL -> MirrorMode.MIRROR_VERTICAL
                MirrorMode.MIRROR_VERTICAL -> MirrorMode.MIRROR_NORMAL
                else -> MirrorMode.MIRROR_HORIZONTAL
            }
            CameraCore.setMirror(mirror)
            buildMenu()
        }

        addRow("⧉", "摄像头", CameraCore.deviceName) { showDeviceDialog() }

        addSection("窗口")
        addSwitchRow("▭", "悬浮窗", FloatWindow.isShowing) { toggleFloat() }
        addRow("▤", "后台常驻", if (serviceRunning) "运行中" else "未运行") {
            if (!serviceRunning && hasPermission(Manifest.permission.CAMERA)) {
                startCameraService()
            }
            buildMenu()
        }

        addSection("键盘快捷键")
        addSwitchRow("⌨", "快捷键开关", Prefs.isHotkeyEnabled(this)) { toggleHotkeySwitch() }
        addRow("⏎", "当前按键", keyLabel(Prefs.hotkeyCode(this), Prefs.hotkeyMods(this))) {
            startHotkeyBinding()
        }
        addRow("♿", "无障碍服务", if (isHotkeyServiceEnabled()) "已开启" else "未开启") {
            openAccessibilitySettings()
        }

        addSection("其他")
        addSwitchRow("⏻", "开机自启", Prefs.isAutostart(this)) {
            Prefs.setAutostart(this, !Prefs.isAutostart(this))
            buildMenu()
        }
        addRow("ⓘ", "关于", "v2.0") { showAbout() }
        addRow("⏏", "退出", "") { exitApp() }
    }

    private fun addSection(title: String) {
        menuPanel.addView(
            TextView(this).apply {
                text = title
                setTextColor(0xFF6F7B8C.toInt())
                textSize = 10.5f
                setPadding(dp(6), dp(12), 0, dp(4))
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun rowContainer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), 0, dp(6), 0)
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(46)
        )
    }

    private fun iconView(icon: String): TextView = TextView(this).apply {
        text = icon
        gravity = Gravity.CENTER
        setTextColor(COLOR_ACCENT_TEXT)
        textSize = 13f
        background = pill(0x242F6BFF, 9)
        layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
    }

    private fun titleView(title: String): TextView = TextView(this).apply {
        text = title
        setTextColor(COLOR_TEXT)
        textSize = 13.5f
        setPadding(dp(10), 0, 0, 0)
    }

    private fun addRow(icon: String, title: String, value: String, onClick: () -> Unit) {
        val row = rowContainer()
        row.setOnClickListener { onClick() }
        row.addView(iconView(icon))
        row.addView(titleView(title))
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        if (value.isNotEmpty()) {
            row.addView(
                TextView(this).apply {
                    text = value
                    setTextColor(COLOR_MUTED)
                    textSize = 11.5f
                    maxWidth = dp(190)
                    isSingleLine = true
                }
            )
        }
        row.addView(
            TextView(this).apply {
                text = "›"
                setTextColor(0xFF5C6779.toInt())
                textSize = 15f
                setPadding(dp(8), 0, 0, 0)
            }
        )
        menuPanel.addView(row)
    }

    private fun addSwitchRow(icon: String, title: String, on: Boolean, onToggle: () -> Unit) {
        val row = rowContainer()
        row.setOnClickListener { onToggle() }
        row.addView(iconView(icon))
        row.addView(titleView(title))
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(
            TextView(this).apply {
                text = if (on) "开" else "关"
                setTextColor(Color.WHITE)
                textSize = 11f
                gravity = Gravity.CENTER
                background = pill(if (on) COLOR_BLUE else 0x33FFFFFF, 12)
                setPadding(dp(12), dp(5), dp(12), dp(5))
            }
        )
        menuPanel.addView(row)
    }

    private fun mirrorLabel(): String = when (mirror) {
        MirrorMode.MIRROR_HORIZONTAL -> "水平"
        MirrorMode.MIRROR_VERTICAL -> "垂直"
        else -> "关"
    }

    /** 只保留 Ctrl / Alt / Shift（META_MODIFIER_MASK 是系统隐藏常量，公开 SDK 里没有）。 */
    private fun eventMods(event: KeyEvent): Int {
        var mods = 0
        if (event.isCtrlPressed) mods = mods or KeyEvent.META_CTRL_ON
        if (event.isAltPressed) mods = mods or KeyEvent.META_ALT_ON
        if (event.isShiftPressed) mods = mods or KeyEvent.META_SHIFT_ON
        return mods
    }

    private fun keyLabel(code: Int, mods: Int): String {
        val name = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
        val sb = StringBuilder()
        if ((mods and KeyEvent.META_CTRL_ON) != 0) sb.append("Ctrl+")
        if ((mods and KeyEvent.META_ALT_ON) != 0) sb.append("Alt+")
        if ((mods and KeyEvent.META_SHIFT_ON) != 0) sb.append("Shift+")
        sb.append(name)
        return sb.toString()
    }

    // ==================================================================
    // 动作
    // ==================================================================

    private fun takePhoto() {
        CameraCore.takePhoto { _, message -> toast(message) }
    }

    private fun toggleRecord() {
        if (CameraCore.isRecording) {
            CameraCore.stopRecording()
        } else {
            CameraCore.startRecording { ok, message ->
                if (ok) recStartedAt = SystemClock.elapsedRealtime()
                toast(message)
            }
        }
    }

    private fun toggleHotkeyFromToolbar() {
        if (Prefs.isHotkeyEnabled(this)) {
            Prefs.setHotkeyEnabled(this, false)
            toast("快捷键已关闭")
            refreshState()
        } else {
            startHotkeyBinding()
        }
    }

    private fun toggleFloat() {
        if (!FloatWindow.canShow(this)) {
            AlertDialog.Builder(this)
                .setTitle("需要悬浮窗权限")
                .setMessage("用于在导航等应用上层显示摄像头画面。请在接下来的设置页里允许「显示在其他应用的上层」。")
                .setPositiveButton("去设置") { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    try {
                        startActivity(intent)
                    } catch (e: Exception) {
                        toast("无法打开设置页")
                    }
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        val on = FloatWindow.toggle(this)
        toast(if (on) "悬浮窗已开启：可拖动、双指缩放" else "悬浮窗已关闭")
        buildMenu()
    }

    private fun toggleHotkeySwitch() {
        if (Prefs.isHotkeyEnabled(this)) {
            Prefs.setHotkeyEnabled(this, false)
            toast("快捷键已关闭")
            buildMenu()
        } else {
            startHotkeyBinding()
        }
    }

    private fun startHotkeyBinding() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(20), dp(24), dp(4))
        }

        content.addView(
            TextView(this).apply {
                text = "请按下要绑定的按键"
                setTextColor(COLOR_TEXT)
                textSize = 15f
                gravity = Gravity.CENTER
            }
        )
        content.addView(
            TextView(this).apply {
                text = "10 秒内按下一个键（可配合 Ctrl / Alt / Shift）"
                setTextColor(COLOR_MUTED)
                textSize = 11.5f
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
            }
        )

        val counter = TextView(this).apply {
            text = "10"
            setTextColor(COLOR_BLUE)
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(4))
        }
        content.addView(counter)

        val dialog = AlertDialog.Builder(this)
            .setView(content)
            .setNegativeButton("取消", null)
            .create()

        HotkeyService.paused = true
        dialog.setOnDismissListener { HotkeyService.paused = false }

        var remain = 10
        val tick = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                if (remain <= 0) {
                    dialog.dismiss()
                    toast("未检测到按键，已取消绑定")
                    return
                }
                counter.text = remain.toString()
                remain--
                handler.postDelayed(this, 1000)
            }
        }

        dialog.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) {
                return@setOnKeyListener false
            }
            if (keyCode == KeyEvent.KEYCODE_BACK ||
                keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
            ) {
                return@setOnKeyListener false
            }

            val mods = eventMods(event)
            Prefs.setHotkey(this, keyCode, mods)
            Prefs.setHotkeyEnabled(this, true)
            handler.removeCallbacks(tick)
            dialog.dismiss()
            toast("已绑定：${keyLabel(keyCode, mods)}")

            if (!isHotkeyServiceEnabled()) {
                promptAccessibility()
            } else {
                refreshState()
            }
            true
        }

        dialog.show()
        handler.postDelayed(tick, 1000)
    }

    private fun promptAccessibility() {
        AlertDialog.Builder(this)
            .setTitle("还需要开启无障碍服务")
            .setMessage("Android 不允许普通应用在后台读取键盘按键，需要在「设置 → 无障碍」里开启本应用的开关，快捷键才能在后台生效。")
            .setPositiveButton("去开启") { _, _ -> openAccessibilitySettings() }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            toast("无法打开无障碍设置")
        }
    }

    private fun isHotkeyServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val expected = ComponentName(this, HotkeyService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun showFormatDialog() {
        if (CameraCore.state != CameraCore.State.PREVIEW) {
            toast("请先连接摄像头")
            return
        }
        if (CameraCore.isRecording) {
            toast("请先停止录像")
            return
        }

        val formats = CameraCore.supportedFormats()
        if (formats.isNullOrEmpty()) {
            toast("读取不到分辨率列表")
            return
        }

        val entries = ArrayList<Size>()
        val seen = HashSet<String>()
        for (format in formats) {
            val descriptors = format.frameDescriptors ?: continue
            for (descriptor in descriptors) {
                val fpsList = descriptor.intervals?.map { it.fps }?.distinct() ?: emptyList()
                val candidates = if (fpsList.isEmpty()) listOf(descriptor.fps) else fpsList
                for (fps in candidates) {
                    if (fps <= 0) continue
                    val key = "${descriptor.type}-${descriptor.width}x${descriptor.height}@$fps"
                    if (!seen.add(key)) continue
                    entries.add(
                        Size(
                            descriptor.type,
                            descriptor.width,
                            descriptor.height,
                            fps,
                            ArrayList(candidates)
                        )
                    )
                }
            }
        }
        if (entries.isEmpty()) {
            toast("没有可用的分辨率")
            return
        }

        entries.sortWith(
            compareByDescending<Size> { it.width * it.height }.thenByDescending { it.fps }
        )

        val labels = entries.map {
            "${formatName(it.type)}  ${it.width}×${it.height}  @${it.fps}fps"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("选择分辨率 / 格式")
            .setItems(labels) { _, which ->
                val size = entries[which]
                CameraCore.setPreviewSize(size)
                previewView.setAspectRatio(size.width, size.height)
                toast("已切换：${size.width}×${size.height}@${size.fps}fps")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDeviceDialog() {
        val devices = CameraCore.devices()
        if (devices.isEmpty()) {
            toast("没有检测到 USB 摄像头")
            return
        }
        val labels = devices.map { device ->
            device.productName
                ?: "USB 设备 ${device.vendorId.toString(16)}:${device.productId.toString(16)}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("选择摄像头")
            .setItems(labels) { _, which ->
                CameraCore.switchTo(devices[which])
                toast("正在切换…")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("USB 摄像头 v2.0")
            .setMessage(
                "基于 libuvc（UVCAndroid）的免驱 USB 摄像头应用。\n\n" +
                    "· 全屏预览，轻点屏幕唤出控件\n" +
                    "· 拍照 / 录像 / 切换分辨率与格式\n" +
                    "· 悬浮窗：可在导航等应用上层显示\n" +
                    "· 键盘快捷键：需开启无障碍服务\n\n" +
                    "照片保存到相册 Pictures/USBCam，视频保存到 Movies/USBCam。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun exitApp() {
        if (CameraCore.isRecording) CameraCore.stopRecording()
        CameraCore.close()
        FloatWindow.hide()
        CameraService.stop(this)
        finish()
    }

    // ==================================================================
    // 设备 / 权限 / 服务
    // ==================================================================

    private fun startCameraService() {
        if (!serviceRunning) {
            serviceRunning = true
            CameraService.start(this)
        }
    }

    private fun tryOpenDevice(attempt: Int = 0) {
        if (isFinishing || !AppState.isVisible) return
        val state = CameraCore.state
        if (state == CameraCore.State.PREVIEW || state == CameraCore.State.OPENING) return

        val device = CameraCore.currentDevice ?: CameraCore.firstDevice()
        if (device != null) {
            CameraCore.open(device)
            return
        }
        if (attempt < 10) {
            handler.postDelayed({ tryOpenDevice(attempt + 1) }, 400)
        }
    }

    private fun hasPermission(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun requestBasePermissions() {
        val wanted = ArrayList<String>()
        if (!hasPermission(Manifest.permission.CAMERA)) wanted.add(Manifest.permission.CAMERA)
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) wanted.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission("android.permission.POST_NOTIFICATIONS")
        ) {
            wanted.add("android.permission.POST_NOTIFICATIONS")
        }
        if (wanted.isNotEmpty()) {
            requestPermissions(wanted.toTypedArray(), REQ_PERMISSIONS)
        }
    }

    @Suppress("DEPRECATION")
    private fun applyImmersive() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
