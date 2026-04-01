package com.kioskcamera

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.VideoView
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
    private var mediaFiles: MutableList<File> = mutableListOf()

    private val currentIndex: Int get() = pager.currentItem

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE

        setContentView(R.layout.activity_photo_viewer)

        pager = findViewById(R.id.photoPager)
        infoText = findViewById(R.id.photoInfo)
        counterText = findViewById(R.id.photoCounter)

        val startPath = intent.getStringExtra("photo_path")
            ?: intent.getStringExtra("video_path")
            ?: run { finish(); return }

        loadMedia(startPath)
        setupControls()
    }

    private fun loadMedia(startPath: String) {
        val queueDir = File(filesDir, "upload_queue")
        mediaFiles = queueDir.listFiles { f -> f.extension == "jpg" || f.extension == "mp4" }
            ?.sortedByDescending { it.lastModified() }
            ?.toMutableList() ?: mutableListOf()

        val startIndex = mediaFiles.indexOfFirst { it.absolutePath == startPath }.coerceAtLeast(0)

        pager.adapter = MediaPagerAdapter(mediaFiles)
        pager.setCurrentItem(startIndex, false)
        updateInfo(startIndex)

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateInfo(position)
                pauseAllVideos()
                resetZoomOnAllPages()
            }
        })
    }

    private fun pauseAllVideos() {
        val rv = pager.getChildAt(0) as? RecyclerView ?: return
        for (i in 0 until rv.childCount) {
            val vv = rv.getChildAt(i)?.findViewById<VideoView>(R.id.videoView)
            if (vv != null && vv.isPlaying) vv.pause()
            val ppb = rv.getChildAt(i)?.findViewById<ImageButton>(R.id.playPauseButton)
            ppb?.visibility = View.VISIBLE
            ppb?.setImageResource(android.R.drawable.ic_media_play)
        }
    }

    private fun resetZoomOnAllPages() {
        val rv = pager.getChildAt(0) as? RecyclerView ?: return
        for (i in 0 until rv.childCount) {
            val ziv = rv.getChildAt(i)?.findViewById<ZoomableImageView>(R.id.zoomableImage)
            ziv?.resetTransform()
        }
    }

    private fun updateInfo(position: Int) {
        if (position < 0 || position >= mediaFiles.size) return
        val file = mediaFiles[position]
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val size = file.length() / 1024
        val type = if (file.extension == "mp4") "Video" else "Photo"
        infoText.text = "$type  •  ${sdf.format(Date(file.lastModified()))}  •  ${size}KB"
        counterText.text = "${position + 1} / ${mediaFiles.size}"
    }

    private fun setupControls() {
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.deleteButtonBottom).setOnClickListener { confirmDelete() }
    }

    private fun confirmDelete() {
        if (mediaFiles.isEmpty()) return
        val idx = currentIndex
        val file = mediaFiles[idx]
        val type = if (file.extension == "mp4") "video" else "photo"

        AlertDialog.Builder(this)
            .setTitle("Delete $type?")
            .setMessage(file.name)
            .setPositiveButton("Delete") { _, _ ->
                // Stop video if playing
                pauseAllVideos()
                file.delete()
                mediaFiles.removeAt(idx)
                if (mediaFiles.isEmpty()) {
                    finish()
                } else {
                    pager.adapter?.notifyItemRemoved(idx)
                    updateInfo(pager.currentItem.coerceAtMost(mediaFiles.size - 1))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        pauseAllVideos()
    }

    class MediaPagerAdapter(
        private val files: List<File>
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            private const val TYPE_PHOTO = 0
            private const val TYPE_VIDEO = 1
        }

        class PhotoVH(view: View) : RecyclerView.ViewHolder(view) {
            val imageView: ZoomableImageView = view.findViewById(R.id.zoomableImage)
        }

        class VideoVH(view: View) : RecyclerView.ViewHolder(view) {
            val videoView: VideoView = view.findViewById(R.id.videoView)
            val playPauseButton: ImageButton = view.findViewById(R.id.playPauseButton)
        }

        override fun getItemViewType(position: Int): Int {
            return if (files[position].extension == "mp4") TYPE_VIDEO else TYPE_PHOTO
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == TYPE_VIDEO) {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_video_page, parent, false)
                VideoVH(view)
            } else {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_photo_page, parent, false)
                PhotoVH(view)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val file = files[position]
            when (holder) {
                is PhotoVH -> {
                    val bitmap = decodeBitmapWithRotation(file.absolutePath)
                    holder.imageView.resetTransform()
                    holder.imageView.setImageBitmap(bitmap)
                }
                is VideoVH -> {
                    holder.videoView.setVideoURI(Uri.fromFile(file))
                    holder.playPauseButton.visibility = View.VISIBLE
                    holder.playPauseButton.setImageResource(android.R.drawable.ic_media_play)

                    val togglePlay = {
                        if (holder.videoView.isPlaying) {
                            holder.videoView.pause()
                            holder.playPauseButton.visibility = View.VISIBLE
                            holder.playPauseButton.setImageResource(android.R.drawable.ic_media_play)
                        } else {
                            holder.videoView.start()
                            holder.playPauseButton.visibility = View.GONE
                        }
                    }

                    holder.playPauseButton.setOnClickListener { togglePlay() }
                    holder.videoView.setOnClickListener { togglePlay() }

                    holder.videoView.setOnCompletionListener {
                        holder.playPauseButton.visibility = View.VISIBLE
                        holder.playPauseButton.setImageResource(android.R.drawable.ic_media_play)
                    }
                }
            }
        }

        override fun getItemCount() = files.size
    }
}
