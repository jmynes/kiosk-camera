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

    fun getQueueDirForProject(context: Context, project: String?): File {
        val sub = project ?: "_unassigned"
        val dir = File(context.filesDir, "upload_queue/$sub")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getFilesForProject(context: Context, project: String?): List<File> {
        val dir = getQueueDirForProject(context, project)
        return dir.listFiles { f -> f.extension == "jpg" || f.extension == "mp4" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun getPendingCountForProject(context: Context, project: String?): Int {
        return getFilesForProject(context, project).size
    }

    fun getUploadedDir(context: Context): File {
        val dir = File(context.filesDir, "uploaded_cache")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    data class UploadBatch(
        val dir: File,
        val project: String,
        val timestamp: String,
        val files: List<File>
    )

    fun getUploadBatches(context: Context): List<UploadBatch> {
        val uploadedDir = getUploadedDir(context)
        val batchDirs = uploadedDir.listFiles { f -> f.isDirectory }
            ?.sortedByDescending { it.name } ?: emptyList()
        return batchDirs.mapNotNull { dir ->
            val metaFile = File(dir, ".batch_meta")
            val project = if (metaFile.exists()) metaFile.readText().trim() else "Unknown"
            val files = dir.listFiles { f -> f.extension == "jpg" || f.extension == "mp4" }
                ?.sortedBy { it.name }?.toList() ?: emptyList()
            if (files.isNotEmpty()) UploadBatch(dir, project, dir.name, files) else null
        }
    }

    fun getPendingCount(context: Context): Int {
        return getQueueDir(context).listFiles { f ->
            f.extension == "jpg" || f.extension == "mp4"
        }?.size ?: 0
    }

    fun getUploadedFiles(context: Context): List<File> {
        // Flat list across all batches, plus any legacy files in root
        val files = mutableListOf<File>()
        val uploadedDir = getUploadedDir(context)
        // Legacy flat files
        uploadedDir.listFiles { f -> f.isFile && (f.extension == "jpg" || f.extension == "mp4") }
            ?.let { files.addAll(it) }
        // Batch subdirectories
        uploadedDir.listFiles { f -> f.isDirectory }?.forEach { batchDir ->
            batchDir.listFiles { f -> f.extension == "jpg" || f.extension == "mp4" }
                ?.let { files.addAll(it) }
        }
        return files.sortedByDescending { it.lastModified() }
    }

    fun clearUploadedCache(context: Context) {
        getUploadedDir(context).listFiles()?.forEach {
            if (it.isDirectory) it.deleteRecursively() else it.delete()
        }
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

                // Cache into batch subdirectory
                if (uploaded > 0) {
                    val batchTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val batchDir = File(uploadedDir, batchTimestamp)
                    batchDir.mkdirs()
                    // Write batch metadata
                    File(batchDir, ".batch_meta").writeText(projectNumber)

                    for (i in 0 until uploaded) {
                        val file = sortedFiles[i]
                        val remoteName = renamedFiles[i].second
                        val cached = File(batchDir, remoteName)
                        file.copyTo(cached, overwrite = true)
                        file.delete()
                        Log.i(TAG, "Uploaded and cached: ${remoteName}")
                    }
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
        return "$basePath/$year/$projectNumber/$timestamp"
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
