package com.gxstar.stargallery

import android.content.Context
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
 * 注意：不再开启 setUriImageDecoderEnabled(true)。该开关会让 Glide 对
 * content:// Uri 模型优先走 ImageDecoder 解码路径，而 ImageDecoder 在部分
 * 场景/格式下不会回退到第三方 ResourceDecoder（如 jxl-coder），导致 JXL
 * 在 Glide 层解码失败。保留此 @GlideModule 注解（KSP 索引的前提）即可让
 * jxl-coder 的解码器正常生效。
 */
@GlideModule
class StarGalleryGlideModule : AppGlideModule() {

    override fun applyOptions(context: Context, builder: GlideBuilder) {
        // 刻意不开启 setUriImageDecoderEnabled，避免绕过 jxl-coder 等第三方解码器。
    }
}
