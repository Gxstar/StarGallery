package com.gxstar.stargallery.ui.common

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.gxstar.stargallery.databinding.DialogDeleteOptionsBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.gxstar.stargallery.R

class DeleteOptionsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogDeleteOptionsBinding? = null
    private val binding get() = _binding!!

    private var onDeletePermanently: (() -> Unit)? = null
    private var onMoveToTrash: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogDeleteOptionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnMoveToTrash.setOnClickListener {
            onMoveToTrash?.invoke()
            dismiss()
        }

        binding.btnDeletePermanently.setOnClickListener {
            onDeletePermanently?.invoke()
            dismiss()
        }
    }

    override fun getTheme(): Int {
        return R.style.CustomBottomSheetDialogTheme
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    fun setListeners(
        onMoveToTrash: () -> Unit,
        onDeletePermanently: () -> Unit
    ) {
        this.onMoveToTrash = onMoveToTrash
        this.onDeletePermanently = onDeletePermanently
    }

    companion object {
        const val TAG = "DeleteOptionsBottomSheet"
        
        fun newInstance(
            onMoveToTrash: () -> Unit,
            onDeletePermanently: () -> Unit
        ): DeleteOptionsBottomSheet {
            return DeleteOptionsBottomSheet().apply {
                setListeners(onMoveToTrash, onDeletePermanently)
            }
        }
    }
}
