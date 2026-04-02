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
    private lateinit var tabQueue: TextView
    private lateinit var tabUploaded: TextView
    private lateinit var titleText: TextView
    private lateinit var uploadButton: ImageButton
    private lateinit var clearAllButton: ImageButton
    private lateinit var projectFab: TextView

    private val selectedPositions = mutableSetOf<Int>()
    private var isSelectionMode = false
    private var isDragSelecting = false
    private var dragStartPos = -1
    private var dragCurrentPos = -1
    private var dragAddMode = true

    private var showingQueue = true // true = queue tab, false = uploaded tab

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
        tabQueue = findViewById(R.id.tabQueue)
        tabUploaded = findViewById(R.id.tabUploaded)
        titleText = findViewById(R.id.titleText)
        uploadButton = findViewById(R.id.uploadButton)
        clearAllButton = findViewById(R.id.clearAllButton)
        projectFab = findViewById(R.id.projectFab)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
        uploadButton.setOnClickListener { onUploadPressed() }
        projectFab.setOnClickListener { showProjectPicker() }
        clearAllButton.setOnClickListener { confirmClearAll() }
        findViewById<ImageButton>(R.id.cancelSelectionButton).setOnClickListener { exitSelectionMode() }
        findViewById<ImageButton>(R.id.deleteSelectedButton).setOnClickListener { deleteSelected() }

        tabQueue.setOnClickListener { switchTab(true) }
        tabUploaded.setOnClickListener { switchTab(false) }

        recyclerView.layoutManager = GridLayoutManager(this, 3)
        adapter = PhotoAdapter(getFiles())
        recyclerView.adapter = adapter

        setupDragSelect()
        updateTabState()
        updateProjectFab()
        updateEmptyState()
    }

    override fun onResume() {
        super.onResume()
        updateProjectFab()
        refreshPhotos()
    }

    private fun switchTab(queue: Boolean) {
        if (showingQueue == queue) return
        if (isSelectionMode) exitSelectionMode()
        showingQueue = queue
        updateTabState()
        refreshPhotos()
    }

    private fun updateTabState() {
        tabQueue.setTextColor(if (showingQueue) 0xFFFFD700.toInt() else 0xFF888888.toInt())
        tabQueue.setTypeface(null, if (showingQueue) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        tabUploaded.setTextColor(if (!showingQueue) 0xFFFFD700.toInt() else 0xFF888888.toInt())
        tabUploaded.setTypeface(null, if (!showingQueue) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)

        titleText.text = if (showingQueue) "Queue" else "Uploaded"

        // Show upload FAB only on queue tab
        uploadButton.visibility = if (showingQueue) View.VISIBLE else View.GONE
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

        val message = if (!showingQueue) "This only deletes the local cache. To delete from the server, use a computer." else null
        AlertDialog.Builder(this)
            .setTitle("Delete $count item${if (count > 1) "s" else ""}?")
            .apply { if (message != null) setMessage(message) }
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
        // Tell viewer which directory to scan
        intent.putExtra("source_dir", if (showingQueue) "queue" else "uploaded")
        startActivity(intent)
    }

    private fun getFiles(): MutableList<File> {
        val project = ProjectManager.getActiveProject(this)
        return if (showingQueue) {
            UploadManager.getFilesForProject(this, project).toMutableList()
        } else {
            UploadManager.getUploadedFiles(this).toMutableList()
        }
    }

    private fun refreshPhotos() {
        adapter.updatePhotos(getFiles())
        updateEmptyState()
    }

    private fun updateEmptyState() {
        if (adapter.itemCount == 0) {
            emptyText.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            emptyText.text = if (showingQueue) "No media queued" else "No uploaded media"
        } else {
            emptyText.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }

    private fun onUploadPressed() {
        val project = ProjectManager.getActiveProject(this)
        if (project == null) {
            Toast.makeText(this, "Select a project first", Toast.LENGTH_LONG).show()
            return
        }

        val filesToUpload: List<File>
        if (isSelectionMode && selectedPositions.isNotEmpty()) {
            filesToUpload = selectedPositions.sorted().map { adapter.getFile(it) }
            exitSelectionMode()
        } else {
            filesToUpload = UploadManager.getFilesForProject(this, project)
        }

        if (filesToUpload.isEmpty()) {
            Toast.makeText(this, "Nothing to upload", Toast.LENGTH_SHORT).show()
            return
        }

        val count = filesToUpload.size
        AlertDialog.Builder(this)
            .setTitle("Upload $count file${if (count > 1) "s" else ""} to \"$project\"?")
            .setMessage("Uploads are one-way. To delete from the server, use a computer.")
            .setPositiveButton("Upload") { _, _ ->
                doUpload(filesToUpload, project)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doUpload(files: List<File>, project: String) {
        uploadButton.isEnabled = false
        uploadButton.alpha = 0.5f

        val mainHandler = Handler(Looper.getMainLooper())
        val onProgress: (String) -> Unit = { msg ->
            mainHandler.post { titleText.text = msg }
        }
        val onComplete: (Int, Int) -> Unit = { uploaded, failed ->
            mainHandler.post {
                uploadButton.isEnabled = true
                uploadButton.alpha = 1f
                updateTabState()
                val msg = if (failed == 0) "Uploaded $uploaded to \"$project\""
                          else "Uploaded $uploaded, failed $failed"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                refreshPhotos()
            }
        }

        UploadManager.uploadFiles(this, files, project, onProgress, onComplete)
    }

    private fun updateProjectFab() {
        val project = ProjectManager.getActiveProject(this)
        val footer = findViewById<TextView>(R.id.projectFooter)
        if (project != null) {
            projectFab.text = project
            projectFab.setTextColor(0xFFFFD700.toInt())
            footer.text = "Project: $project"
            footer.visibility = View.VISIBLE
        } else {
            projectFab.text = "PRJ"
            projectFab.setTextColor(0xFFFFFFFF.toInt())
            footer.visibility = View.GONE
        }
    }

    private fun showProjectPicker() {
        ProjectPickerDialog.show(this) { project ->
            ProjectManager.setActiveProject(this, project)
            updateProjectFab()
            refreshPhotos()
        }
    }

    private fun confirmClearAll() {
        val count = adapter.itemCount
        if (count == 0) return

        val message = if (showingQueue) {
            "This will remove all queued photos and videos."
        } else {
            "This only deletes the local cache. To delete from the server, use a computer."
        }
        AlertDialog.Builder(this)
            .setTitle("Delete all $count items?")
            .setMessage(message)
            .setPositiveButton("Delete All") { _, _ ->
                if (showingQueue) {
                    UploadManager.getQueueDir(this).listFiles()?.forEach { it.delete() }
                } else {
                    UploadManager.clearUploadedCache(this)
                }
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
