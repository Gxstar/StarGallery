package com.gxstar.stargallery.data.local

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.bumptech.glide.Glide
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

    suspend fun generateThumbnail(uri: Uri, photoId: Long, mimeType: String, displayName: String? = null): String? =
        withContext(Dispatchers.IO) {
            val ext = displayName?.substringAfterLast('.', "")?.lowercase() ?: ""
            // 仅跳过 RAW 格式（image/x-*），不跳过 image/* 泛类型
            if (mimeType.startsWith("image/x-") && mimeType != "image/jxl") {
                return@withContext null
            }

            try {
                // 使用与 Photo.isJxl 一致的双重判断：MIME + 扩展名兜底
                // MediaStore 不识别 .jxl，其 MIME 可能为 null/"image/*"，必须用扩展名兜底
                val isJxl = mimeType == "image/jxl" || ext == "jxl"
                val bitmap = if (isJxl) {
                    // JXL 不走 Glide 的 content:// 解码路径（MediaStore 缩略图对 JXL 失败），
                    // 直接用 jxl-coder 核心库解码字节流。
                    decodeJxlThumbnail(uri)
                } else {
                    // 统一走 Glide 解码，使缩略图生成与图片加载共用同一解码链
                    // （AVIF/HEIC 由系统原生解码，minSdk 35 全量支持）。
                    Glide.with(context)
                        .asBitmap()
                        .load(uri)
                        .submit(THUMBNAIL_SIZE, THUMBNAIL_SIZE)
                        .get()
                }

                if (bitmap == null) return@withContext null

                Log.d(TAG, "genThumb $uri config=${bitmap.config} w=${bitmap.width} h=${bitmap.height} cs=${bitmap.colorSpace}")

                val file = thumbnailFileFor(photoId)
                val ok = file.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, out)
                }
                Log.d(TAG, "genThumb compressOk=$ok size=${file.length()}")
                bitmap.recycle()

                if (!ok) {
                    file.delete()
                    null
                } else {
                    file.absolutePath
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to generate thumbnail for $uri", e)
                null
            }
        }

    @androidx.annotation.WorkerThread
    private fun decodeJxlThumbnail(uri: Uri): android.graphics.Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readBytes()
                com.awxkee.jxlcoder.JxlCoder.decodeSampled(
                    bytes,
                    THUMBNAIL_SIZE,
                    THUMBNAIL_SIZE,
                    com.awxkee.jxlcoder.PreferredColorConfig.RGBA_8888,
                    com.awxkee.jxlcoder.ScaleMode.FIT,
                    com.awxkee.jxlcoder.JxlResizeFilter.CATMULL_ROM
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "JXL decode failed for $uri", e)
            null
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
