package com.usbcam

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.herohan.uvcapp.IImageCapture
import com.herohan.uvcapp.VideoCapture
import com.serenegiant.usb.Size
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 摄像头的唯一持有者：界面（全屏页）和悬浮窗都只跟它打交道。
 * 它是进程内单例，因此 Activity 退到后台、悬浮窗还在时，摄像头可以继续出流。
 */
object CameraCore : ICameraHelper.StateCallback {

    private const val TAG = "CameraCore"

    enum class State { IDLE, OPENING, PREVIEW, ERROR }

    interface Listener {
        fun onCameraStateChanged()
    }

    @Volatile
    var state: State = State.IDLE
        private set

    @Volatile
    var currentDevice: UsbDevice? = null
        private set

    @Volatile
    var previewSize: Size? = null
        private set

    @Volatile
    var deviceName: String = "USB Camera"
        private set

    @Volatile
    var isRecording: Boolean = false
        private set

    private var app: Context? = null
    private var helper: CameraHelper? = null
    private var pendingDevice: UsbDevice? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val surfaces = LinkedHashMap<String, SurfaceTexture>()

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    fun init(context: Context) {
        val ctx = context.applicationContext
        app = ctx
        if (helper != null) return

        val h = CameraHelper()
        h.setStateCallback(this)
        h.setImageCaptureConfig(h.getImageCaptureConfig().setJpegCompressionQuality(95))
        h.setVideoCaptureConfig(
            h.getVideoCaptureConfig()
                .setVideoFrameRate(30)
                .setIFrameInterval(1)
                .setBitRate(12 * 1024 * 1024)
                .setAudioCaptureEnable(hasPermission(ctx, android.Manifest.permission.RECORD_AUDIO))
        )
        helper = h
    }

    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    private fun notifyChanged() {
        mainHandler.post {
            for (l in listeners) {
                try {
                    l.onCameraStateChanged()
                } catch (e: Exception) {
                    Log.e(TAG, "listener error", e)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 画面输出
    // ------------------------------------------------------------------

    /** key 用 "activity" / "float" 区分，两个画面可以同时挂载。 */
    fun addSurface(key: String, texture: SurfaceTexture) {
        surfaces[key] = texture
        if (state == State.PREVIEW) {
            helper?.addSurface(texture, false)
        }
    }

    fun removeSurface(key: String) {
        val texture = surfaces.remove(key) ?: return
        if (state == State.PREVIEW) {
            helper?.removeSurface(texture)
        }
    }

    // ------------------------------------------------------------------
    // 打开 / 关闭
    // ------------------------------------------------------------------

    fun open(device: UsbDevice) {
        val h = helper ?: return
        if (state == State.PREVIEW || state == State.OPENING) return
        currentDevice = device
        state = State.OPENING
        notifyChanged()
        h.selectDevice(device)
    }

    /** 重新打开上次使用的设备（从后台回到前台时调用）。 */
    fun reopen() {
        val device = currentDevice ?: return
        val h = helper ?: return
        if (state == State.PREVIEW || state == State.OPENING) return
        state = State.OPENING
        notifyChanged()
        h.selectDevice(device)
    }

    fun close() {
        helper?.closeCamera()
    }

    /** 切换到另一台摄像头：先关再开（关闭是异步的，所以用 pending 记录下一步）。 */
    fun switchTo(device: UsbDevice) {
        if (state == State.PREVIEW || state == State.OPENING) {
            pendingDevice = device
            helper?.closeCamera()
        } else {
            open(device)
        }
    }

    fun firstDevice(): UsbDevice? {
        val list = helper?.getDeviceList() ?: return null
        return list.firstOrNull()
    }

    fun devices(): List<UsbDevice> = helper?.getDeviceList() ?: emptyList()

    // ------------------------------------------------------------------
    // 参数
    // ------------------------------------------------------------------

    fun setPreviewSize(size: Size) {
        val h = helper ?: return
        h.stopPreview()
        h.setPreviewSize(size)
        h.startPreview()
    }

    fun setRotation(rotation: Int) {
        val h = helper ?: return
        h.setPreviewConfig(h.getPreviewConfig().setRotation(rotation))
    }

    fun setMirror(mirror: Int) {
        val h = helper ?: return
        h.setPreviewConfig(h.getPreviewConfig().setMirror(mirror))
    }

    fun supportedFormats() = helper?.getSupportedFormatList()

    fun setAudioEnabled(enabled: Boolean) {
        val h = helper ?: return
        h.setVideoCaptureConfig(h.getVideoCaptureConfig().setAudioCaptureEnable(enabled))
    }

    // ------------------------------------------------------------------
    // 拍照 / 录像
    // ------------------------------------------------------------------

    fun takePhoto(onDone: (Boolean, String) -> Unit) {
        val h = helper
        val ctx = app
        if (h == null || ctx == null) {
            onDone(false, "摄像头未初始化")
            return
        }
        if (state != State.PREVIEW) {
            onDone(false, "请先连接摄像头")
            return
        }

        val file = newMediaFile(ctx, "IMG", "jpg", Environment.DIRECTORY_PICTURES)
        val options = IImageCapture.OutputFileOptions.Builder(file).build()

        h.takePicture(options, object : IImageCapture.OnImageCaptureCallback {
            override fun onImageSaved(outputFileResults: IImageCapture.OutputFileResults) {
                val exported = MediaStoreExport.export(ctx, file, false)
                mainHandler.post {
                    onDone(true, if (exported != null) "照片已保存到相册" else "照片已保存：${file.absolutePath}")
                }
            }

            override fun onError(imageCaptureError: Int, message: String, cause: Throwable?) {
                mainHandler.post { onDone(false, "拍照失败：$message") }
            }
        })
    }

    fun startRecording(onDone: (Boolean, String) -> Unit) {
        val h = helper
        val ctx = app
        if (h == null || ctx == null) {
            onDone(false, "摄像头未初始化")
            return
        }
        if (state != State.PREVIEW) {
            onDone(false, "请先连接摄像头")
            return
        }
        if (isRecording) {
            onDone(false, "已在录像中")
            return
        }

        val file = newMediaFile(ctx, "VID", "mp4", Environment.DIRECTORY_MOVIES)
        val options = VideoCapture.OutputFileOptions.Builder(file).build()

        h.startRecording(options, object : VideoCapture.OnVideoCaptureCallback {
            override fun onStart() {
                isRecording = true
                notifyChanged()
                mainHandler.post { onDone(true, "开始录像") }
            }

            override fun onVideoSaved(outputFileResults: VideoCapture.OutputFileResults) {
                isRecording = false
                notifyChanged()
                val exported = MediaStoreExport.export(ctx, file, true)
                mainHandler.post {
                    onDone(true, if (exported != null) "录像已保存到相册" else "录像已保存：${file.absolutePath}")
                }
            }

            override fun onError(videoCaptureError: Int, message: String, cause: Throwable?) {
                isRecording = false
                notifyChanged()
                mainHandler.post { onDone(false, "录像失败：$message") }
            }
        })
    }

    fun stopRecording() {
        helper?.stopRecording()
    }

    // ------------------------------------------------------------------
    // ICameraHelper.StateCallback
    // ------------------------------------------------------------------

    override fun onAttach(device: UsbDevice) {
        Log.d(TAG, "onAttach: ${device.deviceName}")
    }

    override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
        helper?.openCamera()
    }

    override fun onCameraOpen(device: UsbDevice) {
        val h = helper ?: return
        state = State.PREVIEW
        currentDevice = device
        deviceName = device.productName ?: "USB Camera"
        h.startPreview()
        previewSize = h.getPreviewSize()
        for (texture in surfaces.values) {
            h.addSurface(texture, false)
        }
        notifyChanged()
    }

    override fun onCameraClose(device: UsbDevice) {
        state = State.IDLE
        isRecording = false
        val next = pendingDevice
        if (next != null) {
            pendingDevice = null
            notifyChanged()
            open(next)
        } else {
            notifyChanged()
        }
    }

    override fun onDeviceClose(device: UsbDevice) {
        Log.d(TAG, "onDeviceClose")
    }

    override fun onDetach(device: UsbDevice) {
        if (currentDevice?.deviceId == device.deviceId) {
            currentDevice = null
        }
        state = State.IDLE
        isRecording = false
        notifyChanged()
    }

    override fun onCancel(device: UsbDevice) {
        state = State.IDLE
        notifyChanged()
    }

    override fun onError(device: UsbDevice, e: CameraException) {
        Log.e(TAG, "camera error", e)
        state = State.ERROR
        notifyChanged()
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun newMediaFile(context: Context, prefix: String, suffix: String, dir: String): File {
        val base = context.getExternalFilesDir(dir) ?: context.filesDir
        val target = File(base, "USBCam")
        if (!target.exists()) target.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(target, "${prefix}_$stamp.$suffix")
    }

    private fun hasPermission(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
