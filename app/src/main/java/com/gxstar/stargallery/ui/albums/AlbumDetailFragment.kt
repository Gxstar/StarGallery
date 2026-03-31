package com.gxstar.stargallery.ui.albums

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.databinding.FragmentPhotosBinding
import com.gxstar.stargallery.databinding.ItemPhotoBinding
import com.permissionx.guolindev.PermissionX
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class AlbumDetailFragment : Fragment() {

    private var _binding: FragmentPhotosBinding? = null
    private val binding get() = _binding!!

    private val args: AlbumDetailFragmentArgs by navArgs()
    private lateinit var adapter: AlbumPhotoAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPhotosBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.tvTitle.text = args.albumName
        setupRecyclerView()
        checkPermissions()
    }

    private fun checkPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        PermissionX.init(this)
            .permissions(*permissions)
            .request { allGranted, _, _ ->
                if (allGranted) {
                    loadPhotos()
                }
            }
    }

    private fun loadPhotos() {
        // 在实际应用中，这里应该通过 ViewModel 加载
    }

    private fun setupRecyclerView() {
        adapter = AlbumPhotoAdapter { photo ->
            // sortType 0 = DATE_TAKEN
            val action = AlbumDetailFragmentDirections.actionAlbumDetailFragmentToPhotoDetailFragment(photo.id, 0)
            findNavController().navigate(action)
        }
        binding.rvPhotos.layoutManager = GridLayoutManager(requireContext(), 4)
        binding.rvPhotos.adapter = adapter
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class AlbumPhotoAdapter(
    private val onPhotoClick: (Photo) -> Unit
) : RecyclerView.Adapter<AlbumPhotoAdapter.PhotoViewHolder>() {

    private val items = mutableListOf<Photo>()

    fun submitList(photos: List<Photo>) {
        items.clear()
        items.addAll(photos)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        return PhotoViewHolder(
            ItemPhotoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        holder.bind(items[position], onPhotoClick)
    }

    override fun getItemCount(): Int = items.size

    class PhotoViewHolder(private val binding: ItemPhotoBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(photo: Photo, onClick: (Photo) -> Unit) {
            Glide.with(binding.ivPhoto.context)
                .load(photo.uri)
                .centerCrop()
                .into(binding.ivPhoto)
            binding.root.setOnClickListener { onClick(photo) }
        }
    }
}
