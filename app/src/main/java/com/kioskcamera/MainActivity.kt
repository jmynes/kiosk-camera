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
import android.view.HapticFeedbackConstants
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
    private lateinit var projectButton: TextView
    private lateinit var projectFooter: TextView
    private lateinit var statusText: TextView
    private lateinit var zoomText: TextView
    private lateinit var focusIndicator: FocusIndicatorView
    private lateinit var flashButton: ImageButton
    private lateinit var hdrButton: ImageButton
    private lateinit var timerButton: ImageButton
    private lateinit var switchCameraButton: ImageButton
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
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var httpClient: OkHttpClient
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

    private val uploadUrl = BuildConfig.UPLOAD_URL

    private fun createHttpClient(): OkHttpClient {
        val url = java.net.URL(BuildConfig.UPLOAD_URL)
        val builder = OkHttpClient.Builder()

        try {
            val cf = java.security.cert.CertificateFactory.getInstance("X.509")
            val caInput = resources.openRawResource(R.raw.ca_cert)
            val ca = cf.generateCertificate(caInput)
            caInput.close()

            val ks = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType())
            ks.load(null, null)
            ks.setCertificateEntry("ca", ca)

            val tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
            )
            tmf.init(ks)

            val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
            sslContext.init(null, tmf.trustManagers, null)
            builder.sslSocketFactory(sslContext.socketFactory,
                tmf.trustManagers[0] as javax.net.ssl.X509TrustManager)

            // Hostname verifier for IP-based certs
            builder.hostnameVerifier { _, session ->
                try {
                    val certs = session.peerCertificates
                    certs.isNotEmpty()
                } catch (e: Exception) { false }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set up SSL trust", e)
        }

        builder.certificatePinner(
            okhttp3.CertificatePinner.Builder()
                .add(url.host, BuildConfig.CERT_PIN)
                .build()
        )

        return builder.build()
    }

    companion object {
        private const val TAG = "KioskCamera"
        private const val CAMERA_PERMISSION_CODE = 100
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
        httpClient = createHttpClient()
        ScpUploader.init(this)
        initSounds()

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

        UploadManager.init(httpClient)
    }

    override fun onResume() {
        super.onResume()
        activeProject = ProjectManager.getActiveProject(this)
        updateProjectButton()
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
            haptic(it)
            startActivity(Intent(this, GalleryActivity::class.java))
        }

        projectButton = findViewById(R.id.projectButton)
        projectButton.setOnClickListener { haptic(it); showProjectPicker() }
        projectFooter = findViewById(R.id.projectFooter)
        activeProject = ProjectManager.getActiveProject(this)
        updateProjectButton()

        flashButton.setOnClickListener { haptic(it); cycleFlash() }
        hdrButton.setOnClickListener { haptic(it); toggleHdr() }
        timerButton.setOnClickListener { haptic(it); cycleTimer() }
        switchCameraButton.setOnClickListener { haptic(it); switchCamera() }

        photoModeButton.setOnClickListener { haptic(it); setMode(false) }
        videoModeButton.setOnClickListener { haptic(it); setMode(true) }

        flashButton.tooltipText = "Flash"
        hdrButton.tooltipText = "Night mode"
        timerButton.tooltipText = "Timer"
        switchCameraButton.tooltipText = "Switch camera"
        photoModeButton.tooltipText = "Photo"
        videoModeButton.tooltipText = "Video"
    }

    // --- Mode Toggle ---

    private fun setMode(video: Boolean) {
        if (isRecording) return // Don't switch while recording
        isVideoMode = video

        photoModeButton.setColorFilter(if (!video) 0xFFFFD700.toInt() else 0xFFFFFFFF.toInt())
        videoModeButton.setColorFilter(if (video) 0xFFFFD700.toInt() else 0xFFFFFFFF.toInt())

        captureButton.setBackgroundResource(if (video) R.drawable.record_button else R.drawable.shutter_button)

        // Hide timer/HDR in video mode
        // Night mode only works with ImageCapture, not VideoCapture
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
                videoCapture?.targetRotation = deviceRotation
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
        if (isVideoMode) {
            camera?.cameraControl?.enableTorch(flashMode == ImageCapture.FLASH_MODE_ON)
        }
        updateFlashButton()
        showStatus(when (flashMode) {
            ImageCapture.FLASH_MODE_AUTO -> "Flash: Auto"
            ImageCapture.FLASH_MODE_ON -> if (isVideoMode) "Light: On" else "Flash: On"
            else -> if (isVideoMode) "Light: Off" else "Flash: Off"
        })
    }

    private fun updateFlashButton() {
        flashButton.setImageResource(when (flashMode) {
            ImageCapture.FLASH_MODE_AUTO -> R.drawable.ic_flash_auto
            ImageCapture.FLASH_MODE_ON -> R.drawable.ic_flash_on
            else -> R.drawable.ic_flash_off
        })
        flashButton.setColorFilter(
            if (flashMode == ImageCapture.FLASH_MODE_OFF) 0xFFFFFFFF.toInt() else 0xFFFFD700.toInt()
        )
    }

    // --- HDR ---

    private fun toggleHdr() {
        if (!hdrAvailable) {
            showStatus("Night mode not available")
            return
        }
        hdrEnabled = !hdrEnabled
        updateHdrButton()
        showStatus(if (hdrEnabled) "Night mode: On" else "Night mode: Off")
        startCamera()
    }

    private fun updateHdrButton() {
        hdrButton.setColorFilter(
            when {
                !hdrAvailable -> 0xFF555555.toInt()
                hdrEnabled -> 0xFFFFD700.toInt()
                else -> 0xFFFFFFFF.toInt()
            }
        )
    }

    // --- Timer ---

    private fun cycleTimer() {
        timerSeconds = when (timerSeconds) {
            0 -> 3
            3 -> 10
            else -> 0
        }
        timerButton.setImageResource(when (timerSeconds) {
            3 -> R.drawable.ic_timer_3
            10 -> R.drawable.ic_timer_10
            else -> R.drawable.ic_timer_off
        })
        timerButton.setColorFilter(when (timerSeconds) {
            3 -> 0xFFFFD700.toInt()   // gold
            10 -> 0xFFFF6B00.toInt()  // orange
            else -> 0xFFFFFFFF.toInt() // white
        })
        showStatus(if (timerSeconds == 0) "Timer: Off" else "Timer: ${timerSeconds}s")
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
        if (countdownActive) {
            cancelCountdown()
            return
        }
        if (isVideoMode) {
            if (isRecording) {
                stopRecording()
            } else if (timerSeconds > 0) {
                startCountdown()
            } else {
                startRecording()
            }
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
                if (isVideoMode) {
                    startRecording()
                } else {
                    takePhoto()
                }
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
        playShutter()
        val vc = videoCapture ?: return

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), CAMERA_PERMISSION_CODE)
            return
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
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
        playShutter()
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
                        baseCameraSelector, ExtensionMode.NIGHT
                    )
                    updateHdrButton()

                    val cameraSelector = if (hdrEnabled && hdrAvailable) {
                        extensionsManager.getExtensionEnabledCameraSelector(
                            baseCameraSelector, ExtensionMode.NIGHT
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
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
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

    private fun flashScreen() {
        val flash = findViewById<View>(R.id.shutterFlash)
        flash.alpha = 0.7f
        flash.visibility = View.VISIBLE
        flash.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction { flash.visibility = View.GONE }
            .start()
    }

    private val saveExecutor = Executors.newFixedThreadPool(4)
    private var captureCount = java.util.concurrent.atomic.AtomicInteger(0)
    private lateinit var soundPool: android.media.SoundPool
    private var shutterSoundId = 0
    private var videoStartSoundId = 0
    private var videoStopSoundId = 0

    private fun initSounds() {
        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_ALARM)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = android.media.SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(attrs)
            .build()
        shutterSoundId = soundPool.load(this, R.raw.shutter_click, 1)
    }

    private fun playShutter() {
        soundPool.play(shutterSoundId, 1f, 1f, 1, 0, 1f)
    }

    private fun takePhoto() {
        playShutter()
        flashScreen()
        val imageCapture = imageCapture ?: return

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val photoFile = File(getQueueDir(), "IMG_${timestamp}.jpg")

        captureCount.incrementAndGet()

        imageCapture.takePicture(cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                    // Save on separate thread pool so CameraX can capture next immediately
                    saveExecutor.execute {
                        try {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            image.close()

                            photoFile.writeBytes(bytes)
                            Log.i(TAG, "Photo saved: ${photoFile.absolutePath}")

                            val remaining = captureCount.decrementAndGet()
                            handler.post {
                                if (remaining > 0) {
                                    showStatus("Photo captured ($remaining saving)")
                                } else {
                                    showStatus("Photo captured")
                                }
                                updateGalleryThumbnail()
                            }
                        } catch (e: Exception) {
                            image.close()
                            Log.e(TAG, "Photo save failed: ${e.message}")
                            captureCount.decrementAndGet()
                            handler.post { showStatus("Save failed") }
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}")
                    captureCount.decrementAndGet()
                    handler.post { showStatus("Capture failed") }
                }
            }
        )
    }

    // --- Gallery Thumbnail ---

    private var lastThumbnailPath: String? = null

    private fun updateGalleryThumbnail() {
        val files = getQueueDir().listFiles { f -> f.extension == "jpg" || f.extension == "mp4" }
            ?.sortedByDescending { it.lastModified() }
        if (files != null && files.isNotEmpty()) {
            val first = files[0]
            val isNew = first.absolutePath != lastThumbnailPath
            lastThumbnailPath = first.absolutePath

            if (first.extension == "jpg") {
                val bitmap = decodeBitmapWithRotation(first.absolutePath, sampleSize = 8)
                if (isNew) {
                    galleryButton.translationY = -galleryButton.height.toFloat()
                    galleryButton.setImageBitmap(bitmap)
                    galleryButton.animate()
                        .translationY(0f)
                        .setDuration(200)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                } else {
                    galleryButton.setImageBitmap(bitmap)
                }
            } else {
                galleryButton.setImageDrawable(null)
                galleryButton.setBackgroundResource(R.drawable.gallery_button_bg)
            }
        } else {
            lastThumbnailPath = null
            galleryButton.setImageDrawable(null)
        }
    }

    // --- Queue Directory ---

    private fun getQueueDir(): File {
        val project = activeProject
        val dir = if (project != null) {
            File(filesDir, "upload_queue/$project")
        } else {
            File(filesDir, "upload_queue/_unassigned")
        }
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun haptic(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun pulseButton(view: View) {
        haptic(view)
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

    // --- Project Selection ---

    private var activeProject: String? = null

    private fun updateProjectButton() {
        val project = activeProject
        val hasProjects = ProjectManager.getProjects(this).isNotEmpty()
        if (project != null) {
            projectButton.text = project
            projectButton.setTextColor(0xFFFFD700.toInt())
            projectFooter.text = "Project: $project"
            projectFooter.visibility = View.VISIBLE
        } else if (!hasProjects) {
            projectButton.text = "NEW"
            projectButton.setTextColor(0xFF448AFF.toInt())
            projectFooter.text = "No project — tap to create"
            projectFooter.visibility = View.VISIBLE
            projectFooter.setTextColor(0xFF888888.toInt())
        } else {
            projectButton.text = "PRJ"
            projectButton.setTextColor(0xFFFFFFFF.toInt())
            projectFooter.text = "No project selected"
            projectFooter.visibility = View.VISIBLE
            projectFooter.setTextColor(0xFF888888.toInt())
        }
        // Restore gold for when project is set
        if (project != null) {
            projectFooter.setTextColor(0xFFFFD700.toInt())
        }
    }

    private fun showProjectPicker() {
        ProjectPickerDialog.show(this, onProjectSelected = { project ->
            activeProject = project
            ProjectManager.setActiveProject(this, project)
            updateProjectButton()
            updateGalleryThumbnail()
            showStatus("Project: $project")
        }, onDismiss = {
            // Sync in case manage dialog changed active project
            activeProject = ProjectManager.getActiveProject(this)
            updateProjectButton()
            updateGalleryThumbnail()
        })
    }

    private fun showStatus(message: String) {
        statusText.text = message
        statusText.visibility = View.VISIBLE
        handler.postDelayed({ statusText.visibility = View.GONE }, 3000)
    }

    private var volumeDownTime = 0L
    private var volumeHeld = false
    private val volumeLongPressRunnable = Runnable {
        if (volumeHeld && !isVideoMode && !isRecording) {
            longPressStartedFromPhoto = true
            pendingRecordOnBind = true
            setMode(true)
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event?.repeatCount == 0) {
                volumeDownTime = System.currentTimeMillis()
                volumeHeld = true
                longPressStartedFromPhoto = false
                if (!isVideoMode && !isRecording) {
                    handler.postDelayed(volumeLongPressRunnable, 500)
                }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            val held = System.currentTimeMillis() - volumeDownTime
            volumeHeld = false
            handler.removeCallbacks(volumeLongPressRunnable)

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
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        isDestroyed = true
        if (isRecording) {
            activeRecording?.stop()
        }
        orientationListener.disable()
        countdownTimer?.cancel()
        handler.removeCallbacks(timerUpdateRunnable)
        handler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
        saveExecutor.shutdown()
        super.onDestroy()
    }
}
