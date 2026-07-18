package com.gxstar.stargallery.ui.detail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
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

                val source = ImageDecoder.createSource(
                    imageSource.context.contentResolver,
                    imageSource.uri
                )
                cachedBitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.setTargetSize(
                        (targetWidth * scale).toInt().coerceAtLeast(1),
                        (targetHeight * scale).toInt().coerceAtLeast(1)
                    )
                }
                cachedSampleSize = currentSampleSize
            } catch (e: Exception) {
                Log.w(TAG, "Decode failed at sampleSize=$currentSampleSize: ${e.message}")
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
            return if (mimeType == "image/avif" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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
