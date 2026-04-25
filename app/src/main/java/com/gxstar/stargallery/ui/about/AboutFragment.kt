package com.gxstar.stargallery.ui.about

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.gxstar.stargallery.BuildConfig
import com.gxstar.stargallery.R
import com.gxstar.stargallery.databinding.FragmentAboutBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class AboutFragment : Fragment() {

    private var _binding: FragmentAboutBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAboutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        binding.tvVersion.text = getString(R.string.version, BuildConfig.VERSION_NAME)

        // 隐私政策
        binding.itemPrivacyPolicy.setOnClickListener {
            findNavController().navigate(R.id.action_aboutFragment_to_privacyPolicyFragment)
        }

        // 权限说明
        binding.itemPermissions.setOnClickListener {
            findNavController().navigate(R.id.action_aboutFragment_to_permissionsFragment)
        }

        // 第三方库
        binding.itemThirdParty.setOnClickListener {
            findNavController().navigate(R.id.action_aboutFragment_to_thirdPartyLibrariesFragment)
        }

        // 联系我们
        binding.itemContact.setOnClickListener {
            findNavController().navigate(R.id.action_aboutFragment_to_contactFragment)
        }

        // 开源许可
        binding.itemLicense.setOnClickListener {
            findNavController().navigate(R.id.action_aboutFragment_to_licenseFragment)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
