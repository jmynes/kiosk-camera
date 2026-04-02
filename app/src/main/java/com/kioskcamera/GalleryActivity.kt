package com.kioskcamera

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.os.Handler
import android.os.Looper
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
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
    private lateinit var normalBar: View
    private lateinit var selectionBar: View
    private lateinit var selectionCount: TextView

    private val selectedPositions = mutableSetOf<Int>()
    private var isSelectionMode = false
    private var isDragSelecting = false
    private var dragStartPos = -1
    private var dragCurrentPos = -1
    private var dragAddMode = true // true = selecting, false = deselecting

    override fun onCreate(savedInstanceState: Bundle?) {
        ThumbnailCache.init(cacheDir)
        super.onCreate(savedInstanceState)

        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE

        setContentView(R.layout.activity_gallery)

        recyclerView = findViewById(R.id.photoGrid)
        emptyText = findViewById(R.id.emptyText)
        normalBar = findViewById(R.id.normalBar)
        selectionBar = findViewById(R.id.selectionBar)
        selectionCount = findViewById(R.id.selectionCount)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.uploadButton).setOnClickListener { uploadAll() }
        findViewById<ImageButton>(R.id.clearAllButton).setOnClickListener { confirmClearAll() }
        findViewById<ImageButton>(R.id.cancelSelectionButton).setOnClickListener { exitSelectionMode() }
        findViewById<ImageButton>(R.id.deleteSelectedButton).setOnClickListener { deleteSelected() }

        recyclerView.layoutManager = GridLayoutManager(this, 3)
        adapter = PhotoAdapter(getPhotos())
        recyclerView.adapter = adapter

        setupDragSelect()
        updateEmptyState()
    }

    override fun onResume() {
        super.onResume()
        refreshPhotos()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragSelect() {
        recyclerView.setOnTouchListener { _, event ->
            if (!isDragSelecting) return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_MOVE -> {
                    val pos = getPositionUnder(event)
                    if (pos != RecyclerView.NO_POSITION && pos != dragCurrentPos) {
                        dragCurrentPos = pos
                        updateDragSelection()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDragSelecting = false
                }
            }
            true
        }
    }

    private fun getPositionUnder(event: MotionEvent): Int {
        val child = recyclerView.findChildViewUnder(event.x, event.y) ?: return RecyclerView.NO_POSITION
        return recyclerView.getChildAdapterPosition(child)
    }

    private fun updateDragSelection() {
        val start = minOf(dragStartPos, dragCurrentPos)
        val end = maxOf(dragStartPos, dragCurrentPos)
        for (i in 0 until adapter.itemCount) {
            val inRange = i in start..end
            if (inRange) {
                if (dragAddMode) selectedPositions.add(i) else selectedPositions.remove(i)
            }
        }
        adapter.notifyDataSetChanged()
        updateSelectionCount()
    }

    fun onItemLongPress(position: Int) {
        if (!isSelectionMode) {
            enterSelectionMode()
        }
        dragAddMode = position !in selectedPositions
        toggleSelection(position)
        isDragSelecting = true
        dragStartPos = position
        dragCurrentPos = position
    }

    fun onItemTap(position: Int) {
        if (isSelectionMode) {
            toggleSelection(position)
        } else {
            val file = adapter.getFile(position)
            openViewer(file)
        }
    }

    private fun toggleSelection(position: Int) {
        if (position in selectedPositions) {
            selectedPositions.remove(position)
        } else {
            selectedPositions.add(position)
        }
        adapter.notifyItemChanged(position)
        updateSelectionCount()

        if (selectedPositions.isEmpty()) {
            exitSelectionMode()
        }
    }

    private fun enterSelectionMode() {
        isSelectionMode = true
        normalBar.visibility = View.GONE
        selectionBar.visibility = View.VISIBLE
        updateSelectionCount()
        adapter.notifyDataSetChanged()
    }

    fun exitSelectionMode() {
        isSelectionMode = false
        isDragSelecting = false
        selectedPositions.clear()
        normalBar.visibility = View.VISIBLE
        selectionBar.visibility = View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun updateSelectionCount() {
        selectionCount.text = "${selectedPositions.size} selected"
    }

    private fun deleteSelected() {
        val count = selectedPositions.size
        if (count == 0) return

        AlertDialog.Builder(this)
            .setTitle("Delete $count item${if (count > 1) "s" else ""}?")
            .setPositiveButton("Delete") { _, _ ->
                val files = selectedPositions.sortedDescending().map { adapter.getFile(it) }
                files.forEach { it.delete() }
                exitSelectionMode()
                refreshPhotos()
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    private fun uploadAll() {
        val count = UploadManager.getPendingCount(this)
        if (count == 0) {
            Toast.makeText(this, "Nothing to upload", Toast.LENGTH_SHORT).show()
            return
        }

        val uploadButton = findViewById<ImageButton>(R.id.uploadButton)
        uploadButton.isEnabled = false
        uploadButton.alpha = 0.5f
        Toast.makeText(this, "Uploading $count file(s)...", Toast.LENGTH_SHORT).show()

        val mainHandler = Handler(Looper.getMainLooper())
        UploadManager.uploadAll(this,
            onProgress = { msg ->
                mainHandler.post {
                    findViewById<TextView>(R.id.titleText).text = msg
                }
            },
            onComplete = { uploaded, failed ->
                mainHandler.post {
                    uploadButton.isEnabled = true
                    uploadButton.alpha = 1f
                    findViewById<TextView>(R.id.titleText).text = "Queued Media"
                    val msg = if (failed == 0) "Uploaded $uploaded file(s)"
                              else "Uploaded $uploaded, failed $failed"
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    refreshPhotos()
                }
            }
        )
    }

    private fun confirmClearAll() {
        val count = adapter.itemCount
        if (count == 0) return

        AlertDialog.Builder(this)
            .setTitle("Delete all $count items?")
            .setMessage("This will remove all queued photos and videos.")
            .setPositiveButton("Delete All") { _, _ ->
                getQueueDir().listFiles()?.forEach { it.delete() }
                ThumbnailCache.clearAll()
                refreshPhotos()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onBackPressed() {
        if (isSelectionMode) {
            exitSelectionMode()
        } else {
            super.onBackPressed()
        }
    }

    inner class PhotoAdapter(
        private var photos: MutableList<File>
    ) : RecyclerView.Adapter<PhotoAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val imageView: ImageView = view.findViewById(R.id.thumbImage)
            val timeText: TextView = view.findViewById(R.id.timeText)
            val selectionOverlay: View = view.findViewById(R.id.selectionOverlay)
            val checkMark: ImageView = view.findViewById(R.id.checkMark)
            var currentPath: String? = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_photo, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val file = photos[position]
            val path = file.absolutePath
            holder.currentPath = path

            holder.imageView.setImageDrawable(ColorDrawable(Color.DKGRAY))
            ThumbnailCache.loadThumbnail(file, holder.imageView) { holder.currentPath }

            val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)
            val label = if (file.extension == "mp4") "▶ " else ""
            holder.timeText.text = label + sdf.format(Date(file.lastModified()))

            val selected = position in selectedPositions
            holder.selectionOverlay.visibility = if (selected) View.VISIBLE else View.GONE
            holder.checkMark.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
            holder.checkMark.setImageResource(
                if (selected) R.drawable.ic_select_checked else R.drawable.ic_select_unchecked
            )

            holder.itemView.setOnClickListener {
                onItemTap(holder.bindingAdapterPosition)
            }
            holder.itemView.setOnLongClickListener {
                onItemLongPress(holder.bindingAdapterPosition)
                true
            }
        }

        override fun getItemCount() = photos.size

        fun getFile(position: Int) = photos[position]

        fun updatePhotos(newPhotos: MutableList<File>) {
            photos = newPhotos
            notifyDataSetChanged()
        }
    }
}
