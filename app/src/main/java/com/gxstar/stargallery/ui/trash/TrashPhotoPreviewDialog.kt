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
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
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
        // 直接使用 SubsamplingScaleImageView 加载大图
        try {
            binding.ivPhoto.setImage(ImageSource.uri(photo.uri))
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 设置双击缩放和手势缩放
        binding.ivPhoto.maxScale = 5f
        binding.ivPhoto.setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE)
    }

    private fun restorePhoto() {
        val currentPhoto = photo ?: return
        mediaRepository.restorePhotos(listOf(currentPhoto))?.let { intentSender ->
            try {
                restoreRequestLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(requireContext(), R.string.restore_failed, Toast.LENGTH_SHORT).show()
            }
        } ?: run {
            Toast.makeText(requireContext(), R.string.restore_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun deletePhoto() {
        val currentPhoto = photo ?: return
        mediaRepository.deletePhotos(listOf(currentPhoto))?.let { intentSender ->
            try {
                deleteRequestLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(requireContext(), R.string.delete_failed, Toast.LENGTH_SHORT).show()
            }
        } ?: run {
            Toast.makeText(requireContext(), R.string.delete_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    fun setPhoto(photo: Photo) {
        this.photo = photo
    }

    fun setOnActionComplete(listener: () -> Unit) {
        onActionComplete = listener
    }

    companion object {
        const val TAG = "TrashPhotoPreviewDialog"

        fun newInstance(photo: Photo, onActionComplete: () -> Unit): TrashPhotoPreviewDialog {
            return TrashPhotoPreviewDialog().apply {
                setPhoto(photo)
                setOnActionComplete(onActionComplete)
            }
        }
    }
}
