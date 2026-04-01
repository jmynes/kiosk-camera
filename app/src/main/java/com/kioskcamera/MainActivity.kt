package com.kioskcamera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
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
    private lateinit var statusText: TextView
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var uploadExecutor: ExecutorService
    private val handler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient()

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
        statusText = findViewById(R.id.statusText)

        cameraExecutor = Executors.newSingleThreadExecutor()
        uploadExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
        }

        captureButton.setOnClickListener { takePhoto() }

        // Start background upload loop for queued photos
        startUploadLoop()
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
            cameraProvider.bindToLifecycle(
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
                    }
                    // Trigger immediate upload attempt
                    uploadExecutor.execute { uploadPendingPhotos() }
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

    private fun getQueueDir(): File {
        val dir = File(filesDir, "upload_queue")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun uploadPendingPhotos() {
        val queueDir = getQueueDir()
        val files = queueDir.listFiles { f -> f.extension == "jpg" } ?: return

        for (file in files.sortedBy { it.lastModified() }) {
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
                break // Stop trying if server is down
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

    private fun startUploadLoop() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                uploadExecutor.execute { uploadPendingPhotos() }
                handler.postDelayed(this, UPLOAD_RETRY_INTERVAL_MS)
            }
        }, UPLOAD_RETRY_INTERVAL_MS)
    }

    private fun showStatus(message: String) {
        statusText.text = message
        statusText.visibility = View.VISIBLE
        handler.postDelayed({ statusText.visibility = View.GONE }, 3000)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        uploadExecutor.shutdown()
    }
}
