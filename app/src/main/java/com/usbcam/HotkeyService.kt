package com.usbcam

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * 全局热键。Android 上只有无障碍服务能在后台收到硬件键盘按键，
 * 因此需要用户在「设置 → 无障碍」里手动开启一次。
 */
class HotkeyService : AccessibilityService() {

    companion object {
        private const val TAG = "HotkeyService"

        /** 绑定按键时暂停响应，避免按下旧快捷键把界面切走。 */
        @Volatile
        var paused: Boolean = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            val info = serviceInfo ?: return
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            serviceInfo = info
            Log.d(TAG, "connected, filter key events = true")
        } catch (e: Exception) {
            Log.e(TAG, "configure failed", e)
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (paused) return false
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.repeatCount > 0) return false
        if (!Prefs.isHotkeyEnabled(this)) return false
        if (event.keyCode != Prefs.hotkeyCode(this)) return false

        val wantedMods = Prefs.hotkeyMods(this)
        val actualMods = event.metaState and KeyEvent.META_MODIFIER_MASK
        if (actualMods != wantedMods) return false

        if (AppState.isVisible) {
            AppState.hideToBackground()
        } else {
            launchApp()
        }
        return true
    }

    private fun launchApp() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                        or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "launch failed", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 只处理按键，不需要监听界面事件
    }

    override fun onInterrupt() {
    }
}
