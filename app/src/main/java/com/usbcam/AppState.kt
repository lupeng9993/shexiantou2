package com.usbcam

import java.lang.ref.WeakReference

/**
 * 记录界面是否在前台，供无障碍热键判断“该拉起还是该退到后台”。
 */
object AppState {

    @Volatile
    var isVisible: Boolean = false

    private var activityRef: WeakReference<MainActivity>? = null

    fun attach(activity: MainActivity) {
        activityRef = WeakReference(activity)
    }

    fun detach() {
        activityRef = null
    }

    /** 把 App 退到后台，露出下面那一层（导航 / 桌面）。 */
    fun hideToBackground(): Boolean {
        val activity = activityRef?.get() ?: return false
        activity.moveTaskToBack(true)
        return true
    }
}
