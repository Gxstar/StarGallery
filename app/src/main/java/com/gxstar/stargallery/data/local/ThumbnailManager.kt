package com.gxstar.stargallery.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThumbnailManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ThumbnailManager"
        private const val THUMBNAIL_DIR = "thumbnails"
        private const val THUMBNAIL_SIZE = 512
        private const val THUMBNAIL_QUALITY = 85
    }

    private val cacheDir: File
        get() = File(context.cacheDir, THUMBNAIL_DIR).also { it.mkdirs() }

    fun getThumbnailFile(photoId: Long): File? {
        val file = File(cacheDir, "${photoId}.jpg")
        return if (file.exists()) file else null
    }

    suspend fun generateThumbnail(uri: Uri, photoId: Long, mimeType: String): String? =
        withContext(Dispatchers.IO) {
            if (mimeType.startsWith("image/x-")) {
                return@withContext null
            }

            try {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.setTargetSize(THUMBNAIL_SIZE, THUMBNAIL_SIZE)
                }
                val file = File(cacheDir, "${photoId}.jpg")
                file.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, out)
                }
                bitmap.recycle()

                file.absolutePath
            } catch (e: Exception) {
                Log.w(TAG, "Failed to generate thumbnail for $uri (${e.message})")
                null
            }
        }

    fun deleteThumbnail(photoId: Long) {
        File(cacheDir, "${photoId}.jpg").delete()
    }

    fun deleteThumbnails(photoIds: Collection<Long>) {
        photoIds.forEach { deleteThumbnail(it) }
    }

    fun cleanupOrphanedThumbnails(validIds: Set<Long>) {
        cacheDir.listFiles()?.forEach { file ->
            val name = file.nameWithoutExtension
            val id = name.toLongOrNull()
            if (id == null || id !in validIds) {
                file.delete()
            }
        }
    }

    fun getThumbnailCount(): Int {
        return cacheDir.listFiles()?.size ?: 0
    }
}
