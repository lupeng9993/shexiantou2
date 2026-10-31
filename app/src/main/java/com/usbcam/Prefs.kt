package com.usbcam

import android.content.Context
import android.view.KeyEvent

/**
 * 轻量偏好存储（避免引入 DataStore / 额外依赖）。
 */
object Prefs {

    private const val FILE = "usbcam_prefs"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---------- 快捷键 ----------
    fun isHotkeyEnabled(c: Context): Boolean = sp(c).getBoolean("hotkey_enabled", false)

    fun setHotkeyEnabled(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("hotkey_enabled", on).apply()
    }

    fun hotkeyCode(c: Context): Int = sp(c).getInt("hotkey_code", KeyEvent.KEYCODE_F8)

    fun hotkeyMods(c: Context): Int = sp(c).getInt("hotkey_mods", 0)

    fun setHotkey(c: Context, code: Int, mods: Int) {
        sp(c).edit().putInt("hotkey_code", code).putInt("hotkey_mods", mods).apply()
    }

    // ---------- 开机自启 ----------
    fun isAutostart(c: Context): Boolean = sp(c).getBoolean("autostart", true)

    fun setAutostart(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("autostart", on).apply()
    }

    // ---------- 悬浮窗位置 / 大小 ----------
    fun floatX(c: Context): Int = sp(c).getInt("float_x", -1)
    fun floatY(c: Context): Int = sp(c).getInt("float_y", 120)
    fun floatW(c: Context): Int = sp(c).getInt("float_w", 0)

    fun saveFloat(c: Context, x: Int, y: Int, w: Int) {
        sp(c).edit().putInt("float_x", x).putInt("float_y", y).putInt("float_w", w).apply()
    }
}
