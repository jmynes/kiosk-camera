package com.kioskcamera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.CountDownTimer
import android.view.OrientationEventListener
import android.view.Surface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
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
    private lateinit var flashButton: TextView
    private lateinit var hdrButton: TextView
    private lateinit var timerButton: TextView
    private lateinit var switchCameraButton: TextView
    private lateinit var exposureSlider: SeekBar
    private lateinit var exposureText: TextView
    private lateinit var countdownText: TextView

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var uploadExecutor: ExecutorService
    private val handler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient()
    private var isDestroyed = false
    private lateinit var orientationListener: OrientationEventListener
    private var deviceRotation = Surface.ROTATION_0

    // Camera state
    private var useFrontCamera = false
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var hdrEnabled = false
    private var hdrAvailable = false
    private var timerSeconds = 0 // 0 = off, 3, 10
    private var countdownTimer: CountDownTimer? = null

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
        flashButton = findViewById(R.id.flashButton)
        hdrButton = findViewById(R.id.hdrButton)
        timerButton = findViewById(R.id.timerButton)
        switchCameraButton = findViewById(R.id.switchCameraButton)
        exposureSlider = findViewById(R.id.exposureSlider)
        exposureText = findViewById(R.id.exposureText)
        countdownText = findViewById(R.id.countdownText)

        cameraExecutor = Executors.newSingleThreadExecutor()
        uploadExecutor = Executors.newSingleThreadExecutor()

        setupZoomGesture()
        setupControls()
        setupOrientationListener()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
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

    private fun setupControls() {
        captureButton.setOnClickListener { onShutterPressed() }
        galleryButton.setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }

        flashButton.setOnClickListener { cycleFlash() }
        hdrButton.setOnClickListener { toggleHdr() }
        timerButton.setOnClickListener { cycleTimer() }
        switchCameraButton.setOnClickListener { switchCamera() }
    }

    // --- Orientation ---

    private fun setupOrientationListener() {
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                deviceRotation = when {
                    orientation >= 315 || orientation < 45 -> Surface.ROTATION_0
                    orientation in 45..134 -> Surface.ROTATION_270
                    orientation in 135..224 -> Surface.ROTATION_180
                    else -> Surface.ROTATION_90
                }
                imageCapture?.targetRotation = deviceRotation
            }
        }
        if (orientationListener.canDetectOrientation()) {
            orientationListener.enable()
        }
    }

    // --- Flash ---

    private fun cycleFlash() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        imageCapture?.flashMode = flashMode
        updateFlashButton()
    }

    private fun updateFlashButton() {
        flashButton.text = when (flashMode) {
            ImageCapture.FLASH_MODE_AUTO -> "FLASH AUTO"
            ImageCapture.FLASH_MODE_ON -> "FLASH ON"
            else -> "FLASH OFF"
        }
        flashButton.setTextColor(
            if (flashMode == ImageCapture.FLASH_MODE_OFF) 0xFFFFFFFF.toInt() else 0xFFFFD700.toInt()
        )
    }

    // --- HDR ---

    private fun toggleHdr() {
        if (!hdrAvailable) {
            showStatus("HDR not available")
            return
        }
        hdrEnabled = !hdrEnabled
        updateHdrButton()
        startCamera() // Must rebind with extension
    }

    private fun updateHdrButton() {
        hdrButton.setTextColor(
            when {
                !hdrAvailable -> 0xFF555555.toInt()
                hdrEnabled -> 0xFFFFD700.toInt()
                else -> 0xFFFFFFFF.toInt()
            }
        )
        hdrButton.text = if (hdrEnabled) "HDR ON" else "HDR"
    }

    // --- Timer ---

    private fun cycleTimer() {
        timerSeconds = when (timerSeconds) {
            0 -> 3
            3 -> 10
            else -> 0
        }
        timerButton.text = if (timerSeconds == 0) "TIMER OFF" else "TIMER ${timerSeconds}s"
        timerButton.setTextColor(
            if (timerSeconds == 0) 0xFFFFFFFF.toInt() else 0xFFFFD700.toInt()
        )
    }

    // --- Camera Switch ---

    private fun switchCamera() {
        useFrontCamera = !useFrontCamera
        startCamera()
    }

    // --- Shutter / Countdown ---

    private var countdownActive = false

    private fun onShutterPressed() {
        if (countdownActive) {
            cancelCountdown()
            return
        }
        if (timerSeconds == 0) {
            takePhoto()
        } else {
            startCountdown()
        }
    }

    private fun startCountdown() {
        countdownActive = true
        countdownText.visibility = View.VISIBLE
        showStatus("Tap shutter to cancel")

        countdownTimer?.cancel()
        countdownTimer = object : CountDownTimer(timerSeconds * 1000L, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = (millisUntilFinished / 1000) + 1
                countdownText.text = secondsLeft.toString()
            }

            override fun onFinish() {
                countdownText.visibility = View.GONE
                countdownActive = false
                takePhoto()
            }
        }.start()
    }

    private fun cancelCountdown() {
        countdownTimer?.cancel()
        countdownText.visibility = View.GONE
        countdownActive = false
        captureButton.isEnabled = true
        showStatus("Timer cancelled")
    }

    // --- Zoom + Focus Gestures ---

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

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    // Double tap toggles exposure slider visibility
                    toggleExposureSlider()
                    return true
                }
            })

        previewView.setOnTouchListener { _, event ->
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

    // --- Exposure Compensation ---

    private fun toggleExposureSlider() {
        if (exposureSlider.visibility == View.VISIBLE) {
            exposureSlider.visibility = View.GONE
            exposureText.visibility = View.GONE
        } else {
            setupExposureSlider()
            exposureSlider.visibility = View.VISIBLE
            exposureText.visibility = View.VISIBLE
        }
    }

    private fun setupExposureSlider() {
        val cam = camera ?: return
        val state = cam.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) {
            showStatus("Exposure compensation not supported")
            return
        }

        val range = state.exposureCompensationRange
        val step = state.exposureCompensationStep.toFloat()

        exposureSlider.max = range.upper - range.lower
        exposureSlider.progress = state.exposureCompensationIndex - range.lower

        updateExposureText(state.exposureCompensationIndex, step)

        exposureSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val index = progress + range.lower
                    cam.cameraControl.setExposureCompensationIndex(index)
                    updateExposureText(index, step)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    private fun updateExposureText(index: Int, step: Float) {
        val ev = index * step
        exposureText.text = when {
            ev > 0 -> String.format("+%.1f EV", ev)
            ev < 0 -> String.format("%.1f EV", ev)
            else -> "0 EV"
        }
    }

    // --- Camera Startup ---

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            this.cameraProvider = provider

            val baseCameraSelector = if (useFrontCamera)
                CameraSelector.DEFAULT_FRONT_CAMERA
            else
                CameraSelector.DEFAULT_BACK_CAMERA

            // Check HDR extension availability
            val extensionsFuture = ExtensionsManager.getInstanceAsync(this, provider)
            extensionsFuture.addListener({
                val extensionsManager = extensionsFuture.get()

                hdrAvailable = extensionsManager.isExtensionAvailable(
                    baseCameraSelector, ExtensionMode.HDR
                )
                updateHdrButton()

                val cameraSelector = if (hdrEnabled && hdrAvailable) {
                    extensionsManager.getExtensionEnabledCameraSelector(
                        baseCameraSelector, ExtensionMode.HDR
                    )
                } else {
                    baseCameraSelector
                }

                bindCamera(provider, cameraSelector)
            }, ContextCompat.getMainExecutor(this))
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(provider: ProcessCameraProvider, cameraSelector: CameraSelector) {
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(flashMode)
            .build()

        provider.unbindAll()
        camera = provider.bindToLifecycle(this, cameraSelector, preview, imageCapture)

        // Reset exposure slider if visible
        if (exposureSlider.visibility == View.VISIBLE) {
            setupExposureSlider()
        }
    }

    // --- Photo Capture ---

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

    // --- Gallery Thumbnail ---

    private fun updateGalleryThumbnail() {
        val files = getQueueDir().listFiles { f -> f.extension == "jpg" }
            ?.sortedByDescending { it.lastModified() }
        if (files != null && files.isNotEmpty()) {
            val bitmap = decodeBitmapWithRotation(files[0].absolutePath, sampleSize = 8)
            galleryButton.setImageBitmap(bitmap)
        } else {
            galleryButton.setImageDrawable(null)
        }
    }

    // --- Upload Logic ---

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
        handler.postDelayed({ statusText.visibility = View.GONE }, 3000)
    }

    override fun onDestroy() {
        isDestroyed = true
        orientationListener.disable()
        countdownTimer?.cancel()
        handler.removeCallbacks(uploadRunnable)
        handler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
        uploadExecutor.shutdown()
        super.onDestroy()
    }
}
