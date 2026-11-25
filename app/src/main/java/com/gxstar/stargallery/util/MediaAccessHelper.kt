package com.gxstar.stargallery.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 媒体权限访问级别判定。
 *
 * Android 14（API 34）引入「部分照片访问」：用户在权限弹窗里选「选择部分」后，
 * 系统只授予 READ_MEDIA_VISUAL_USER_SELECTED，READ_MEDIA_IMAGES/VIDEO 不授予。
 *
 * 判据（manifest 必须已声明 READ_MEDIA_VISUAL_USER_SELECTED，见 AndroidManifest.xml）：
 * - FULL    ：READ_MEDIA_IMAGES 已授予（图片）→ 可全量读取
 * - PARTIAL ：VISUAL_USER_SELECTED 已授予但 READ_MEDIA_IMAGES 未授予 → 只能读用户勾选的照片
 * - NONE    ：两者都未授予
 *
 * 兼容说明：若 manifest 未声明 VISUAL_USER_SELECTED（旧版本兼容路径），
 * 系统会给 READ_MEDIA_IMAGES 附加 REVOKED_COMPAT 标志——checkSelfPermission 返回
 * granted 但实际访问受限。此时应用本判据会误判为 FULL，这正是 H1 的根因；
 * 声明权限后系统走标准模型，误判消失。为稳妥起见，PAGE 仍用 VISUAL_USER_SELECTED
 * 是否授予作为部分访问的强信号。
 */
object MediaAccessHelper {

    enum class AccessLevel { NONE, PARTIAL, FULL }

    /** 权限请求数组：三档媒体权限一次申请，避免多次弹框 */
    val REQUIRED_PERMISSIONS: Array<String>
        get() = buildList {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            }
            add(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }.toTypedArray()

    fun getAccessLevel(context: Context): AccessLevel {
        val imagesGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_MEDIA_IMAGES
        ) == PackageManager.PERMISSION_GRANTED

        if (imagesGranted) return AccessLevel.FULL

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val partialGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ) == PackageManager.PERMISSION_GRANTED
            if (partialGranted) return AccessLevel.PARTIAL
        }

        return AccessLevel.NONE
    }

    fun hasAnyAccess(context: Context): Boolean = getAccessLevel(context) != AccessLevel.NONE

    fun isPartialAccess(context: Context): Boolean = getAccessLevel(context) == AccessLevel.PARTIAL
}
