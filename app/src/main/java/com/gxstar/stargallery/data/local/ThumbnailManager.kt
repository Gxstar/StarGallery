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
        // 旧缓存目录（缩略图被压成正方形的那版），升级后一次性清理
        private const val THUMBNAIL_DIR_OLD = "thumbnails"
        private const val THUMBNAIL_DIR = "thumbnails_v2"
        private const val THUMBNAIL_SIZE = 512   // 长边目标尺寸（保持原图比例，不再压成正方形）
        private const val THUMBNAIL_QUALITY = 85
    }

    init {
        migrateOldCache()
    }

    private val cacheDir: File
        get() = File(context.cacheDir, THUMBNAIL_DIR).also { it.mkdirs() }

    // 清理旧版本缓存目录（缩略图被拉伸变形那一版），避免旧畸形图继续被读取
    private fun migrateOldCache() {
        val old = File(context.cacheDir, THUMBNAIL_DIR_OLD)
        if (old.exists() && old.isDirectory) {
            old.deleteRecursively()
        }
    }

    fun getThumbnailFile(photoId: Long): File? {
        val file = thumbnailFileFor(photoId)
        return if (file.exists()) file else null
    }

    /** 当前版本下某照片的缩略图文件（不论是否存在），用于判断是否需要重新生成 */
    fun thumbnailFileFor(photoId: Long): File = File(cacheDir, "$photoId.jpg")

    suspend fun generateThumbnail(uri: Uri, photoId: Long, mimeType: String): String? =
        withContext(Dispatchers.IO) {
            if (mimeType.startsWith("image/x-")) {
                return@withContext null
            }

            try {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    // setTargetSize 会精确缩放到给定宽高（不保比例）。
                    // 必须按原图比例计算目标尺寸，否则竖图被压成正方形导致变形。
                    val (tw, th) = fitSize(info.size.width, info.size.height, THUMBNAIL_SIZE)
                    decoder.setTargetSize(tw, th)
                }
                val file = thumbnailFileFor(photoId)
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

    /**
     * 在保持原图比例的前提下，把长边缩放到 max，返回 (宽, 高)。
     * 例如竖图 3024x4032 → 384x512，横图 4032x3024 → 512x384。
     */
    private fun fitSize(srcW: Int, srcH: Int, max: Int): Pair<Int, Int> {
        if (srcW <= 0 || srcH <= 0) return max to max
        return if (srcW >= srcH) {
            max to (max * srcH / srcW).coerceAtLeast(1)
        } else {
            (max * srcW / srcH).coerceAtLeast(1) to max
        }
    }

    fun deleteThumbnail(photoId: Long) {
        thumbnailFileFor(photoId).delete()
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
