package com.kioskcamera

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var videoView: VideoView
    private lateinit var playPauseButton: ImageButton
    private var videoFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE

        setContentView(R.layout.activity_video_player)

        videoView = findViewById(R.id.videoView)
        playPauseButton = findViewById(R.id.playPauseButton)

        val path = intent.getStringExtra("video_path") ?: run { finish(); return }
        videoFile = File(path)

        if (!videoFile!!.exists()) { finish(); return }

        setupControls()
        setupVideo()
    }

    private fun setupVideo() {
        val file = videoFile ?: return

        videoView.setVideoURI(Uri.fromFile(file))

        val mc = MediaController(this)
        mc.setAnchorView(videoView)
        videoView.setMediaController(mc)

        videoView.setOnPreparedListener { mp ->
            val duration = mp.duration / 1000
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val size = file.length() / 1024
            val mins = duration / 60
            val secs = duration % 60
            findViewById<TextView>(R.id.videoInfo).text =
                "${sdf.format(Date(file.lastModified()))}  •  ${size}KB  •  ${mins}:${String.format("%02d", secs)}"
            findViewById<TextView>(R.id.videoTitle).text = file.name

            videoView.start()
        }

        videoView.setOnCompletionListener {
            playPauseButton.visibility = View.VISIBLE
            playPauseButton.setImageResource(android.R.drawable.ic_media_play)
        }

        playPauseButton.setOnClickListener {
            if (videoView.isPlaying) {
                videoView.pause()
                playPauseButton.setImageResource(android.R.drawable.ic_media_play)
            } else {
                videoView.start()
                playPauseButton.visibility = View.GONE
            }
        }

        videoView.setOnClickListener {
            if (videoView.isPlaying) {
                videoView.pause()
                playPauseButton.visibility = View.VISIBLE
                playPauseButton.setImageResource(android.R.drawable.ic_media_play)
            } else {
                videoView.start()
                playPauseButton.visibility = View.GONE
            }
        }
    }

    private fun setupControls() {
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.deleteButton).setOnClickListener { confirmDelete() }
    }

    private fun confirmDelete() {
        val file = videoFile ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete video?")
            .setMessage(file.name)
            .setPositiveButton("Delete") { _, _ ->
                videoView.stopPlayback()
                file.delete()
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        if (videoView.isPlaying) videoView.pause()
    }
}
