package com.gxstar.stargallery

import android.content.Context
import android.os.Build
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.module.AppGlideModule

/**
 * AppGlideModule，用于触发 Glide KSP 注解处理器生成 GeneratedAppGlideModuleImpl
 * 索引类，从而自动注册所有依赖库提供的 LibraryGlideModule（例如 jxl-coder-glide
 * 的 JPEG XL 解码器）。
 *
 * 若无此模块，KSP 不会生成索引，jxl-coder 的 LibraryGlideModule 不会被注册，
 * Glide 将无法解码 JXL 图片（网格缩略图与详情页大图均显示失败）。
 *
 * applyOptions 中开启 Glide 5.0 的 ImageDecoder 解码路径（Android Q+）：
 * - setImageDecoderEnabledForBitmaps：用 ImageDecoder 解码 Bitmap，对新格式
 *   （HEIC/AVIF）支持更好、更省内存。
 * - setUriImageDecoderEnabled：直接通过 ImageDecoder 解码 content:// Uri
 *   （本项目照片均为 MediaStore Uri，正好命中）。
 * - setUseArrayPoolForImageDecoderByteBufferAllocation / setUseHeapBufferForImageDecoderWithInputStream：
 *   复用 Glide 的 ArrayPool / 堆缓冲，避免原生内存双重分配，降低 OOM 风险。
 */
@GlideModule
class StarGalleryGlideModule : AppGlideModule() {

    override fun applyOptions(context: Context, builder: GlideBuilder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setImageDecoderEnabledForBitmaps(true)
            builder.setUriImageDecoderEnabled(true)
            builder.setUseArrayPoolForImageDecoderByteBufferAllocation(true)
            builder.setUseHeapBufferForImageDecoderWithInputStream(true)
        }
    }
}
