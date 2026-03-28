package com.gxstar.stargallery.ui.detail

import android.app.Activity
import android.content.IntentSender
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.gxstar.stargallery.R
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.databinding.FragmentPhotoDetailBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class PhotoDetailFragment : Fragment() {

    private var _binding: FragmentPhotoDetailBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PhotoDetailViewModel by viewModels()
    
    private val deleteRequestLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(requireContext(), R.string.deleted, Toast.LENGTH_SHORT).show()
            findNavController().navigateUp()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPhotoDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViews()
        observeData()
    }

    private fun setupViews() {
        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.btnFavorite.setOnClickListener {
            viewModel.toggleFavorite()
        }

        binding.btnDelete.setOnClickListener {
            viewModel.deletePhoto { intentSender ->
                if (intentSender != null) {
                    deleteRequestLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                } else {
                    Toast.makeText(requireContext(), "删除失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.photo.collect { photo ->
                    photo?.let {
                        loadImage(it)
                        updateFavoriteIcon(it.isFavorite)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.dateText.collect { date ->
                    binding.tvDate.text = date
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.infoText.collect { info ->
                    binding.tvInfo.text = info
                }
            }
        }
    }

    private fun loadImage(photo: Photo) {
        Glide.with(binding.ivPhoto.context)
            .load(photo.uri)
            .into(binding.ivPhoto)
    }

    private fun updateFavoriteIcon(isFavorite: Boolean) {
        val iconRes = if (isFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite
        binding.ivFavorite.setImageResource(iconRes)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
