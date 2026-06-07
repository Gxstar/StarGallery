package com.gxstar.stargallery.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.gxstar.stargallery.R
import com.gxstar.stargallery.databinding.FragmentSettingsBinding
import com.gxstar.stargallery.util.ExcludedAlbumManager
import com.gxstar.stargallery.util.LocaleManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var localeManager: LocaleManager

    @Inject
    lateinit var excludedAlbumManager: ExcludedAlbumManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        updateLanguageSummary()
        updateExcludedSummary()

        binding.itemLanguage.setOnClickListener { showLanguageDialog() }
        binding.itemExcludedAlbums.setOnClickListener {
            findNavController().navigate(R.id.action_settingsFragment_to_excludedAlbumsFragment)
        }
    }

    private fun updateLanguageSummary() {
        val currentLang = localeManager.currentLanguage.value
        val summary = when (currentLang) {
            LocaleManager.LANG_EN -> getString(R.string.settings_language_en)
            LocaleManager.LANG_ZH -> getString(R.string.settings_language_zh)
            else -> getString(R.string.settings_language_system)
        }
        binding.tvLanguageSummary.text = summary
    }

    private fun showLanguageDialog() {
        val currentLang = localeManager.currentLanguage.value
        val options = arrayOf(
            getString(R.string.settings_language_system),
            getString(R.string.settings_language_zh),
            getString(R.string.settings_language_en)
        )
        val langValues = arrayOf(
            LocaleManager.LANG_SYSTEM,
            LocaleManager.LANG_ZH,
            LocaleManager.LANG_EN
        )
        val checkedItem = langValues.indexOf(currentLang)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_language)
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                val newLang = langValues[which]
                if (newLang != currentLang) {
                    localeManager.setLanguage(newLang)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateExcludedSummary() {
        val count = excludedAlbumManager.excludedBucketIds.value.size
        binding.tvExcludedSummary.text = if (count > 0) {
            getString(R.string.settings_excluded_count, count)
        } else {
            getString(R.string.settings_excluded_none)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
