package com.gxstar.stargallery.ui.detail

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * ExoPlayer 单例管理类
 * 用于在多个 ViewHolder 之间复用同一个播放器实例
 */
object ExoPlayerManager {
    private var exoPlayer: ExoPlayer? = null
    private var currentVideoId: Long? = null

    @OptIn(UnstableApi::class)
    fun getPlayer(context: Context): ExoPlayer {
        return exoPlayer ?: run {
            ExoPlayer.Builder(context).build().also {
                // 设置循环播放
                it.repeatMode = Player.REPEAT_MODE_ONE
                exoPlayer = it
            }
        }
    }

    fun getCurrentVideoId(): Long? = currentVideoId

    fun isPlaying(videoId: Long): Boolean {
        return currentVideoId == videoId && exoPlayer?.isPlaying == true
    }

    @OptIn(UnstableApi::class)
    fun play(videoId: Long, uri: android.net.Uri, autoPlay: Boolean = true) {
        currentVideoId = videoId
        exoPlayer?.apply {
            val mediaItem = MediaItem.fromUri(uri)
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = autoPlay
        }
    }

    fun pause() {
        exoPlayer?.pause()
    }

    fun release() {
        exoPlayer?.release()
        exoPlayer = null
        currentVideoId = null
    }

    fun clear() {
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        currentVideoId = null
    }
}
