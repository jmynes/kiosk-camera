package com.kioskcamera

import android.content.Context
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
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

    fun getPendingCount(context: Context): Int {
        return getQueueDir(context).listFiles { f ->
            f.extension == "jpg" || f.extension == "mp4"
        }?.size ?: 0
    }

    fun uploadAll(context: Context, onProgress: (String) -> Unit, onComplete: (Int, Int) -> Unit) {
        executor.execute {
            val queueDir = getQueueDir(context)
            val files = queueDir.listFiles { f ->
                f.extension == "jpg" || f.extension == "mp4"
            }?.sortedBy { it.lastModified() } ?: emptyList()

            var uploaded = 0
            var failed = 0

            for (file in files) {
                onProgress("Uploading ${file.name}...")
                if (uploadFile(file)) {
                    file.delete()
                    uploaded++
                    Log.i(TAG, "Uploaded and deleted: ${file.name}")
                    onProgress("Uploaded ${file.name}")
                } else {
                    failed++
                    Log.w(TAG, "Upload failed for ${file.name}")
                    onProgress("Failed: ${file.name}")
                    break // Stop on first failure
                }
            }

            onComplete(uploaded, failed)
        }
    }

    private fun uploadFile(file: File): Boolean {
        if (BuildConfig.USE_SCP) {
            return ScpUploader.uploadFile(
                file, BuildConfig.SCP_HOST, BuildConfig.SCP_PORT,
                BuildConfig.SCP_USER, BuildConfig.SCP_PATH
            )
        }

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
            Log.i(TAG, "Uploading ${file.name} to ${BuildConfig.UPLOAD_URL}")
            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            Log.i(TAG, "Upload response: ${response.code} ${response.message}")
            response.close()
            success
        } catch (e: Exception) {
            Log.e(TAG, "Upload exception: ${e.javaClass.simpleName}: ${e.message}", e)
            false
        }
    }
}
