package com.kioskcamera

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class PhotoViewerActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var infoText: TextView
    private lateinit var counterText: TextView
    private var photos: MutableList<File> = mutableListOf()

    private val currentIndex: Int get() = pager.currentItem

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE

        setContentView(R.layout.activity_photo_viewer)

        pager = findViewById(R.id.photoPager)
        infoText = findViewById(R.id.photoInfo)
        counterText = findViewById(R.id.photoCounter)

        val startFile = intent.getStringExtra("photo_path") ?: run { finish(); return }

        loadPhotos(startFile)
        setupControls()
    }

    private fun loadPhotos(startPath: String) {
        val queueDir = File(filesDir, "upload_queue")
        photos = queueDir.listFiles { f -> f.extension == "jpg" }
            ?.sortedByDescending { it.lastModified() }
            ?.toMutableList() ?: mutableListOf()

        val startIndex = photos.indexOfFirst { it.absolutePath == startPath }.coerceAtLeast(0)

        pager.adapter = PhotoPagerAdapter(photos)
        pager.setCurrentItem(startIndex, false)
        updateInfo(startIndex)

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateInfo(position)
                // Reset zoom on the previous page's ZoomableImageView
                resetZoomOnAllPages()
            }
        })
    }

    private fun resetZoomOnAllPages() {
        val rv = pager.getChildAt(0) as? RecyclerView ?: return
        for (i in 0 until rv.childCount) {
            val ziv = rv.getChildAt(i)?.findViewById<ZoomableImageView>(R.id.zoomableImage)
            ziv?.resetTransform()
        }
    }

    private fun updateInfo(position: Int) {
        if (position < 0 || position >= photos.size) return
        val file = photos[position]
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val size = file.length() / 1024
        infoText.text = "${sdf.format(Date(file.lastModified()))}  •  ${size}KB"
        counterText.text = "${position + 1} / ${photos.size}"
    }

    private fun setupControls() {
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.deleteButton).setOnClickListener { confirmDelete() }
    }

    private fun confirmDelete() {
        if (photos.isEmpty()) return
        val idx = currentIndex
        val file = photos[idx]

        AlertDialog.Builder(this)
            .setTitle("Delete photo?")
            .setMessage(file.name)
            .setPositiveButton("Delete") { _, _ ->
                file.delete()
                photos.removeAt(idx)
                if (photos.isEmpty()) {
                    finish()
                } else {
                    pager.adapter?.notifyItemRemoved(idx)
                    updateInfo(pager.currentItem.coerceAtMost(photos.size - 1))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    class PhotoPagerAdapter(
        private val photos: List<File>
    ) : RecyclerView.Adapter<PhotoPagerAdapter.PhotoVH>() {

        class PhotoVH(view: View) : RecyclerView.ViewHolder(view) {
            val imageView: ZoomableImageView = view.findViewById(R.id.zoomableImage)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoVH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_photo_page, parent, false)
            return PhotoVH(view)
        }

        override fun onBindViewHolder(holder: PhotoVH, position: Int) {
            val bitmap = decodeBitmapWithRotation(photos[position].absolutePath)
            holder.imageView.resetTransform()
            holder.imageView.setImageBitmap(bitmap)
        }

        override fun getItemCount() = photos.size
    }
}
