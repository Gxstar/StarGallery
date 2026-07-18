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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.github.panpf.zoomimage.ZoomImageView
import com.github.panpf.zoomimage.subsampling.ContentImageSource
import com.gxstar.stargallery.R
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.data.repository.MediaRepository
import com.gxstar.stargallery.databinding.DialogTrashPhotoPreviewBinding
import com.gxstar.stargallery.ui.detail.AvifRegionDecoder
import com.gxstar.stargallery.ui.photos.PhotosFragment
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.max

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

        applySystemBarInsets()

        photo?.let { loadImage(it) }
    }

    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomBar) { v, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = systemBars.bottom + dpToPx(16))
            windowInsets
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun loadImage(photo: Photo) {
        // 大图/Raw 使用 ZoomImageView 子采样加载，避免一次性全量解码占用过多内存；
        // JXL 不支持 BitmapRegionDecoder，无法子采样，回退到 Glide 全量加载。
        val maxDimension = max(photo.width, photo.height)
        val needSubsampling = !photo.isJxl && (maxDimension >= 2000 || photo.isRaw)

        if (needSubsampling) {
            loadWithSubsampling(photo)
        } else {
            loadFullImage(photo)
        }
    }

    /**
     * 子采样加载大图：先 Glide 预览，再启用 ZoomImageView 子采样
     * （ZoomImage 自动按缩放级别只加载可视区域的 tiles）。
     */
    private fun loadWithSubsampling(photo: Photo) {
        Glide.with(requireContext())
            .asBitmap()
            .load(photo.uri)
            .placeholder(android.R.color.black)
            .override(1200)
            .fitCenter()
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .into(object : CustomTarget<android.graphics.Bitmap>() {
                override fun onResourceReady(
                    resource: android.graphics.Bitmap,
                    transition: Transition<in android.graphics.Bitmap>?
                ) {
                    binding.ivPhoto.setImageBitmap(resource)
                    enableSubsampling(photo)
                }

                override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {
                    binding.ivPhoto.setImageDrawable(placeholder)
                }

                override fun onLoadFailed(errorDrawable: android.graphics.drawable.Drawable?) {
                    binding.ivPhoto.setImageDrawable(errorDrawable)
                }
            })
    }

    /**
     * 直接 Glide 加载完整图片（适用于小图 / JXL 回退）。
     */
    private fun loadFullImage(photo: Photo) {
        Glide.with(requireContext())
            .load(photo.uri)
            .placeholder(android.R.color.black)
            .error(android.R.color.darker_gray)
            .fitCenter()
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

    /**
     * 启用子采样功能：注册 AVIF 自定义区域解码器，并交给 ZoomImageView 接管大图。
     */
    private fun enableSubsampling(photo: Photo) {
        try {
            binding.ivPhoto.subsampling.setRegionDecoders(listOf(AvifRegionDecoder.Factory()))
            val imageSource = ContentImageSource(requireContext(), photo.uri)
            binding.ivPhoto.setSubsamplingImage(imageSource)
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
        // 清理子采样资源（释放大图解码器）
        binding.ivPhoto.setSubsamplingImage(null as com.github.panpf.zoomimage.subsampling.ImageSource?)
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
