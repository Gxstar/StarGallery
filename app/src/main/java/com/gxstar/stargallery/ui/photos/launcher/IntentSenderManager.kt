package com.gxstar.stargallery.ui.photos.launcher

import android.app.Activity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment

/**
 * IntentSender Launcher 管理器
 * 统一管理所有需要 IntentSender 的 Activity Result Launcher
 */
class IntentSenderManager(private val fragment: Fragment) {

    private var favoriteCallback: ((Boolean) -> Unit)? = null
    private var trashCallback: ((Boolean) -> Unit)? = null
    private var deleteCallback: ((Boolean) -> Unit)? = null

    /**
     * 收藏操作 Launcher
     */
    val favoriteLauncher: ActivityResultLauncher<IntentSenderRequest> =
        fragment.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val success = result.resultCode == Activity.RESULT_OK
            favoriteCallback?.invoke(success)
            favoriteCallback = null
        }

    /**
     * 移至回收站 Launcher
     */
    val trashLauncher: ActivityResultLauncher<IntentSenderRequest> =
        fragment.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val success = result.resultCode == Activity.RESULT_OK
            trashCallback?.invoke(success)
            trashCallback = null
        }

    /**
     * 永久删除 Launcher
     */
    val deleteLauncher: ActivityResultLauncher<IntentSenderRequest> =
        fragment.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val success = result.resultCode == Activity.RESULT_OK
            deleteCallback?.invoke(success)
            deleteCallback = null
        }

    /**
     * 设置收藏操作结果回调
     */
    fun setFavoriteCallback(onResult: (Boolean) -> Unit) {
        favoriteCallback = onResult
    }

    /**
     * 设置移至回收站操作结果回调
     */
    fun setTrashCallback(onResult: (Boolean) -> Unit) {
        trashCallback = onResult
    }

    /**
     * 设置永久删除操作结果回调
     */
    fun setDeleteCallback(onResult: (Boolean) -> Unit) {
        deleteCallback = onResult
    }

    /**
     * 启动收藏操作
     */
    fun launchFavorite(
        request: IntentSenderRequest,
        onResult: (Boolean) -> Unit
    ) {
        favoriteCallback = onResult
        favoriteLauncher.launch(request)
    }

    /**
     * 启动移至回收站操作
     */
    fun launchTrash(
        request: IntentSenderRequest,
        onResult: (Boolean) -> Unit
    ) {
        trashCallback = onResult
        trashLauncher.launch(request)
    }

    /**
     * 启动永久删除操作
     */
    fun launchDelete(
        request: IntentSenderRequest,
        onResult: (Boolean) -> Unit
    ) {
        deleteCallback = onResult
        deleteLauncher.launch(request)
    }
}
