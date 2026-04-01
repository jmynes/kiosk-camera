package com.kioskcamera

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.ThumbnailUtils
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import java.util.concurrent.Executors
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class GalleryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var adapter: PhotoAdapter

    companion object {
        val thumbCache = LruCache<String, Bitmap>(100)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE

        setContentView(R.layout.activity_gallery)

        recyclerView = findViewById(R.id.photoGrid)
        emptyText = findViewById(R.id.emptyText)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        recyclerView.layoutManager = GridLayoutManager(this, 3)
        adapter = PhotoAdapter(
            getPhotos(),
            onTap = { file -> openViewer(file) },
            onLongPress = { file -> confirmDelete(file) }
        )
        recyclerView.adapter = adapter

        updateEmptyState()
    }

    override fun onResume() {
        super.onResume()
        refreshPhotos()
    }

    private fun openViewer(file: File) {
        val intent = Intent(this, PhotoViewerActivity::class.java)
        intent.putExtra("photo_path", file.absolutePath)
        startActivity(intent)
    }

    private fun getQueueDir(): File {
        val dir = File(filesDir, "upload_queue")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getPhotos(): MutableList<File> {
        return getQueueDir().listFiles { f -> f.extension == "jpg" || f.extension == "mp4" }
            ?.sortedByDescending { it.lastModified() }
            ?.toMutableList() ?: mutableListOf()
    }

    private fun refreshPhotos() {
        adapter.updatePhotos(getPhotos())
        updateEmptyState()
    }

    private fun updateEmptyState() {
        if (adapter.itemCount == 0) {
            emptyText.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyText.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete photo?")
            .setMessage(file.name)
            .setPositiveButton("Delete") { _, _ ->
                file.delete()
                refreshPhotos()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    class PhotoAdapter(
        private var photos: MutableList<File>,
        private val onTap: (File) -> Unit,
        private val onLongPress: (File) -> Unit
    ) : RecyclerView.Adapter<PhotoAdapter.ViewHolder>() {

        private val executor = Executors.newFixedThreadPool(3)
        private val mainHandler = Handler(Looper.getMainLooper())

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val imageView: ImageView = view.findViewById(R.id.thumbImage)
            val timeText: TextView = view.findViewById(R.id.timeText)
            var currentPath: String? = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_photo, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val file = photos[position]
            val path = file.absolutePath
            holder.currentPath = path

            // Show placeholder immediately
            holder.imageView.setImageDrawable(ColorDrawable(Color.DKGRAY))

            // Check cache
            val cached = thumbCache.get(path)
            if (cached != null) {
                holder.imageView.setImageBitmap(cached)
            } else {
                // Load async
                executor.execute {
                    val bitmap = if (file.extension == "mp4") {
                        ThumbnailUtils.createVideoThumbnail(file, Size(270, 270), null)
                    } else {
                        decodeBitmapWithRotation(file.absolutePath, sampleSize = 4)
                    }
                    if (bitmap != null) {
                        thumbCache.put(path, bitmap)
                        mainHandler.post {
                            if (holder.currentPath == path) {
                                holder.imageView.setImageBitmap(bitmap)
                            }
                        }
                    }
                }
            }

            val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)
            val label = if (file.extension == "mp4") "▶ " else ""
            holder.timeText.text = label + sdf.format(Date(file.lastModified()))

            holder.itemView.setOnClickListener { onTap(file) }
            holder.itemView.setOnLongClickListener {
                onLongPress(file)
                true
            }
        }

        override fun getItemCount() = photos.size

        fun updatePhotos(newPhotos: MutableList<File>) {
            photos = newPhotos
            notifyDataSetChanged()
        }
    }
}
