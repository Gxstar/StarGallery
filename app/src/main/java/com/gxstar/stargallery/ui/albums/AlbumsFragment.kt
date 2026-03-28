package com.gxstar.stargallery.ui.albums

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.gxstar.stargallery.data.model.Album
import com.gxstar.stargallery.databinding.FragmentAlbumsBinding
import com.gxstar.stargallery.databinding.ItemAlbumBinding
import com.permissionx.guolindev.PermissionX
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class AlbumsFragment : Fragment() {

    private var _binding: FragmentAlbumsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AlbumsViewModel by viewModels()
    private lateinit var adapter: AlbumAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAlbumsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        observeData()
        checkPermissions()
    }

    private fun checkPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        PermissionX.init(this)
            .permissions(*permissions)
            .request { allGranted, _, _ ->
                if (allGranted) {
                    viewModel.loadAlbums()
                }
            }
    }

    private fun setupRecyclerView() {
        adapter = AlbumAdapter { album ->
            val action = AlbumsFragmentDirections.actionAlbumsFragmentToAlbumDetailFragment(album.id, album.name)
            findNavController().navigate(action)
        }
        binding.rvAlbums.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.rvAlbums.adapter = adapter
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.albums.collect { albums ->
                    adapter.submitList(albums)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class AlbumAdapter(
    private val onAlbumClick: (Album) -> Unit
) : RecyclerView.Adapter<AlbumAdapter.AlbumViewHolder>() {

    private val items = mutableListOf<Album>()

    fun submitList(albums: List<Album>) {
        items.clear()
        items.addAll(albums)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumViewHolder {
        return AlbumViewHolder(
            ItemAlbumBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )
    }

    override fun onBindViewHolder(holder: AlbumViewHolder, position: Int) {
        holder.bind(items[position], onAlbumClick)
    }

    override fun getItemCount(): Int = items.size

    class AlbumViewHolder(private val binding: ItemAlbumBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(album: Album, onClick: (Album) -> Unit) {
            binding.tvName.text = album.name
            binding.tvCount.text = "${album.photoCount}张"
            
            Glide.with(binding.ivCover.context)
                .load(album.coverUri)
                .centerCrop()
                .into(binding.ivCover)
            
            binding.root.setOnClickListener { onClick(album) }
        }
    }
}
