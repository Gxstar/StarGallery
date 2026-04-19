package com.gxstar.stargallery.ui.about

import android.content.Intent
import android.net.Uri
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

        binding.tvPrivacyPolicy.setOnClickListener {
            // TODO: 跳转到隐私政策页面或打开网页
            // 这里先用一个简单的 Toast 提示
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
