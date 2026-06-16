package com.gxstar.stargallery.ui.hidden

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gxstar.stargallery.R
import com.gxstar.stargallery.data.model.Photo
import com.gxstar.stargallery.databinding.FragmentHiddenBinding
import com.gxstar.stargallery.ui.common.BaseSelectionManager
import com.gxstar.stargallery.ui.photos.GridSpacingItemDecoration
import com.gxstar.stargallery.ui.detail.PhotoDetailListCache
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class HiddenFragment : Fragment() {

    private var _binding: FragmentHiddenBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HiddenViewModel by viewModels()
    private lateinit var adapter: HiddenAdapter
    private lateinit var gridLayoutManager: GridLayoutManager
    private lateinit var selectionManager: HiddenSelectionManager

    @Inject
    lateinit var photoDetailListCache: PhotoDetailListCache

    private var currentSpanCount = 4
    private var itemSize = 0
    private var isAuthenticated = false

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            selectionManager.exitSelectionMode()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHiddenBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(this, backPressedCallback)
        calculateItemSize()
        setupRecyclerView()
        setupClickListeners()
        showAuthentication()
    }

    private fun showAuthentication() {
        val executor = ContextCompat.getMainExecutor(requireContext())
        val biometricPrompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                isAuthenticated = true
                observeData()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                if (!isAuthenticated) {
                    findNavController().navigateUp()
                }
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.hidden_auth_title))
            .setSubtitle(getString(R.string.hidden_auth_subtitle))
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        try {
            biometricPrompt.authenticate(promptInfo)
        } catch (_: Exception) {
            findNavController().navigateUp()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        calculateItemSize()
        adapter.updateItemSize(itemSize)
    }

    private fun calculateItemSize() {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val itemSpacing = dpToPx(2) * (currentSpanCount + 1)
        itemSize = (screenWidth - itemSpacing) / currentSpanCount
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun setupRecyclerView() {
        adapter = HiddenAdapter(
            itemSize = itemSize,
            onPhotoClick = { photo ->
                if (selectionManager.isInSelectionMode()) {
                    selectionManager.toggleSelection(photo)
                } else {
                    navigateToDetail(photo)
                }
            },
            onPhotoLongClick = { position -> selectionManager.startDragSelection(position) },
            isSelectionModeProvider = { selectionManager.isInSelectionMode() },
            isSelectedProvider = { position -> selectionManager.isSelectedPosition(position) }
        )

        gridLayoutManager = GridLayoutManager(requireContext(), currentSpanCount)

        binding.rvPhotos.layoutManager = gridLayoutManager
        binding.rvPhotos.adapter = adapter
        binding.rvPhotos.addItemDecoration(GridSpacingItemDecoration(currentSpanCount, dpToPx(2), true))
        binding.rvPhotos.setHasFixedSize(true)

        selectionManager = HiddenSelectionManager(binding.rvPhotos, adapter)
        selectionManager.init()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                selectionManager.isSelectionMode.collect { isSelectionMode ->
                    if (isSelectionMode) {
                        binding.normalToolbar.visibility = View.GONE
                        binding.selectionToolbar.visibility = View.VISIBLE
                    } else {
                        binding.normalToolbar.visibility = View.VISIBLE
                        binding.selectionToolbar.visibility = View.GONE
                    }
                    backPressedCallback.isEnabled = isSelectionMode
                    refreshVisibleItems()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                selectionManager.selectedCount.collect { count ->
                    binding.tvSelectionCount.text = getString(R.string.selected, count)
                }
            }
        }
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.btnCancel.setOnClickListener {
            selectionManager.exitSelectionMode()
        }

        binding.btnRestore.setOnClickListener {
            restoreSelectedPhotos()
        }
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.photos.collect { photos ->
                    adapter.submitList(photos)
                    binding.tvEmpty.visibility = if (photos.isEmpty()) View.VISIBLE else View.GONE
                    binding.rvPhotos.visibility = if (photos.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.photos.collect {
                    binding.tvSubtitle.text = getString(R.string.hidden_count, viewModel.getPhotoCount())
                }
            }
        }
    }

    private fun refreshVisibleItems() {
        val layoutManager = binding.rvPhotos.layoutManager as? GridLayoutManager ?: return
        val first = layoutManager.findFirstVisibleItemPosition()
        val last = layoutManager.findLastVisibleItemPosition()
        if (first != RecyclerView.NO_POSITION && last != RecyclerView.NO_POSITION) {
            adapter.notifyItemRangeChanged(first, last - first + 1, BaseSelectionManager.PAYLOAD_SELECTION_CHANGED)
        }
    }

    private fun navigateToDetail(photo: Photo) {
        // 把当前可见的隐藏照片列表写入缓存
        photoDetailListCache.put(viewModel.photos.value)

        val action = HiddenFragmentDirections.actionHiddenFragmentToPhotoDetailFragment(
            photoId = photo.id,
            initialPhoto = photo,
            favoritesOnly = false,
            filterCameraMake = null,
            filterCameraModel = null,
            filterLensModel = null
        )
        findNavController().navigate(action)
    }

    private fun restoreSelectedPhotos() {
        val selectedIds = selectionManager.selectedPhotoIds
        if (selectedIds.isEmpty()) return

        viewModel.unhidePhotos(selectedIds.toList())
        Toast.makeText(requireContext(), R.string.unhidden_success, Toast.LENGTH_SHORT).show()
        selectionManager.exitSelectionMode()
    }

    override fun onDestroyView() {
        selectionManager.clear()
        binding.rvPhotos.adapter = null
        binding.rvPhotos.layoutManager = null
        _binding = null
        super.onDestroyView()
    }
}
