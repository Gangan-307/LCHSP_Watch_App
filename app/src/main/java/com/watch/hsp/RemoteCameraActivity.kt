package com.watch.hsp

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RemoteCameraActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusView: TextView
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val captureHandler: () -> Unit = { takePhoto() }
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) bindCamera() else showStatus("需要相机权限")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        setContentView(createContentView())
        getSystemService(android.app.NotificationManager::class.java)
            ?.cancel(REMOTE_CAMERA_NOTIFICATION_ID)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            bindCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onStart() {
        super.onStart()
        if (imageCapture != null) RemoteCameraController.attach(captureHandler)
    }

    override fun onStop() {
        RemoteCameraController.detach(captureHandler)
        super.onStop()
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        super.onDestroy()
    }

    private fun createContentView(): FrameLayout {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        root.addView(
            previewView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val close = TextView(this).apply {
            text = "×"
            textSize = 36f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(0x66000000)
            setOnClickListener { finish() }
            contentDescription = "关闭遥控相机"
        }
        root.addView(close, FrameLayout.LayoutParams(64.dp, 64.dp).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = 16.dp
            topMargin = 20.dp
        })

        statusView = TextView(this).apply {
            text = "正在打开相机"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(18.dp, 12.dp, 18.dp, 12.dp)
            setBackgroundColor(0x88000000.toInt())
        }
        root.addView(statusView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 40.dp
        })
        return root
    }

    private fun bindCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture
                )
                cameraProvider = provider
                imageCapture = capture
                RemoteCameraController.attach(captureHandler)
                showStatus("相机已就绪，请按手表快门")
            } catch (exception: Exception) {
                imageCapture = null
                showStatus("相机启动失败")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: run {
            showStatus("相机尚未就绪")
            return
        }
        val name = "HSP_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}"
        val output = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/HSP Watch")
            }
            ImageCapture.OutputFileOptions.Builder(
                contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            ).build()
        } else {
            val directory = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                ?: filesDir
            ImageCapture.OutputFileOptions.Builder(File(directory, "$name.jpg")).build()
        }

        showStatus("正在拍照")
        capture.takePicture(
            output,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    showStatus("照片已保存")
                }

                override fun onError(exception: ImageCaptureException) {
                    showStatus("拍照失败")
                }
            }
        )
    }

    private fun showStatus(text: String) {
        statusView.text = text
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    companion object {
        const val REMOTE_CAMERA_NOTIFICATION_ID = 1002
    }
}
