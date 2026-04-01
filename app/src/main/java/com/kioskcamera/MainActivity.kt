package com.kioskcamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.os.Bundle
import android.os.CountDownTimer
import android.view.animation.DecelerateInterpolator
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
import androidx.camera.video.*
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
    private lateinit var photoModeButton: ImageButton
    private lateinit var videoModeButton: ImageButton
    private lateinit var recordingTimer: TextView

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
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
    private var pendingRecordOnBind = false
    private var longPressStartedFromPhoto = false
    private var longPressRecordingStartTime = 0L
    private var pendingPhotoOnBind = false
    private var revertToPhotoAfterRecording = false
    private var useFrontCamera = false
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var hdrEnabled = false
    private var hdrAvailable = false
    private var timerSeconds = 0
    private var countdownTimer: CountDownTimer? = null
    private var isVideoMode = false
    private var isRecording = false
    private var recordingStartTime = 0L

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
        photoModeButton = findViewById(R.id.photoModeButton)
        videoModeButton = findViewById(R.id.videoModeButton)
        recordingTimer = findViewById(R.id.recordingTimer)

        cameraExecutor = Executors.newSingleThreadExecutor()
        uploadExecutor = Executors.newSingleThreadExecutor()

        setupZoomGesture()
        setupControls()
        setupOrientationListener()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                CAMERA_PERMISSION_CODE)
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
        var shutterDownTime = 0L
        var shutterHeld = false
        val longPressRunnable = Runnable {
            if (shutterHeld && !isVideoMode && !isRecording) {
                longPressStartedFromPhoto = true
                pendingRecordOnBind = true
                setMode(true)
            }
        }
        captureButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    shutterDownTime = System.currentTimeMillis()
                    shutterHeld = true
                    longPressStartedFromPhoto = false
                    if (!isVideoMode && !isRecording) {
                        handler.postDelayed(longPressRunnable, 500)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val held = System.currentTimeMillis() - shutterDownTime
                    shutterHeld = false
                    handler.removeCallbacks(longPressRunnable)

                    if (longPressStartedFromPhoto) {
                        revertToPhotoAfterRecording = true
                        if (isRecording) {
                            stopRecording()
                        } else {
                            pendingRecordOnBind = false
                            handler.postDelayed({
                                if (revertToPhotoAfterRecording && !isRecording) {
                                    revertToPhotoAfterRecording = false
                                    setMode(false)
                                }
                            }, 500)
                        }
                        longPressStartedFromPhoto = false
                    } else if (held < 500) {
                        pulseButton(captureButton)
                        onShutterPressed()
                    }
                }
            }
            true
        }
        galleryButton.setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }

        flashButton.setOnClickListener { cycleFlash() }
        hdrButton.setOnClickListener { toggleHdr() }
        timerButton.setOnClickListener { cycleTimer() }
        switchCameraButton.setOnClickListener { switchCamera() }

        photoModeButton.setOnClickListener { setMode(false) }
        videoModeButton.setOnClickListener { setMode(true) }
    }

    // --- Mode Toggle ---

    private fun setMode(video: Boolean) {
        if (isRecording) return // Don't switch while recording
        isVideoMode = video

        photoModeButton.setColorFilter(if (!video) 0xFFFFD700.toInt() else 0xFFFFFFFF.toInt())
        videoModeButton.setColorFilter(if (video) 0xFFFFD700.toInt() else 0xFFFFFFFF.toInt())

        captureButton.setBackgroundResource(if (video) R.drawable.record_button else R.drawable.shutter_button)

        // Hide timer/HDR in video mode
        timerButton.visibility = if (video) View.GONE else View.VISIBLE
        hdrButton.visibility = if (video) View.GONE else View.VISIBLE

        startCamera()
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
        // Toggle torch for video mode
        if (isVideoMode) {
            camera?.cameraControl?.enableTorch(flashMode == ImageCapture.FLASH_MODE_ON)
        }
        updateFlashButton()
    }

    private fun updateFlashButton() {
        flashButton.text = when (flashMode) {
            ImageCapture.FLASH_MODE_AUTO -> if (isVideoMode) "LIGHT ON" else "FLASH AUTO"
            ImageCapture.FLASH_MODE_ON -> if (isVideoMode) "LIGHT ON" else "FLASH ON"
            else -> if (isVideoMode) "LIGHT OFF" else "FLASH OFF"
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
        startCamera()
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
        if (isRecording) return
        useFrontCamera = !useFrontCamera
        startCamera()
    }

    // --- Shutter / Countdown ---

    private var countdownActive = false

    private fun onShutterPressed() {
        if (isVideoMode) {
            toggleRecording()
            return
        }
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

    // --- Video Recording ---

    @SuppressLint("MissingPermission")
    private fun toggleRecording() {
        if (isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val vc = videoCapture ?: return

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), CAMERA_PERMISSION_CODE)
            return
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val videoFile = File(getQueueDir(), "VID_${timestamp}.mp4")

        val outputOptions = FileOutputOptions.Builder(videoFile).build()

        activeRecording = vc.output
            .prepareRecording(this, outputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        isRecording = true
                        recordingStartTime = System.currentTimeMillis()
                        longPressRecordingStartTime = recordingStartTime
                        if (revertToPhotoAfterRecording) {
                            // User already released — stop immediately
                            activeRecording?.stop()
                            return@start
                        }
                        handler.post {
                            captureButton.setBackgroundResource(R.drawable.stop_button)
                            recordingTimer.visibility = View.VISIBLE
                            updateRecordingTimer()
                        }
                    }
                    is VideoRecordEvent.Finalize -> {
                        isRecording = false
                        handler.post {
                            captureButton.setBackgroundResource(R.drawable.record_button)
                            recordingTimer.visibility = View.GONE
                            handler.removeCallbacks(timerUpdateRunnable)
                        }
                        val duration = System.currentTimeMillis() - recordingStartTime
                        if (event.hasError()) {
                            Log.e(TAG, "Video recording error: ${event.error}")
                            videoFile.delete()
                            handler.post { showStatus("Recording failed") }
                        } else if (revertToPhotoAfterRecording) {
                            revertToPhotoAfterRecording = false
                            if (duration < 1000) {
                                videoFile.delete()
                                handler.post {
                                    setMode(false)
                                    pendingPhotoOnBind = true
                                }
                            } else {
                                Log.i(TAG, "Video saved: ${videoFile.absolutePath}")
                                handler.post {
                                    showStatus("Video saved")
                                    updateGalleryThumbnail()
                                    setMode(false)
                                }
                            }
                        } else {
                            Log.i(TAG, "Video saved: ${videoFile.absolutePath}")
                            handler.post {
                                showStatus("Video saved")
                                updateGalleryThumbnail()
                            }
                        }
                    }
                }
            }
    }

    private fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
    }

    private val timerUpdateRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                updateRecordingTimer()
                handler.postDelayed(this, 1000)
            }
        }
    }

    private fun updateRecordingTimer() {
        val elapsed = (System.currentTimeMillis() - recordingStartTime) / 1000
        val minutes = elapsed / 60
        val seconds = elapsed % 60
        recordingTimer.text = String.format("● %02d:%02d", minutes, seconds)
        handler.removeCallbacks(timerUpdateRunnable)
        handler.postDelayed(timerUpdateRunnable, 1000)
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

            if (isVideoMode) {
                bindVideoCamera(provider, baseCameraSelector)
            } else {
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

                    bindPhotoCamera(provider, cameraSelector)
                }, ContextCompat.getMainExecutor(this))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindPhotoCamera(provider: ProcessCameraProvider, cameraSelector: CameraSelector) {
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(flashMode)
            .build()

        videoCapture = null

        provider.unbindAll()
        camera = provider.bindToLifecycle(this, cameraSelector, preview, imageCapture)

        if (exposureSlider.visibility == View.VISIBLE) {
            setupExposureSlider()
        }

        if (pendingPhotoOnBind) {
            pendingPhotoOnBind = false
            takePhoto()
        }
    }

    private fun bindVideoCamera(provider: ProcessCameraProvider, cameraSelector: CameraSelector) {
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
            .build()

        videoCapture = VideoCapture.withOutput(recorder)
        imageCapture = null

        provider.unbindAll()
        camera = provider.bindToLifecycle(this, cameraSelector, preview, videoCapture!!)

        if (exposureSlider.visibility == View.VISIBLE) {
            setupExposureSlider()
        }

        if (pendingRecordOnBind) {
            pendingRecordOnBind = false
            startRecording()
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
        val files = getQueueDir().listFiles { f -> f.extension == "jpg" || f.extension == "mp4" }
            ?.sortedByDescending { it.lastModified() }
        if (files != null && files.isNotEmpty()) {
            val first = files[0]
            if (first.extension == "jpg") {
                val bitmap = decodeBitmapWithRotation(first.absolutePath, sampleSize = 8)
                galleryButton.setImageBitmap(bitmap)
            } else {
                // For video, just show a placeholder color
                galleryButton.setImageDrawable(null)
                galleryButton.setBackgroundResource(R.drawable.gallery_button_bg)
            }
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
        val files = queueDir.listFiles { f -> f.extension == "jpg" || f.extension == "mp4" } ?: return

        for (file in files.sortedBy { it.lastModified() }) {
            if (isDestroyed) return
            if (uploadFile(file)) {
                file.delete()
                Log.i(TAG, "Uploaded and deleted: ${file.name}")
                handler.post { showStatus("Uploaded ${file.name}") }
            } else {
                Log.w(TAG, "Upload failed for ${file.name}, will retry later")
                handler.post {
                    val pending = queueDir.listFiles { f -> f.extension == "jpg" || f.extension == "mp4" }?.size ?: 0
                    showStatus("$pending file(s) queued for upload")
                }
                break
            }
        }
    }

    private fun uploadFile(file: File): Boolean {
        val mediaType = if (file.extension == "mp4") "video/mp4" else "image/jpeg"
        val fieldName = if (file.extension == "mp4") "video" else "photo"

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(fieldName, file.name, file.asRequestBody(mediaType.toMediaType()))
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

    private fun pulseButton(view: View) {
        val scaleDown = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, "scaleX", 1f, 0.85f),
                ObjectAnimator.ofFloat(view, "scaleY", 1f, 0.85f)
            )
            duration = 80
        }
        val scaleUp = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, "scaleX", 0.85f, 1f),
                ObjectAnimator.ofFloat(view, "scaleY", 0.85f, 1f)
            )
            duration = 150
            interpolator = DecelerateInterpolator()
        }
        AnimatorSet().apply {
            playSequentially(scaleDown, scaleUp)
            start()
        }
    }

    private fun showStatus(message: String) {
        statusText.text = message
        statusText.visibility = View.VISIBLE
        handler.postDelayed({ statusText.visibility = View.GONE }, 3000)
    }

    override fun onDestroy() {
        isDestroyed = true
        if (isRecording) {
            activeRecording?.stop()
        }
        orientationListener.disable()
        countdownTimer?.cancel()
        handler.removeCallbacks(uploadRunnable)
        handler.removeCallbacks(timerUpdateRunnable)
        handler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
        uploadExecutor.shutdown()
        super.onDestroy()
    }
}
