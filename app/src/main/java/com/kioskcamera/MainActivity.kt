package com.kioskcamera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var captureButton: ImageButton
    private lateinit var galleryButton: ImageButton
    private lateinit var statusText: TextView
    private lateinit var zoomText: TextView
    private lateinit var focusIndicator: FocusIndicatorView
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var uploadExecutor: ExecutorService
    private val handler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient()
    private var isDestroyed = false

    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private lateinit var tapGestureDetector: GestureDetector

    // TODO: Configure this to point to your server
    private val uploadUrl = "http://192.168.1.100:8080/upload"

    companion object {
        private const val TAG = "KioskCamera"
        private const val CAMERA_PERMISSION_CODE = 100
        private const val UPLOAD_RETRY_INTERVAL_MS = 30_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Don't go immersive — this is the whole point of this app
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE

        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        captureButton = findViewById(R.id.captureButton)
        galleryButton = findViewById(R.id.galleryButton)
        statusText = findViewById(R.id.statusText)
        zoomText = findViewById(R.id.zoomText)
        focusIndicator = findViewById(R.id.focusIndicator)

        cameraExecutor = Executors.newSingleThreadExecutor()
        uploadExecutor = Executors.newSingleThreadExecutor()

        setupZoomGesture()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
        }

        captureButton.setOnClickListener { takePhoto() }
        galleryButton.setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }

        startUploadLoop()
    }

    override fun onResume() {
        super.onResume()
        updateGalleryThumbnail()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupZoomGesture() {
        scaleGestureDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val cam = camera ?: return false
                    val currentZoom = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                    val newZoom = currentZoom * detector.scaleFactor
                    cam.cameraControl.setZoomRatio(newZoom)

                    zoomText.text = String.format("%.1fx", newZoom)
                    zoomText.visibility = View.VISIBLE
                    handler.removeCallbacksAndMessages("zoom")
                    handler.postDelayed({ zoomText.visibility = View.GONE }, 1500)
                    return true
                }
            })

        tapGestureDetector = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    handleTapToFocus(e)
                    return true
                }
            })

        previewView.setOnTouchListener { v, event ->
            scaleGestureDetector.onTouchEvent(event)
            tapGestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun handleTapToFocus(event: MotionEvent) {
        val cam = camera ?: return
        Log.i(TAG, "Tap to focus at (${event.x}, ${event.y})")
        focusIndicator.showAt(event.x, event.y)
        val factory = previewView.meteringPointFactory
        val point = factory.createPoint(event.x, event.y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val photoFile = File(getQueueDir(), "IMG_${timestamp}.jpg")

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        captureButton.isEnabled = false

        imageCapture.takePicture(outputOptions, cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.i(TAG, "Photo saved: ${photoFile.absolutePath}")
                    handler.post {
                        showStatus("Photo captured")
                        captureButton.isEnabled = true
                        updateGalleryThumbnail()
                    }
                    if (!isDestroyed) {
                        uploadExecutor.execute { uploadPendingPhotos() }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}")
                    handler.post {
                        showStatus("Capture failed")
                        captureButton.isEnabled = true
                    }
                }
            }
        )
    }

    private fun updateGalleryThumbnail() {
        val files = getQueueDir().listFiles { f -> f.extension == "jpg" }
            ?.sortedByDescending { it.lastModified() }
        if (files != null && files.isNotEmpty()) {
            val options = BitmapFactory.Options().apply { inSampleSize = 8 }
            val bitmap = BitmapFactory.decodeFile(files[0].absolutePath, options)
            galleryButton.setImageBitmap(bitmap)
        } else {
            galleryButton.setImageDrawable(null)
        }
    }

    private fun getQueueDir(): File {
        val dir = File(filesDir, "upload_queue")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun uploadPendingPhotos() {
        val queueDir = getQueueDir()
        val files = queueDir.listFiles { f -> f.extension == "jpg" } ?: return

        for (file in files.sortedBy { it.lastModified() }) {
            if (isDestroyed) return
            if (uploadFile(file)) {
                file.delete()
                Log.i(TAG, "Uploaded and deleted: ${file.name}")
                handler.post { showStatus("Uploaded ${file.name}") }
            } else {
                Log.w(TAG, "Upload failed for ${file.name}, will retry later")
                handler.post {
                    val pending = queueDir.listFiles { f -> f.extension == "jpg" }?.size ?: 0
                    showStatus("$pending photo(s) queued for upload")
                }
                break
            }
        }
    }

    private fun uploadFile(file: File): Boolean {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("photo", file.name, file.asRequestBody("image/jpeg".toMediaType()))
            .build()

        val request = Request.Builder()
            .url(uploadUrl)
            .post(requestBody)
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            response.use { it.isSuccessful }
        } catch (e: IOException) {
            Log.w(TAG, "Upload failed: ${e.message}")
            false
        }
    }

    private val uploadRunnable = object : Runnable {
        override fun run() {
            if (!isDestroyed) {
                uploadExecutor.execute { uploadPendingPhotos() }
                handler.postDelayed(this, UPLOAD_RETRY_INTERVAL_MS)
            }
        }
    }

    private fun startUploadLoop() {
        handler.postDelayed(uploadRunnable, UPLOAD_RETRY_INTERVAL_MS)
    }

    private fun showStatus(message: String) {
        statusText.text = message
        statusText.visibility = View.VISIBLE
        handler.removeCallbacksAndMessages("status")
        handler.postDelayed({ statusText.visibility = View.GONE }, 3000)
    }

    override fun onDestroy() {
        isDestroyed = true
        handler.removeCallbacks(uploadRunnable)
        handler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
        uploadExecutor.shutdown()
        super.onDestroy()
    }
}
