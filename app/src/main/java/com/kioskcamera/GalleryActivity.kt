package com.kioskcamera

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.MotionEvent
import android.view.HapticFeedbackConstants
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
    private lateinit var adapter: GalleryAdapter
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

        val haptic = { v: View -> v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { haptic(it); finish() }
        uploadButton.setOnClickListener { haptic(it); onUploadPressed() }
        projectFab.setOnClickListener { haptic(it); showProjectPicker() }
        clearAllButton.setOnClickListener { haptic(it); confirmClearAll() }
        findViewById<ImageButton>(R.id.cancelSelectionButton).setOnClickListener { haptic(it); exitSelectionMode() }
        findViewById<ImageButton>(R.id.deleteSelectedButton).setOnClickListener { haptic(it); deleteSelected() }

        tabQueue.setOnClickListener { haptic(it); switchTab(true) }
        tabUploaded.setOnClickListener { haptic(it); switchTab(false) }

        val gridLayoutManager = GridLayoutManager(this, 3)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (adapter.isHeader(position)) 3 else 1
            }
        }
        recyclerView.layoutManager = gridLayoutManager
        adapter = GalleryAdapter(mutableListOf())
        recyclerView.adapter = adapter

        setupDragSelect()
        updateTabState()
        updateProjectFab()
        refreshPhotos()
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

        titleText.text = if (showingQueue) "Queue" else "History"

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
        if (adapter.isHeader(position)) return
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
        if (adapter.isHeader(position)) return
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

        val action = if (showingQueue) "Delete" else "Clear"
        val message = if (!showingQueue) "This only clears the local history. To delete from the server, use a computer." else null
        AlertDialog.Builder(this)
            .setTitle("$action $count item${if (count > 1) "s" else ""}?")
            .apply { if (message != null) setMessage(message) }
            .setPositiveButton(action) { _, _ ->
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

    // Items can be files or batch headers (for uploaded tab)
    sealed class GalleryItem {
        data class Photo(val file: File) : GalleryItem()
        data class BatchHeader(val project: String, val timestamp: String, val count: Int) : GalleryItem()
    }

    private fun getItems(): MutableList<GalleryItem> {
        val project = ProjectManager.getActiveProject(this)
        return if (showingQueue) {
            UploadManager.getFilesForProject(this, project)
                .map { GalleryItem.Photo(it) }.toMutableList()
        } else {
            val batches = UploadManager.getUploadBatches(this)
            val items = mutableListOf<GalleryItem>()
            for (batch in batches) {
                items.add(GalleryItem.BatchHeader(batch.project, batch.timestamp, batch.files.size))
                batch.files.forEach { items.add(GalleryItem.Photo(it)) }
            }
            // Legacy flat files
            val legacyFiles = UploadManager.getUploadedDir(this)
                .listFiles { f -> f.isFile && (f.extension == "jpg" || f.extension == "mp4") }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
            if (legacyFiles.isNotEmpty()) {
                items.add(GalleryItem.BatchHeader("Unsorted", "", legacyFiles.size))
                legacyFiles.forEach { items.add(GalleryItem.Photo(it)) }
            }
            items
        }
    }

    private val ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun refreshPhotos() {
        ioExecutor.execute {
            val items = getItems()
            runOnUiThread {
                adapter.updateItems(items)
                updateEmptyState()
                updateProjectFab()
            }
        }
    }

    private fun updateEmptyState() {
        if (adapter.itemCount == 0) {
            emptyText.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            emptyText.text = if (showingQueue) "No media queued" else "No upload history"
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
            val count = if (showingQueue) {
                UploadManager.getFilesForProject(this, project).size
            } else {
                UploadManager.getUploadedFiles(this).size
            }
            val label = if (showingQueue) "queued" else "in history"
            footer.text = "Project: $project  •  $count $label"
            footer.visibility = View.VISIBLE
        } else {
            projectFab.text = "PRJ"
            projectFab.setTextColor(0xFFFFFFFF.toInt())
            footer.visibility = View.GONE
        }
    }

    private fun showProjectPicker() {
        ProjectPickerDialog.show(this, onProjectSelected = { project ->
            ProjectManager.setActiveProject(this, project)
            updateProjectFab()
            refreshPhotos()
        }, onDismiss = {
            updateProjectFab()
            refreshPhotos()
        })
    }

    private fun confirmClearAll() {
        val count = adapter.itemCount
        if (count == 0) return

        val message: String
        val title: String
        val button: String
        if (showingQueue) {
            title = "Delete all $count items?"
            message = "This will remove all queued photos and videos."
            button = "Delete All"
        } else {
            title = "Clear all $count items?"
            message = "This only clears the local history. To delete from the server, use a computer."
            button = "Clear All"
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(button) { _, _ ->
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

    private val TYPE_PHOTO = 0
    private val TYPE_HEADER = 1

    inner class GalleryAdapter(
        private var items: MutableList<GalleryItem>
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        inner class PhotoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val imageView: ImageView = view.findViewById(R.id.thumbImage)
            val timeText: TextView = view.findViewById(R.id.timeText)
            val selectionOverlay: View = view.findViewById(R.id.selectionOverlay)
            val checkMark: ImageView = view.findViewById(R.id.checkMark)
            var currentPath: String? = null
        }

        inner class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val batchProject: TextView = view.findViewById(R.id.batchProject)
            val batchInfo: TextView = view.findViewById(R.id.batchInfo)
        }

        override fun getItemViewType(position: Int): Int {
            return when (items[position]) {
                is GalleryItem.Photo -> TYPE_PHOTO
                is GalleryItem.BatchHeader -> TYPE_HEADER
            }
        }

        fun isHeader(position: Int): Boolean {
            return position in items.indices && items[position] is GalleryItem.BatchHeader
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == TYPE_HEADER) {
                val view = android.view.LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_batch_header, parent, false)
                HeaderViewHolder(view)
            } else {
                val view = android.view.LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_photo, parent, false)
                PhotoViewHolder(view)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is GalleryItem.BatchHeader -> {
                    val h = holder as HeaderViewHolder
                    h.batchProject.text = item.project
                    val timeLabel = if (item.timestamp.isNotEmpty()) {
                        // Format yyyyMMdd_HHmmss to readable
                        try {
                            val parsed = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(item.timestamp)
                            SimpleDateFormat("MMM d, h:mm a", Locale.US).format(parsed!!)
                        } catch (e: Exception) { item.timestamp }
                    } else ""
                    h.batchInfo.text = "${item.count} file${if (item.count != 1) "s" else ""}${if (timeLabel.isNotEmpty()) "  •  $timeLabel" else ""}"
                }
                is GalleryItem.Photo -> {
                    val h = holder as PhotoViewHolder
                    val file = item.file
                    val path = file.absolutePath
                    h.currentPath = path

                    h.imageView.setImageDrawable(ColorDrawable(Color.DKGRAY))
                    ThumbnailCache.loadThumbnail(file, h.imageView) { h.currentPath }

                    val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)
                    val label = if (file.extension == "mp4") "▶ " else ""
                    h.timeText.text = label + sdf.format(Date(file.lastModified()))

                    val selected = position in selectedPositions
                    h.selectionOverlay.visibility = if (selected) View.VISIBLE else View.GONE
                    h.checkMark.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
                    h.checkMark.setImageResource(
                        if (selected) R.drawable.ic_select_checked else R.drawable.ic_select_unchecked
                    )

                    h.itemView.setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onItemTap(h.bindingAdapterPosition)
                    }
                    h.itemView.setOnLongClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        onItemLongPress(h.bindingAdapterPosition)
                        true
                    }
                }
            }
        }

        override fun getItemCount() = items.size

        fun getFile(position: Int): File {
            val item = items[position]
            return (item as? GalleryItem.Photo)?.file ?: File("")
        }

        fun updateItems(newItems: MutableList<GalleryItem>) {
            items = newItems
            notifyDataSetChanged()
        }
    }
}
