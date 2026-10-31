package com.usbcam

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.serenegiant.widget.AspectRatioTextureView

/**
 * 悬浮窗：在其他应用（导航/桌面）上层显示摄像头画面，可拖动、双指缩放。
 */
object FloatWindow {

    private const val TAG = "FloatWindow"
    private const val KEY = "float"

    private var windowManager: WindowManager? = null
    private var root: FrameLayout? = null
    private var texture: AspectRatioTextureView? = null
    private var params: WindowManager.LayoutParams? = null

    var isShowing: Boolean = false
        private set

    fun canShow(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun toggle(context: Context): Boolean {
        if (isShowing) {
            hide()
        } else {
            show(context)
        }
        return isShowing
    }

    @Suppress("DEPRECATION")
    fun show(context: Context) {
        if (isShowing) return
        val ctx = context.applicationContext
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm == null) {
            Log.e(TAG, "no window manager")
            return
        }

        val width = if (Prefs.floatW(ctx) > 0) Prefs.floatW(ctx) else dp(ctx, 180)
        val height = width * 9 / 16

        val container = FrameLayout(ctx)
        container.background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(ctx, 2), 0xCCFFFFFF.toInt())
            cornerRadius = dp(ctx, 12).toFloat()
        }

        val preview = AspectRatioTextureView(ctx)
        preview.setAspectRatio(16, 9)
        container.addView(
            preview,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val close = TextView(ctx).apply {
            text = "×"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0x99000000.toInt())
                cornerRadius = dp(ctx, 10).toFloat()
            }
            setOnClickListener { hide() }
        }
        container.addView(
            close,
            FrameLayout.LayoutParams(dp(ctx, 22), dp(ctx, 22), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(ctx, 4)
                rightMargin = dp(ctx, 4)
            }
        )

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val lp = WindowManager.LayoutParams(
            width,
            height,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val savedX = Prefs.floatX(ctx)
            x = if (savedX >= 0) savedX else 40
            y = Prefs.floatY(ctx)
        }

        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) {
                CameraCore.addSurface(KEY, surface)
                if (CameraCore.state == CameraCore.State.IDLE) {
                    CameraCore.reopen()
                }
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, w: Int, h: Int) {
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                CameraCore.removeSurface(KEY)
                return false
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
            }
        }

        var lastX = 0
        var lastY = 0
        val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val p = params ?: return false
                val minWidth = dp(ctx, 120)
                val maxWidth = dp(ctx, 520)
                var newWidth = (p.width * detector.scaleFactor).toInt()
                newWidth = newWidth.coerceIn(minWidth, maxWidth)
                p.width = newWidth
                p.height = newWidth * 9 / 16
                try {
                    windowManager?.updateViewLayout(container, p)
                } catch (e: Exception) {
                    Log.e(TAG, "resize failed", e)
                }
                return true
            }
        })

        container.setOnTouchListener { _, event ->
            scaler.onTouchEvent(event)
            val p = params
            if (p != null) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lastX = event.rawX.toInt()
                        lastY = event.rawY.toInt()
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (!scaler.isInProgress && event.pointerCount == 1) {
                            p.x += event.rawX.toInt() - lastX
                            p.y += event.rawY.toInt() - lastY
                            lastX = event.rawX.toInt()
                            lastY = event.rawY.toInt()
                            try {
                                windowManager?.updateViewLayout(container, p)
                            } catch (e: Exception) {
                                Log.e(TAG, "move failed", e)
                            }
                        }
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        Prefs.saveFloat(ctx, p.x, p.y, p.width)
                    }
                }
            }
            true
        }

        try {
            wm.addView(container, lp)
        } catch (e: Exception) {
            Log.e(TAG, "addView failed", e)
            return
        }

        windowManager = wm
        root = container
        texture = preview
        params = lp
        isShowing = true
    }

    fun hide() {
        val container = root
        try {
            if (container != null) windowManager?.removeView(container)
        } catch (e: Exception) {
            Log.e(TAG, "removeView failed", e)
        }
        root = null
        texture = null
        params = null
        windowManager = null
        isShowing = false
        CameraCore.removeSurface(KEY)
        if (!AppState.isVisible) {
            CameraCore.close()
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
