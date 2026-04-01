package com.kioskcamera

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
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
    private lateinit var videoControlsRow: View
    private lateinit var muteButton: TextView
    private lateinit var playPauseText: TextView
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var mediaFiles: MutableList<File> = mutableListOf()
    private var isMuted = false

    private val currentIndex: Int get() = pager.currentItem

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
        videoControlsRow = findViewById(R.id.videoControlsRow)
        muteButton = findViewById(R.id.muteButton)
        muteButton.setOnClickListener { toggleMute() }
        playPauseText = findViewById(R.id.playPauseText)
        playPauseText.setOnClickListener { toggleCurrentVideo() }

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
        val isVideo = file.extension == "mp4"
        val type = if (isVideo) "Video" else "Photo"
        infoText.text = "$type  •  ${sdf.format(Date(file.lastModified()))}  •  ${size}KB"
        counterText.text = "${position + 1} / ${mediaFiles.size}"

        videoSeekBar.visibility = if (isVideo) View.VISIBLE else View.GONE
        videoControlsRow.visibility = if (isVideo) View.VISIBLE else View.GONE
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
                    vh.playPauseButton.visibility = View.VISIBLE
                    vh.playPauseButton.setImageResource(android.R.drawable.ic_media_play)
                    playPauseText.text = "PLAY"
                    onVideoStopped()
                } else {
                    vh.videoView.visibility = View.VISIBLE
                    vh.thumbnail.visibility = View.GONE
                    vh.videoView.start()
                    vh.playPauseButton.visibility = View.GONE
                    playPauseText.text = "PAUSE"
                }
                break
            }
        }
    }

    fun updatePlayPauseState(playing: Boolean) {
        playPauseText.text = if (playing) "PAUSE" else "PLAY"
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
        muteButton.text = if (isMuted) "UNMUTE" else "MUTE"
        muteButton.setTextColor(if (isMuted) 0xFFFFD700.toInt() else 0xFFFFFFFF.toInt())
    }

    // Mute is applied via onPreparedListener in the adapter and on toggle
    // by seeking to current position which re-triggers prepare

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
            val playPauseButton: ImageButton = view.findViewById(R.id.playPauseButton)
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
                    holder.playPauseButton.visibility = View.VISIBLE
                    holder.playPauseButton.setImageResource(android.R.drawable.ic_media_play)

                    holder.videoView.setOnPreparedListener { mp ->
                        mp.setVolume(
                            if (activity.isMuted) 0f else 1f,
                            if (activity.isMuted) 0f else 1f
                        )
                        // Duration is now available
                        activity.onVideoStarted(holder.videoView)
                    }

                    val togglePlay = {
                        if (holder.videoView.isPlaying) {
                            holder.videoView.pause()
                            holder.playPauseButton.visibility = View.VISIBLE
                            holder.playPauseButton.setImageResource(android.R.drawable.ic_media_play)
                            activity.updatePlayPauseState(false)
                            activity.onVideoStopped()
                        } else {
                            holder.videoView.visibility = View.VISIBLE
                            holder.thumbnail.visibility = View.GONE
                            holder.videoView.start()
                            holder.playPauseButton.visibility = View.GONE
                            activity.updatePlayPauseState(true)
                        }
                    }

                    holder.playPauseButton.setOnClickListener { togglePlay() }
                    holder.videoView.setOnClickListener { togglePlay() }

                    holder.videoView.setOnCompletionListener {
                        holder.playPauseButton.visibility = View.VISIBLE
                        holder.playPauseButton.setImageResource(android.R.drawable.ic_media_play)
                        activity.onVideoStopped()
                    }
                }
            }
        }

        override fun getItemCount() = files.size
    }
}
