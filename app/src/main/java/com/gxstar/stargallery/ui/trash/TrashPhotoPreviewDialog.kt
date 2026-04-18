package com.gxstar.stargallery.ui.trash

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.github.panpf.zoomimage.ZoomImageView
import com.gxstar.stargallery.R
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.data.repository.MediaRepository
import com.gxstar.stargallery.databinding.DialogTrashPhotoPreviewBinding
import com.gxstar.stargallery.ui.photos.PhotosFragment
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class TrashPhotoPreviewDialog : DialogFragment() {

    private var _binding: DialogTrashPhotoPreviewBinding? = null
    private val binding get() = _binding!!

    private var photo: Photo? = null
    private var onActionComplete: (() -> Unit)? = null

    @Inject
    lateinit var mediaRepository: MediaRepository

    private val restoreRequestLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == AppCompatActivity.RESULT_OK) {
            Toast.makeText(requireContext(), R.string.restored, Toast.LENGTH_SHORT).show()
            setFragmentResult(PhotosFragment.REQUEST_KEY_PHOTO_DELETED, Bundle.EMPTY)
            onActionComplete?.invoke()
            dismiss()
        }
    }

    private val deleteRequestLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == AppCompatActivity.RESULT_OK) {
            Toast.makeText(requireContext(), R.string.deleted, Toast.LENGTH_SHORT).show()
            setFragmentResult(PhotosFragment.REQUEST_KEY_PHOTO_DELETED, Bundle.EMPTY)
            onActionComplete?.invoke()
            dismiss()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.setWindowAnimations(R.style.DialogAnimation)
        return dialog
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogTrashPhotoPreviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            dismiss()
        }

        binding.btnRestore.setOnClickListener {
            restorePhoto()
        }

        binding.btnDelete.setOnClickListener {
            deletePhoto()
        }

        photo?.let { loadImage(it) }
    }

    private fun loadImage(photo: Photo) {
        // 使用 ZoomImageView 加载图片，默认配置已足够

        // 使用 Glide 加载图片到 ZoomImageView
        Glide.with(requireContext())
            .load(photo.uri)
            .placeholder(android.R.color.black)
            .error(android.R.color.darker_gray)
            .into(object : CustomTarget<android.graphics.drawable.Drawable>() {
                override fun onResourceReady(
                    resource: android.graphics.drawable.Drawable,
                    transition: Transition<in android.graphics.drawable.Drawable>?
                ) {
                    binding.ivPhoto.setImageDrawable(resource)
                }

                override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {
                    binding.ivPhoto.setImageDrawable(placeholder)
                }

                override fun onLoadFailed(errorDrawable: android.graphics.drawable.Drawable?) {
                    binding.ivPhoto.setImageDrawable(errorDrawable)
                }
            })
    }

    private fun restorePhoto() {
        photo?.let { p ->
            try {
                val intentSender = mediaRepository.restorePhotos(listOf(p))
                intentSender?.let {
                    restoreRequestLauncher.launch(IntentSenderRequest.Builder(it).build())
                } ?: run {
                    Toast.makeText(requireContext(), R.string.restored, Toast.LENGTH_SHORT).show()
                    setFragmentResult(PhotosFragment.REQUEST_KEY_PHOTO_DELETED, Bundle.EMPTY)
                    onActionComplete?.invoke()
                    dismiss()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), R.string.restore_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deletePhoto() {
        photo?.let { p ->
            try {
                val intentSender = mediaRepository.deletePhotos(listOf(p))
                intentSender?.let {
                    deleteRequestLauncher.launch(IntentSenderRequest.Builder(it).build())
                } ?: run {
                    Toast.makeText(requireContext(), R.string.deleted, Toast.LENGTH_SHORT).show()
                    setFragmentResult(PhotosFragment.REQUEST_KEY_PHOTO_DELETED, Bundle.EMPTY)
                    onActionComplete?.invoke()
                    dismiss()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), R.string.delete_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        // 清理 Glide 加载的图片
        Glide.with(requireContext()).clear(binding.ivPhoto)
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "TrashPhotoPreviewDialog"

        fun newInstance(photo: Photo, onActionComplete: (() -> Unit)? = null): TrashPhotoPreviewDialog {
            return TrashPhotoPreviewDialog().apply {
                this.photo = photo
                this.onActionComplete = onActionComplete
            }
        }
    }
}
