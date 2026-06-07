package com.gxstar.stargallery.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.gxstar.stargallery.R
import com.gxstar.stargallery.data.repository.MediaRepository
import com.gxstar.stargallery.databinding.FragmentExcludedAlbumsBinding
import com.gxstar.stargallery.util.ExcludedAlbumManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class ExcludedAlbumsFragment : Fragment() {

    private var _binding: FragmentExcludedAlbumsBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var excludedAlbumManager: ExcludedAlbumManager

    @Inject
    lateinit var mediaRepository: MediaRepository

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExcludedAlbumsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        refreshList()

        binding.btnAdd.setOnClickListener { showAlbumPickerDialog() }
    }

    private fun refreshList() {
        val excludedIds = excludedAlbumManager.excludedBucketIds.value
        binding.layoutExcludedList.removeAllViews()

        if (excludedIds.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.layoutExcludedList.visibility = View.GONE
            return
        }

        binding.tvEmpty.visibility = View.GONE
        binding.layoutExcludedList.visibility = View.VISIBLE

        lifecycleScope.launch {
            val allAlbums = withContext(Dispatchers.IO) { mediaRepository.getAlbums() }
            val albumMap = allAlbums.associateBy { it.id }

            for (bucketId in excludedIds.sorted()) {
                val album = albumMap[bucketId]
                val name = album?.name ?: "Album #$bucketId"
                val count = album?.photoCount ?: 0
                addAlbumItem(bucketId, name, count)
            }
        }
    }

    private fun addAlbumItem(bucketId: Long, name: String, count: Int) {
        val spacingLg = resources.getDimensionPixelSize(R.dimen.spacing_lg)
        val spacingSm = resources.getDimensionPixelSize(R.dimen.spacing_sm)

        val itemLayout = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(spacingLg, spacingSm, spacingLg, spacingSm)
        }

        val nameText = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = "$name ($count)"
            textSize = 16f
            setTextColor(resources.getColor(R.color.text_primary, requireContext().theme))
        }

        val removeBtn = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = getString(R.string.settings_excluded_remove)
            textSize = 14f
            setTextColor(resources.getColor(R.color.accent, requireContext().theme))
            setPadding(spacingSm, spacingSm, 0, spacingSm)
            setOnClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(name)
                    .setMessage(R.string.settings_excluded_remove_confirm)
                    .setPositiveButton(R.string.settings_excluded_remove) { _, _ ->
                        excludedAlbumManager.setExcluded(bucketId, false)
                        refreshList()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }

        itemLayout.addView(nameText)
        itemLayout.addView(removeBtn)
        binding.layoutExcludedList.addView(itemLayout)
    }

    private fun showAlbumPickerDialog() {
        lifecycleScope.launch {
            val allAlbums = withContext(Dispatchers.IO) { mediaRepository.getAlbums() }
            val excludedIds = excludedAlbumManager.excludedBucketIds.value

            val albumNames = allAlbums.map { it.name }.toTypedArray()
            val checkedItems = allAlbums.map { it.id in excludedIds }.toBooleanArray()

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_excluded_dialog_title)
                .setMultiChoiceItems(albumNames, checkedItems) { _, which, isChecked ->
                    checkedItems[which] = isChecked
                }
                .setPositiveButton(R.string.settings_excluded_confirm) { _, _ ->
                    val newExcludedIds = mutableSetOf<Long>()
                    for (i in allAlbums.indices) {
                        if (checkedItems[i]) newExcludedIds.add(allAlbums[i].id)
                    }
                    excludedAlbumManager.setAllExcluded(newExcludedIds)
                    refreshList()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
