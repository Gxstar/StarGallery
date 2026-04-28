package com.gxstar.stargallery.ui.photos.scanner

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.gxstar.stargallery.R
import com.gxstar.stargallery.databinding.LayoutScanningProgressBinding
import com.gxstar.stargallery.data.local.scanner.MediaScanner
import kotlinx.coroutines.launch

/**
 * 扫描进度提示
 * 底部卡片形式，支持后台运行（点击隐藏）
 */
class ScanningProgressDialog : DialogFragment() {
    
    private var _binding: LayoutScanningProgressBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: ScanViewModel by activityViewModels()
    
    // 是否已完成
    private var isCompleted = false
    
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return Dialog(requireContext()).apply {
            // 无标题、透明背景
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // 点击外部不关闭（扫描在后台继续）
            setCanceledOnTouchOutside(false)
            // 可通过返回键关闭
            setCancelable(true)
        }
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutScanningProgressBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupClickListeners()
        observeScanState()
    }
    
    private fun setupClickListeners() {
        // 隐藏按钮 - 将扫描转为后台运行
        binding.btnHide.setOnClickListener {
            dismissAllowingStateLoss()
        }
        
        // 点击卡片其他区域也可以隐藏
        binding.cardScanProgress.setOnClickListener {
            dismissAllowingStateLoss()
        }
    }
    
    private fun observeScanState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.scanState.collect { state ->
                    when (state) {
                        is MediaScanner.ScanState.Idle -> {
                            // 空闲状态
                        }
                        is MediaScanner.ScanState.Scanning -> {
                            updateProgress(state.current, state.total, state.progress)
                        }
                        is MediaScanner.ScanState.Completed -> {
                            showCompleted(state.totalScanned)
                            // 延迟关闭
                            binding.root.postDelayed({
                                dismissAllowingStateLoss()
                            }, 1500)
                        }
                        is MediaScanner.ScanState.Error -> {
                            binding.tvTitle.text = getString(R.string.scanning_failed)
                            binding.tvDescription.text = state.message
                            binding.progressIndicator.visibility = View.GONE
                            binding.progressSpinner.visibility = View.GONE
                            binding.ivComplete.visibility = View.VISIBLE
                            // 延迟关闭
                            binding.root.postDelayed({
                                dismissAllowingStateLoss()
                            }, 2000)
                        }
                    }
                }
            }
        }
    }
    
    private fun updateProgress(current: Int, total: Int, progress: Float) {
        if (total > 0) {
            binding.progressIndicator.isIndeterminate = false
            binding.progressIndicator.progress = (progress * 100).toInt()
            binding.tvProgress.text = getString(
                R.string.scanning_progress,
                current,
                total
            )
        } else {
            binding.progressIndicator.isIndeterminate = true
            binding.tvProgress.text = ""
        }
    }
    
    private fun showCompleted(totalScanned: Int) {
        isCompleted = true
        binding.progressIndicator.isIndeterminate = false
        binding.progressIndicator.progress = 100
        binding.progressSpinner.visibility = View.GONE
        binding.ivComplete.visibility = View.VISIBLE
        binding.tvTitle.text = getString(R.string.scanning_completed)
        binding.tvDescription.text = getString(R.string.scanning_total, totalScanned)
        binding.tvProgress.text = ""
        binding.btnHide.visibility = View.GONE
    }
    
    override fun onStart() {
        super.onStart()
        // 设置对话框全宽
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
    
    companion object {
        const val TAG = "ScanningProgressDialog"
        
        fun newInstance(): ScanningProgressDialog {
            return ScanningProgressDialog()
        }
    }
}