package com.kioskcamera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

object ThumbnailCache {

    private val memCache = LruCache<String, Bitmap>(100)
    private val executor = Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var diskCacheDir: File? = null

    fun init(cacheDir: File) {
        diskCacheDir = File(cacheDir, "thumbs")
        diskCacheDir!!.mkdirs()
    }

    fun loadThumbnail(file: File, imageView: ImageView, tag: () -> String?) {
        val path = file.absolutePath
        val key = file.name

        // Memory cache hit
        val memHit = memCache.get(key)
        if (memHit != null) {
            imageView.setImageBitmap(memHit)
            return
        }

        executor.execute {
            // Disk cache hit
            val diskFile = File(diskCacheDir, "$key.thumb")
            var bitmap: Bitmap? = null

            if (diskFile.exists() && diskFile.lastModified() >= file.lastModified()) {
                bitmap = BitmapFactory.decodeFile(diskFile.absolutePath)
            }

            // Generate thumbnail
            if (bitmap == null) {
                bitmap = if (file.extension == "mp4") {
                    try {
                        ThumbnailUtils.createVideoThumbnail(file, Size(270, 270), null)
                    } catch (e: Exception) { null }
                } else {
                    decodeBitmapWithRotation(file.absolutePath, sampleSize = 4)
                }

                // Save to disk
                if (bitmap != null) {
                    try {
                        FileOutputStream(diskFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
                        }
                    } catch (e: Exception) { /* ignore */ }
                }
            }

            if (bitmap != null) {
                memCache.put(key, bitmap)
                val bmp = bitmap
                mainHandler.post {
                    if (tag() == path) {
                        imageView.setImageBitmap(bmp)
                    }
                }
            }
        }
    }

    fun clearAll() {
        memCache.evictAll()
        diskCacheDir?.listFiles()?.forEach { it.delete() }
    }
}
