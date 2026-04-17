package com.gxstar.stargallery.ui.photos.refresh

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 媒体库变化检测器
 * 使用 ContentObserver 实时监听 MediaStore 变化
 *
 * 优势：
 * - 实时响应：系统级回调，无需轮询
 * - 低功耗：仅在媒体库变化时触发
 * - 防抖机制：避免短时间内多次触发
 */
class MediaChangeDetector(
    private val lifecycleOwner: LifecycleOwner,
    private val context: Context,
    private val onChangeDetected: () -> Unit,
    private val shouldSkipRefresh: () -> Boolean = { false }
) : LifecycleEventObserver {

    private var mediaObserver: MediaContentObserver? = null
    private var debounceJob: Job? = null
    private var isRegistered = false

    companion object {
        private const val DEBOUNCE_MS = 500L  // 防抖时间
    }

    init {
        lifecycleOwner.lifecycle.addObserver(this)
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_START -> registerObserver()
            Lifecycle.Event.ON_STOP -> unregisterObserver()
            Lifecycle.Event.ON_DESTROY -> destroy()
            else -> {}
        }
    }

    /**
     * 注册 ContentObserver 监听
     */
    private fun registerObserver() {
        if (isRegistered) return
        
        try {
            mediaObserver = MediaContentObserver()
            
            // 监听图片变化
            context.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,  // notifyForDescendants - 监听子目录
                mediaObserver!!
            )
            
            // 监听视频变化
            context.contentResolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                true,
                mediaObserver!!
            )
            
            isRegistered = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 取消注册 ContentObserver
     */
    private fun unregisterObserver() {
        if (!isRegistered) return
        
        try {
            mediaObserver?.let { observer ->
                context.contentResolver.unregisterContentObserver(observer)
            }
            isRegistered = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun destroy() {
        unregisterObserver()
        debounceJob?.cancel()
        lifecycleOwner.lifecycle.removeObserver(this)
    }

    /**
     * 处理媒体变化（带防抖）
     */
    private fun onMediaChanged() {
        debounceJob?.cancel()
        debounceJob = lifecycleOwner.lifecycleScope.launch {
            delay(DEBOUNCE_MS)
            withContext(Dispatchers.Main) {
                if (!shouldSkipRefresh()) {
                    onChangeDetected()
                }
            }
        }
    }

    /**
     * 重置检测状态（用于手动刷新后）
     * ContentObserver 模式下无需重置，保留此方法以兼容现有调用
     */
    fun reset() {
        // ContentObserver 模式下无需额外操作
    }

    /**
     * ContentObserver 内部类
     */
    private inner class MediaContentObserver : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            onMediaChanged()
        }

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            onMediaChanged()
        }
    }
}

/**
 * 扩展函数：从 Fragment 创建 MediaChangeDetector
 */
fun Fragment.createMediaChangeDetector(onChangeDetected: () -> Unit): MediaChangeDetector {
    return MediaChangeDetector(
        lifecycleOwner = viewLifecycleOwner,
        context = requireContext(),
        onChangeDetected = onChangeDetected
    )
}
