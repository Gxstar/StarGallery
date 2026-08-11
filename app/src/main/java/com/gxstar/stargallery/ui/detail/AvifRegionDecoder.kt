package com.gxstar.stargallery.ui.detail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.github.panpf.zoomimage.subsampling.ContentImageSource
import com.github.panpf.zoomimage.subsampling.ImageInfo
import com.github.panpf.zoomimage.subsampling.ImageSource
import com.github.panpf.zoomimage.subsampling.RegionDecoder
import com.github.panpf.zoomimage.subsampling.SubsamplingImage
import com.github.panpf.zoomimage.subsampling.TileBitmap
import com.github.panpf.zoomimage.util.IntRectCompat
import com.radzivon.bartoshyk.avif.coder.HeifCoder
import java.io.BufferedInputStream
import okio.buffer
import okio.use

/**
 * AVIF 大图区域解码器（libavif 实现）
 *
 * 保留原因：Android 原生 ImageDecoder 仅保证 AVIF baseline profile
 * （4:2:0 色度采样，8/10-bit）。4:2:2 / 4:4:4 色度采样与 12-bit 的 AVIF
 * （如专业工具/部分相机导出）原生解码器会直接失败，必须用 libavif 软件解码。
 * 实测样本：profile 1 (High) / 10-bit / 4:4:4 的 AVIF 在原生解码器上灰图+无缩略图。
 *
 * 说明：
 * - libavif 支持 10/12-bit 与 4:4:4/4:2:2 解码（HeifCoder），覆盖原生能力缺口
 * - tile 解码默认输出 8-bit（libavif RGBA），子采样预览用；
 *   详情页 HDR 由 colorModeForBitmap 按位图实际属性处理
 * - 大图仍是"整帧解码 + 缩放 + 裁切"（AVIF 无原生 tile 解码，平台边界）
 */
class AvifRegionDecoder(
    private val subsamplingImage: SubsamplingImage,
    val imageSource: ContentImageSource,
    imageInfo: ImageInfo? = null,
) : RegionDecoder {

    private val imageInfoValue: ImageInfo = imageInfo ?: decodeImageInfo()

    override fun getImageInfo(): ImageInfo = imageInfoValue

    private var cachedBitmap: Bitmap? = null
    private var cachedSampleSize: Int = 0

    companion object {
        private const val TAG = "AvifRegionDecoder"
        private const val MAX_DECODE_DIM = 4096
    }

    private fun readBytes(): ByteArray? {
        return try {
            imageSource.context.contentResolver.openInputStream(imageSource.uri)?.use { stream ->
                stream.readBytes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "readBytes failed: ${e.message}")
            null
        }
    }

    private fun decodeImageInfo(): ImageInfo {
        val bytes = readBytes()
        if (bytes != null) {
            // libavif 尺寸探测（不抛异常），覆盖 4:2:2/4:4:4 与 12-bit 样本
            val size = HeifCoder().getSize(bytes)
            if (size != null && size.width > 0 && size.height > 0) {
                return ImageInfo(
                    width = size.width,
                    height = size.height,
                    mimeType = "image/avif"
                )
            }
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        imageSource.openSource().use { source ->
            BufferedInputStream(source.buffer().inputStream()).use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        }
        return ImageInfo(
            width = options.outWidth,
            height = options.outHeight,
            mimeType = options.outMimeType ?: "image/avif"
        )
    }

    override fun prepare() = Unit

    override fun decodeRegion(region: IntRectCompat, sampleSize: Int): TileBitmap {
        val currentSampleSize = sampleSize.coerceAtLeast(1)

        if (cachedBitmap == null || cachedSampleSize != currentSampleSize) {
            cachedBitmap?.let { if (!it.isRecycled) it.recycle() }
            cachedBitmap = null
            try {
                val imgWidth = imageInfoValue.width.coerceAtLeast(1)
                val imgHeight = imageInfoValue.height.coerceAtLeast(1)

                val targetWidth = (imgWidth / currentSampleSize).coerceAtLeast(1)
                val targetHeight = (imgHeight / currentSampleSize).coerceAtLeast(1)
                val maxDim = maxOf(targetWidth, targetHeight)
                val scale = if (maxDim > MAX_DECODE_DIM) {
                    MAX_DECODE_DIM.toFloat() / maxDim
                } else 1f

                val finalWidth = (targetWidth * scale).toInt().coerceAtLeast(1)
                val finalHeight = (targetHeight * scale).toInt().coerceAtLeast(1)

                val bytes = readBytes()
                    ?: return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                // libavif 采样解码：支持 4:2:2/4:4:4 与 12-bit（原生不支持的部分）
                cachedBitmap = HeifCoder().decodeSampled(bytes, finalWidth, finalHeight)
                cachedSampleSize = currentSampleSize
            } catch (e: Exception) {
                Log.w(TAG, "Decode failed at sampleSize=$currentSampleSize: ${e.message}", e)
            }
        }

        val bitmap = cachedBitmap
            ?: return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        val scaleX = bitmap.width.toFloat() / imageInfoValue.width.toFloat()
        val scaleY = bitmap.height.toFloat() / imageInfoValue.height.toFloat()
        val cropLeft = (region.left * scaleX).toInt()
        val cropTop = (region.top * scaleY).toInt()
        val cropWidth = (region.width * scaleX).toInt().coerceAtMost(bitmap.width - cropLeft)
        val cropHeight = (region.height * scaleY).toInt().coerceAtMost(bitmap.height - cropTop)

        val cropped = Bitmap.createBitmap(
            bitmap,
            cropLeft, cropTop, cropWidth.coerceAtLeast(1), cropHeight.coerceAtLeast(1)
        )
        return cropped.copy(cropped.config ?: Bitmap.Config.ARGB_8888, false) ?: cropped
    }

    override fun close() {
        cachedBitmap?.recycle()
        cachedBitmap = null
    }

    override fun copy(): RegionDecoder = AvifRegionDecoder(
        subsamplingImage = subsamplingImage,
        imageSource = imageSource,
        imageInfo = imageInfoValue,
    )

    override fun equals(other: Any?): Boolean = this === other ||
        (other is AvifRegionDecoder && subsamplingImage == other.subsamplingImage)

    override fun hashCode(): Int = subsamplingImage.hashCode()

    override fun toString(): String = "AvifRegionDecoder(subsamplingImage=$subsamplingImage)"

    class Factory : RegionDecoder.Factory {

        override suspend fun accept(subsamplingImage: SubsamplingImage): Boolean {
            return checkSupport(subsamplingImage.imageInfo?.mimeType.orEmpty()) == true
        }

        override fun checkSupport(mimeType: String): Boolean? {
            // AVIF 单独走 libavif：原生解码器不支持 4:2:2/4:4:4 与 12-bit 样本
            return if (mimeType == "image/avif") {
                true
            } else {
                null
            }
        }

        override suspend fun create(
            subsamplingImage: SubsamplingImage,
            imageSource: ImageSource,
        ): AvifRegionDecoder {
            require(imageSource is ContentImageSource) {
                "AvifRegionDecoder requires ContentImageSource, got ${imageSource::class.simpleName}"
            }
            return AvifRegionDecoder(
                subsamplingImage = subsamplingImage,
                imageSource = imageSource,
            )
        }

        override fun equals(other: Any?): Boolean = other is Factory

        override fun hashCode(): Int = this::class.hashCode()

        override fun toString(): String = "AvifRegionDecoder"
    }
}
