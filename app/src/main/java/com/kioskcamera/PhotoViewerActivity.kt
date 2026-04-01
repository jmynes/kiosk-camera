package com.kioskcamera

import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class PhotoViewerActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var infoText: TextView
    private lateinit var counterText: TextView
    private var photos: List<File> = emptyList()
    private var currentIndex = 0

    private val matrix = Matrix()
    private var scaleFactor = 1f
    private var translateX = 0f
    private var translateY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE

        setContentView(R.layout.activity_photo_viewer)

        imageView = findViewById(R.id.fullImage)
        infoText = findViewById(R.id.photoInfo)
        counterText = findViewById(R.id.photoCounter)

        val startFile = intent.getStringExtra("photo_path") ?: run { finish(); return }

        loadPhotos(startFile)
        setupGestures()
        setupControls()
    }

    private fun loadPhotos(startPath: String) {
        val queueDir = File(filesDir, "upload_queue")
        photos = queueDir.listFiles { f -> f.extension == "jpg" }
            ?.sortedByDescending { it.lastModified() }
            ?.toList() ?: emptyList()

        currentIndex = photos.indexOfFirst { it.absolutePath == startPath }.coerceAtLeast(0)
        showCurrentPhoto()
    }

    private fun showCurrentPhoto() {
        if (photos.isEmpty()) { finish(); return }

        val file = photos[currentIndex]
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        imageView.setImageBitmap(bitmap)

        // Reset zoom
        scaleFactor = 1f
        translateX = 0f
        translateY = 0f
        imageView.scaleType = ImageView.ScaleType.FIT_CENTER

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val size = file.length() / 1024
        infoText.text = "${sdf.format(Date(file.lastModified()))}  •  ${size}KB"
        counterText.text = "${currentIndex + 1} / ${photos.size}"
    }

    private fun setupControls() {
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        findViewById<ImageButton>(R.id.deleteButton).setOnClickListener {
            confirmDelete()
        }

        findViewById<ImageButton>(R.id.prevButton).setOnClickListener {
            if (currentIndex > 0) {
                currentIndex--
                showCurrentPhoto()
            }
        }

        findViewById<ImageButton>(R.id.nextButton).setOnClickListener {
            if (currentIndex < photos.size - 1) {
                currentIndex++
                showCurrentPhoto()
            }
        }
    }

    private fun confirmDelete() {
        if (photos.isEmpty()) return
        val file = photos[currentIndex]

        AlertDialog.Builder(this)
            .setTitle("Delete photo?")
            .setMessage(file.name)
            .setPositiveButton("Delete") { _, _ ->
                file.delete()
                val newPhotos = photos.toMutableList()
                newPhotos.removeAt(currentIndex)
                photos = newPhotos
                if (photos.isEmpty()) {
                    finish()
                } else {
                    currentIndex = currentIndex.coerceAtMost(photos.size - 1)
                    showCurrentPhoto()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupGestures() {
        val scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    scaleFactor *= detector.scaleFactor
                    scaleFactor = scaleFactor.coerceIn(0.5f, 5f)
                    imageView.scaleX = scaleFactor
                    imageView.scaleY = scaleFactor
                    return true
                }
            })

        val gestureDetector = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    // Reset zoom
                    scaleFactor = 1f
                    imageView.scaleX = 1f
                    imageView.scaleY = 1f
                    imageView.translationX = 0f
                    imageView.translationY = 0f
                    return true
                }

                override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                    if (scaleFactor > 1.1f) return false // Don't swipe when zoomed
                    val dx = (e2.x - (e1?.x ?: e2.x))
                    if (dx > 150 && currentIndex > 0) {
                        currentIndex--
                        showCurrentPhoto()
                        return true
                    } else if (dx < -150 && currentIndex < photos.size - 1) {
                        currentIndex++
                        showCurrentPhoto()
                        return true
                    }
                    return false
                }
            })

        imageView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            true
        }
    }
}
