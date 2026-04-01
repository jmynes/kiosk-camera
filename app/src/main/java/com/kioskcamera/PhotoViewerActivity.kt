package com.kioskcamera

import android.media.MediaMetadataRetriever
import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
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
    private lateinit var videoSeekBar: SeekBar
    private lateinit var videoTimeText: TextView
    private lateinit var floatingPlayPause: ImageButton
    private lateinit var floatingMute: ImageButton
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var mediaFiles: MutableList<File> = mutableListOf()
    private var isMuted = true
    private var activeMediaPlayer: android.media.MediaPlayer? = null
    private var controlsVisible = true
    private lateinit var topBar: View
    private lateinit var bottomContainer: View

    private val currentIndex: Int get() = pager.currentItem

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE

        setContentView(R.layout.activity_photo_viewer)

        pager = findViewById(R.id.photoPager)
        infoText = findViewById(R.id.photoInfo)
        counterText = findViewById(R.id.photoCounter)
        videoSeekBar = findViewById(R.id.videoSeekBar)
        videoTimeText = findViewById(R.id.videoTimeText)
        floatingPlayPause = findViewById(R.id.floatingPlayPause)
        floatingPlayPause.setOnClickListener { toggleCurrentVideo() }
        floatingMute = findViewById(R.id.floatingMute)
        floatingMute.setOnClickListener { toggleMute() }
        topBar = findViewById(R.id.topBar)
        bottomContainer = findViewById(R.id.bottomContainer)

        // Prevent swipes on controls from triggering ViewPager2 page changes
        // Disable ViewPager2 input directly while touching overlays
        val consumeSwipe = View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> pager.isUserInputEnabled = false
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pager.isUserInputEnabled = true
            }
            false
        }
        bottomContainer.setOnTouchListener(consumeSwipe)
        topBar.setOnTouchListener(consumeSwipe)
        floatingPlayPause.setOnTouchListener(consumeSwipe)
        floatingMute.setOnTouchListener(consumeSwipe)
        videoSeekBar.setOnTouchListener(consumeSwipe)

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

        pager.adapter = MediaPagerAdapter(mediaFiles, this)
        pager.setCurrentItem(startIndex, false)
        updateInfo(startIndex)

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateInfo(position)
                pauseAllVideos()
                resetZoomOnAllPages()
                showControls()
            }

            override fun onPageScrollStateChanged(state: Int) {
                if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    pager.isUserInputEnabled = true
                    autoPlayCurrentVideo()
                }
            }
        })
    }

    private fun showControls() {
        controlsVisible = true
        topBar.visibility = View.VISIBLE
        bottomContainer.visibility = View.VISIBLE
        val isVideo = currentIndex < mediaFiles.size && mediaFiles[currentIndex].extension == "mp4"
        if (isVideo) {
            floatingPlayPause.visibility = View.VISIBLE
            floatingMute.visibility = View.VISIBLE
        }
    }

    private fun autoPlayCurrentVideo() {
        val idx = pager.currentItem
        if (idx < 0 || idx >= mediaFiles.size) return
        if (mediaFiles[idx].extension != "mp4") return

        val rv = pager.getChildAt(0) as? RecyclerView ?: return
        for (i in 0 until rv.childCount) {
            val vh = rv.getChildViewHolder(rv.getChildAt(i))
            if (vh is MediaPagerAdapter.VideoVH && vh.bindingAdapterPosition == idx) {
                if (!vh.videoView.isPlaying) {
                    vh.videoView.visibility = View.VISIBLE
                    vh.thumbnail.visibility = View.GONE
                    vh.videoView.start()
                    updatePlayPauseState(true)
                }
                break
            }
        }
    }

    private fun pauseAllVideos() {
        val rv = pager.getChildAt(0) as? RecyclerView ?: return
        for (i in 0 until rv.childCount) {
            val vv = rv.getChildAt(i)?.findViewById<VideoView>(R.id.videoView)
            if (vv != null && vv.isPlaying) vv.pause()
        }
        updatePlayPauseState(false)
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
        val bytes = file.length()
        val sizeStr = when {
            bytes >= 1024L * 1024 * 1024 -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            else -> "${bytes / 1024} KB"
        }
        val isVideo = file.extension == "mp4"
        val type = if (isVideo) "Video" else "Photo"
        infoText.text = "$type  •  ${sdf.format(Date(file.lastModified()))}  •  $sizeStr"
        counterText.text = "${position + 1} / ${mediaFiles.size}"

        videoSeekBar.visibility = if (isVideo) View.VISIBLE else View.GONE
        videoTimeText.visibility = if (isVideo) View.VISIBLE else View.GONE
        floatingPlayPause.visibility = if (isVideo) View.VISIBLE else View.GONE
        floatingMute.visibility = if (isVideo) View.VISIBLE else View.GONE
        if (isVideo) {
            floatingPlayPause.setImageResource(R.drawable.ic_play)
        }
        handler.removeCallbacks(seekBarUpdater)
    }

    private fun formatTime(ms: Int): String {
        val secs = ms / 1000
        return String.format("%d:%02d", secs / 60, secs % 60)
    }

    private fun getCurrentVideoView(): VideoView? {
        val rv = pager.getChildAt(0) as? RecyclerView ?: return null
        for (i in 0 until rv.childCount) {
            val vh = rv.getChildViewHolder(rv.getChildAt(i))
            if (vh is MediaPagerAdapter.VideoVH && vh.bindingAdapterPosition == pager.currentItem) {
                return vh.videoView
            }
        }
        return null
    }

    private val seekBarUpdater = object : Runnable {
        override fun run() {
            val vv = getCurrentVideoView()
            if (vv != null && vv.isPlaying) {
                videoSeekBar.progress = vv.currentPosition
                videoTimeText.text = "${formatTime(vv.currentPosition)} / ${formatTime(vv.duration)}"
                handler.postDelayed(this, 250)
            }
        }
    }

    private fun toggleCurrentVideo() {
        val rv = pager.getChildAt(0) as? RecyclerView ?: return
        for (i in 0 until rv.childCount) {
            val vh = rv.getChildViewHolder(rv.getChildAt(i))
            if (vh is MediaPagerAdapter.VideoVH && vh.bindingAdapterPosition == pager.currentItem) {
                if (vh.videoView.isPlaying) {
                    vh.videoView.pause()
                    updatePlayPauseState(false)
                    onVideoStopped()
                } else {
                    vh.videoView.visibility = View.VISIBLE
                    vh.thumbnail.visibility = View.GONE
                    vh.videoView.start()
                    updatePlayPauseState(true)
                }
                break
            }
        }
    }

    fun toggleControls() {
        controlsVisible = !controlsVisible
        pager.isUserInputEnabled = true
        val vis = if (controlsVisible) View.VISIBLE else View.GONE
        topBar.visibility = vis
        bottomContainer.visibility = vis

        val isVideo = currentIndex < mediaFiles.size && mediaFiles[currentIndex].extension == "mp4"
        if (isVideo) {
            floatingPlayPause.visibility = vis
            floatingMute.visibility = vis
        }
    }

    fun updatePlayPauseState(playing: Boolean) {
        floatingPlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    fun onVideoStarted(videoView: VideoView) {
        videoSeekBar.max = videoView.duration
        videoSeekBar.progress = 0
        videoTimeText.text = "0:00 / ${formatTime(videoView.duration)}"
        videoSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    videoView.seekTo(progress)
                    videoTimeText.text = "${formatTime(progress)} / ${formatTime(videoView.duration)}"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        handler.post(seekBarUpdater)
    }

    fun onVideoStopped() {
        handler.removeCallbacks(seekBarUpdater)
    }

    private fun toggleMute() {
        isMuted = !isMuted
        floatingMute.setImageResource(if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_on)
        val vol = if (isMuted) 0f else 1f
        activeMediaPlayer?.setVolume(vol, vol)
    }

    private fun setupControls() {
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.deleteButtonTop).setOnClickListener { confirmDelete() }
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
        private val files: List<File>,
        private val activity: PhotoViewerActivity
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
            val thumbnail: ImageView = view.findViewById(R.id.videoThumbnail)
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
                    holder.imageView.onSingleTapCallback = { activity.toggleControls() }
                }
                is VideoVH -> {
                    // Load first frame as thumbnail
                    try {
                        val retriever = MediaMetadataRetriever()
                        retriever.setDataSource(file.absolutePath)
                        val frame = retriever.getFrameAtTime(0)
                        holder.thumbnail.setImageBitmap(frame)
                        holder.thumbnail.visibility = View.VISIBLE
                        retriever.release()
                    } catch (e: Exception) {
                        holder.thumbnail.visibility = View.GONE
                    }

                    holder.videoView.visibility = View.INVISIBLE
                    holder.videoView.setVideoURI(Uri.fromFile(file))

                    holder.videoView.setOnPreparedListener { mp ->
                        activity.activeMediaPlayer = mp
                        val vol = if (activity.isMuted) 0f else 1f
                        mp.setVolume(vol, vol)
                        activity.onVideoStarted(holder.videoView)
                    }

                    val togglePlay = {
                        if (holder.videoView.isPlaying) {
                            holder.videoView.pause()
                            activity.updatePlayPauseState(false)
                            activity.onVideoStopped()
                        } else {
                            holder.videoView.visibility = View.VISIBLE
                            holder.thumbnail.visibility = View.GONE
                            holder.videoView.start()
                            activity.updatePlayPauseState(true)
                        }
                    }

                    holder.videoView.setOnClickListener { activity.toggleControls() }

                    holder.videoView.setOnCompletionListener {
                        activity.updatePlayPauseState(false)
                        activity.onVideoStopped()
                    }
                }
            }
        }

        override fun getItemCount() = files.size
    }
}
