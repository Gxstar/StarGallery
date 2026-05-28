package com.gxstar.stargallery.ui.detail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.os.Build
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

class AvifRegionDecoder(
    override val subsamplingImage: SubsamplingImage,
    val imageSource: ContentImageSource,
    imageInfo: ImageInfo? = subsamplingImage.imageInfo,
) : RegionDecoder {

    override val imageInfo: ImageInfo by lazy { imageInfo ?: decodeImageInfo() }

    private var cachedBitmap: Bitmap? = null
    private var cachedSampleSize: Int = 0

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

        // AVIF 不支持 BitmapRegionDecoder，改用全图缩放 + 裁切
        // 缓存全图解码结果，仅在 sampleSize 变化时重新解码
        if (cachedBitmap == null || cachedSampleSize != currentSampleSize) {
            cachedBitmap?.recycle()
            val imgWidth = imageInfo.width.coerceAtLeast(1)
            val imgHeight = imageInfo.height.coerceAtLeast(1)
            val source = ImageDecoder.createSource(
                imageSource.context.contentResolver,
                imageSource.uri
            )
            cachedBitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.setTargetSize(
                    (imgWidth / currentSampleSize).coerceAtLeast(1),
                    (imgHeight / currentSampleSize).coerceAtLeast(1)
                )
            }
            cachedSampleSize = currentSampleSize
        }

        // 从缓存的全图中裁切出需要的区域
        val scaleX = cachedBitmap!!.width.toFloat() / imageInfo.width.toFloat()
        val scaleY = cachedBitmap!!.height.toFloat() / imageInfo.height.toFloat()
        val cropLeft = (region.left * scaleX).toInt()
        val cropTop = (region.top * scaleY).toInt()
        val cropWidth = (region.width * scaleX).toInt().coerceAtMost(cachedBitmap!!.width - cropLeft)
        val cropHeight = (region.height * scaleY).toInt().coerceAtMost(cachedBitmap!!.height - cropTop)
        return Bitmap.createBitmap(
            cachedBitmap!!,
            cropLeft, cropTop, cropWidth.coerceAtLeast(1), cropHeight.coerceAtLeast(1)
        )
    }

    override fun close() {
        cachedBitmap?.recycle()
        cachedBitmap = null
    }

    override fun copy(): RegionDecoder = AvifRegionDecoder(
        subsamplingImage = subsamplingImage,
        imageSource = imageSource,
        imageInfo = imageInfo,
    )

    override fun equals(other: Any?): Boolean = this === other ||
        (other is AvifRegionDecoder && subsamplingImage == other.subsamplingImage)

    override fun hashCode(): Int = subsamplingImage.hashCode()

    override fun toString(): String = "AvifRegionDecoder(subsamplingImage=$subsamplingImage)"

    class Factory : RegionDecoder.Factory {

        override suspend fun accept(subsamplingImage: SubsamplingImage): Boolean = true

        override fun checkSupport(mimeType: String): Boolean? {
            return if (mimeType == "image/avif" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                true
            } else {
                null
            }
        }

        override fun create(
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
