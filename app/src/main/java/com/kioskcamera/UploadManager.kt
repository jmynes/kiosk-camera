package com.kioskcamera

import android.content.Context
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

object UploadManager {

    private const val TAG = "UploadManager"
    private val executor = Executors.newSingleThreadExecutor()
    private var httpClient: OkHttpClient? = null

    fun init(client: OkHttpClient) {
        httpClient = client
    }

    fun getQueueDir(context: Context): File {
        val dir = File(context.filesDir, "upload_queue")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getUploadedDir(context: Context): File {
        val dir = File(context.filesDir, "uploaded_cache")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getPendingCount(context: Context): Int {
        return getQueueDir(context).listFiles { f ->
            f.extension == "jpg" || f.extension == "mp4"
        }?.size ?: 0
    }

    fun getUploadedFiles(context: Context): List<File> {
        return getUploadedDir(context).listFiles { f ->
            f.extension == "jpg" || f.extension == "mp4"
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun clearUploadedCache(context: Context) {
        getUploadedDir(context).listFiles()?.forEach { it.delete() }
    }

    fun uploadFiles(
        context: Context,
        files: List<File>,
        projectNumber: String,
        onProgress: (String) -> Unit,
        onComplete: (Int, Int) -> Unit
    ) {
        executor.execute {
            val uploadedDir = getUploadedDir(context)
            val sortedFiles = files.filter { it.exists() }.sortedBy { it.lastModified() }

            if (sortedFiles.isEmpty()) {
                onComplete(0, 0)
                return@execute
            }

            if (BuildConfig.USE_SCP) {
                val remotePath = buildRemotePath(projectNumber)
                val digitWidth = if (sortedFiles.size >= 100) 3 else 2
                val renamedFiles = sortedFiles.mapIndexed { index, file ->
                    val num = String.format("%0${digitWidth}d", index + 1)
                    val remoteName = "${num}_${file.name}"
                    Pair(file, remoteName)
                }

                onProgress("Connecting to server...")
                val (uploaded, failed) = ScpUploader.uploadBatch(
                    renamedFiles,
                    BuildConfig.SCP_HOST, BuildConfig.SCP_PORT,
                    BuildConfig.SCP_USER, remotePath,
                    onProgress
                )

                // Cache and delete uploaded files
                for (i in 0 until uploaded) {
                    val file = sortedFiles[i]
                    val cached = File(uploadedDir, file.name)
                    file.copyTo(cached, overwrite = true)
                    file.delete()
                    Log.i(TAG, "Uploaded and cached: ${file.name}")
                }

                onComplete(uploaded, failed)
            } else {
                // HTTPS fallback (no project folder structure)
                var uploaded = 0
                var failed = 0
                for (file in sortedFiles) {
                    onProgress("Uploading ${file.name}...")
                    if (uploadFileHttp(file)) {
                        val cached = File(uploadedDir, file.name)
                        file.copyTo(cached, overwrite = true)
                        file.delete()
                        uploaded++
                        onProgress("Uploaded ${file.name}")
                    } else {
                        failed++
                        onProgress("Failed: ${file.name}")
                        break
                    }
                }
                onComplete(uploaded, failed)
            }
        }
    }

    fun uploadAll(
        context: Context,
        projectNumber: String,
        onProgress: (String) -> Unit,
        onComplete: (Int, Int) -> Unit
    ) {
        val queueDir = getQueueDir(context)
        val files = queueDir.listFiles { f ->
            f.extension == "jpg" || f.extension == "mp4"
        }?.toList() ?: emptyList()

        uploadFiles(context, files, projectNumber, onProgress, onComplete)
    }

    private fun buildRemotePath(projectNumber: String): String {
        val basePath = BuildConfig.SCP_PATH.trimEnd('/')
        val year = SimpleDateFormat("yyyy", Locale.US).format(Date())
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "$basePath/$projectNumber/$year/$timestamp"
    }

    private fun uploadFileHttp(file: File): Boolean {
        val client = httpClient ?: return false
        val mediaType = if (file.extension == "mp4") "video/mp4" else "image/jpeg"
        val fieldName = if (file.extension == "mp4") "video" else "photo"

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(fieldName, file.name, file.asRequestBody(mediaType.toMediaType()))
            .build()

        val request = Request.Builder()
            .url(BuildConfig.UPLOAD_URL)
            .post(requestBody)
            .build()

        return try {
            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            response.close()
            success
        } catch (e: Exception) {
            Log.e(TAG, "Upload exception: ${e.javaClass.simpleName}: ${e.message}", e)
            false
        }
    }
}
