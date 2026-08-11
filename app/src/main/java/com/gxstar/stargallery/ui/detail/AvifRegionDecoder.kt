package com.gxstar.stargallery.ui.detail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.util.Log
import com.github.panpf.zoomimage.subsampling.ContentImageSource
import com.github.panpf.zoomimage.subsampling.ImageInfo
import com.github.panpf.zoomimage.subsampling.ImageSource
import com.github.panpf.zoomimage.subsampling.RegionDecoder
import com.github.panpf.zoomimage.subsampling.SubsamplingImage
import com.github.panpf.zoomimage.subsampling.TileBitmap
import com.github.panpf.zoomimage.util.IntRectCompat
import java.io.BufferedInputStream
import okio.buffer
import okio.use

/**
 * AVIF 大图区域解码器（minSdk 35 原生实现）
 *
 * 背景：BitmapRegionDecoder 的 AVIF 区域解码是 Android 16（API 36+）才具备的能力
 * （2025-09 合入 Skia，Bug 435430895），API 35 上 AVIF 只能"整帧解码 + 缩放 + 裁切"，
 * 因此 AVIF 子采样必须保留自定义 RegionDecoder（平台边界，非实现问题）。
 *
 * 实现要点（v3 R1 修订）：
 * - 内部改用 ImageDecoder（原生 AVIF 解码，API 34+ 平台强制），替代 avif-coder（HeifCoder）
 * - decodeRegion 强制 setTargetColorSpace(SRGB)：10-bit AVIF 源若输出 RGBA_F16（8B/px）
 *   会使 tile 内存翻倍，转换到 sRGB 输出 8-bit tile（4B/px），性能回到基线
 * - tile 不做 HDR 是正确设计：HDR 由详情页整图路径（ImageDecoder 保留 F16/gainmap）负责
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

    private fun decodeImageInfo(): ImageInfo {
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

                cachedBitmap = decodeSampledBitmap(finalWidth, finalHeight)
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

    /**
     * R1：ImageDecoder 原生解码 AVIF，强制 sRGB 输出
     * 避免 10-bit AVIF 源输出 RGBA_F16（8B/px）导致 tile 内存翻倍
     */
    private fun decodeSampledBitmap(targetWidth: Int, targetHeight: Int): Bitmap? {
        return try {
            val source = ImageDecoder.createSource(imageSource.context.contentResolver, imageSource.uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.setTargetSize(targetWidth, targetHeight)
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            }
        } catch (e: Exception) {
            Log.w(TAG, "decodeSampled failed: ${e.message}", e)
            null
        }
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
            // minSdk 35：AVIF 原生解码为平台强制（API 34+），无需版本判断
            // HEIF 走 ZoomImage 内置 BitmapRegionDecoder（原生），不在此注册
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
