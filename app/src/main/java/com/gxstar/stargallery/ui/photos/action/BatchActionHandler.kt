package com.gxstar.stargallery.ui.photos.action

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.gxstar.stargallery.R
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.data.repository.MediaRepository
import com.gxstar.stargallery.ui.common.DeleteOptionsBottomSheet
import kotlinx.coroutines.launch

/**
 * 批量操作处理器
 * 处理分享、收藏、删除等批量操作
 */
class BatchActionHandler(
    private val fragment: Fragment,
    private val mediaRepository: MediaRepository,
    private val fragmentManager: FragmentManager
) {

    /**
     * 分享选中的照片
     */
    fun sharePhotos(photos: List<Photo>) {
        if (photos.isEmpty()) return

        val uris = ArrayList<Uri>()
        photos.forEach { photo ->
            uris.add(photo.uri)
        }

        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        fragment.requireContext().startActivity(
            Intent.createChooser(intent, fragment.getString(R.string.send))
        )
    }

    /**
     * 收藏/取消收藏照片
     * @return 是否需要启动 IntentSender
     */
    fun favoritePhotos(
        photos: List<Photo>,
        favoriteLauncher: ActivityResultLauncher<IntentSenderRequest>,
        onComplete: (successMessage: String?) -> Unit
    ): Boolean {
        if (photos.isEmpty()) {
            onComplete(null)
            return false
        }

        val photosToFavorite = photos.filter { !it.isFavorite }
        val photosToUnfavorite = photos.filter { it.isFavorite }

        val actionType = when {
            photosToFavorite.isNotEmpty() && photosToUnfavorite.isNotEmpty() -> FAVORITE_ACTION_MIXED
            photosToFavorite.isNotEmpty() -> FAVORITE_ACTION_ADD
            photosToUnfavorite.isNotEmpty() -> FAVORITE_ACTION_REMOVE
            else -> FAVORITE_ACTION_NONE
        }

        if (actionType == FAVORITE_ACTION_NONE) {
            onComplete(null)
            return false
        }

        var hasRequest = false

        if (photosToFavorite.isNotEmpty()) {
            mediaRepository.setFavorite(photosToFavorite, true)?.let { intentSender ->
                favoriteLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                hasRequest = true
            }
        }

        if (photosToUnfavorite.isNotEmpty()) {
            mediaRepository.setFavorite(photosToUnfavorite, false)?.let { intentSender ->
                favoriteLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                hasRequest = true
            }
        }

        if (!hasRequest) {
            Toast.makeText(
                fragment.requireContext(),
                R.string.add_to_favorite_failed,
                Toast.LENGTH_SHORT
            ).show()
            onComplete(null)
        }

        return hasRequest
    }

    /**
     * 显示删除选项对话框
     */
    fun showDeleteOptions(
        photos: List<Photo>,
        trashLauncher: ActivityResultLauncher<IntentSenderRequest>,
        deleteLauncher: ActivityResultLauncher<IntentSenderRequest>,
        onComplete: () -> Unit
    ) {
        if (photos.isEmpty()) {
            onComplete()
            return
        }

        DeleteOptionsBottomSheet.newInstance(
            onMoveToTrash = {
                moveToTrash(photos, trashLauncher, onComplete)
            },
            onDeletePermanently = {
                deletePermanently(photos, deleteLauncher, onComplete)
            }
        ).show(fragmentManager, DeleteOptionsBottomSheet.TAG)
    }

    /**
     * 移至回收站
     */
    private fun moveToTrash(
        photos: List<Photo>,
        trashLauncher: ActivityResultLauncher<IntentSenderRequest>,
        onComplete: () -> Unit
    ) {
        mediaRepository.trashPhotos(photos)?.let { intentSender ->
            try {
                trashLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(
                    fragment.requireContext(),
                    R.string.move_to_trash_failed,
                    Toast.LENGTH_SHORT
                ).show()
                onComplete()
            }
        } ?: run {
            Toast.makeText(
                fragment.requireContext(),
                R.string.move_to_trash_failed,
                Toast.LENGTH_SHORT
            ).show()
            onComplete()
        }
    }

    /**
     * 永久删除
     */
    private fun deletePermanently(
        photos: List<Photo>,
        deleteLauncher: ActivityResultLauncher<IntentSenderRequest>,
        onComplete: () -> Unit
    ) {
        mediaRepository.deletePhotos(photos)?.let { intentSender ->
            try {
                deleteLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(
                    fragment.requireContext(),
                    R.string.delete_failed,
                    Toast.LENGTH_SHORT
                ).show()
                onComplete()
            }
        } ?: run {
            Toast.makeText(
                fragment.requireContext(),
                R.string.delete_failed,
                Toast.LENGTH_SHORT
            ).show()
            onComplete()
        }
    }

    /**
     * 处理 IntentSender 结果
     */
    fun handleIntentSenderResult(
        resultCode: Int,
        actionType: Int,
        onSuccess: () -> Unit
    ) {
        if (resultCode == Activity.RESULT_OK) {
            val message = when (actionType) {
                FAVORITE_ACTION_ADD -> fragment.getString(R.string.added_to_favorite)
                FAVORITE_ACTION_REMOVE -> fragment.getString(R.string.removed_from_favorite)
                FAVORITE_ACTION_MIXED -> fragment.getString(R.string.favorite_toggled)
                DELETE_ACTION_TRASH -> fragment.getString(R.string.moved_to_trash)
                DELETE_ACTION_PERMANENT -> fragment.getString(R.string.deleted)
                else -> null
            }
            message?.let {
                Toast.makeText(fragment.requireContext(), it, Toast.LENGTH_SHORT).show()
            }
            onSuccess()
        }
    }

    companion object {
        const val FAVORITE_ACTION_NONE = 0
        const val FAVORITE_ACTION_ADD = 1
        const val FAVORITE_ACTION_REMOVE = 2
        const val FAVORITE_ACTION_MIXED = 3
        const val DELETE_ACTION_TRASH = 4
        const val DELETE_ACTION_PERMANENT = 5
    }
}
