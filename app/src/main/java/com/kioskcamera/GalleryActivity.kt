package com.kioskcamera

import android.content.Intent
import android.os.Bundle
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
        return getQueueDir().listFiles { f -> f.extension == "jpg" }
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

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val imageView: ImageView = view.findViewById(R.id.thumbImage)
            val timeText: TextView = view.findViewById(R.id.timeText)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_photo, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val file = photos[position]

            val bitmap = decodeBitmapWithRotation(file.absolutePath, sampleSize = 4)
            holder.imageView.setImageBitmap(bitmap)

            val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)
            holder.timeText.text = sdf.format(Date(file.lastModified()))

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
