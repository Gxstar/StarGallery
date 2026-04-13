package com.gxstar.stargallery.ui.detail

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.children
import com.gxstar.stargallery.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * 标签设置 BottomSheet
 * 用于选择要显示哪些标签
 */
class TagsSettingsBottomSheet : BottomSheetDialogFragment() {

    private var onTagsChangedListener: ((Set<TagType>) -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.bottom_sheet_tags_settings, container, false)
        setupViews(view)
        return view
    }

    private fun setupViews(view: View) {
        val container = view.findViewById<LinearLayout>(R.id.tagsContainer)
        val btnConfirm = view.findViewById<TextView>(R.id.btnConfirm)

        // 获取当前设置
        val currentTags = TagSettingsManager.getSelectedTags(requireContext())

        // 创建标签选项
        TagType.entries.forEach { tagType ->
            val itemView = createTagItem(tagType, currentTags.contains(tagType))
            container.addView(itemView)
        }

        btnConfirm.setOnClickListener {
            val selectedTags = mutableSetOf<TagType>()
            container.children.forEachIndexed { index, child ->
                if (child is LinearLayout) {
                    val checkBox = child.findViewById<CheckBox>(R.id.checkBox)
                    if (checkBox.isChecked) {
                        selectedTags.add(TagType.entries[index])
                    }
                }
            }
            TagSettingsManager.saveSelectedTags(requireContext(), selectedTags)
            onTagsChangedListener?.invoke(selectedTags)
            dismiss()
        }
    }

    private fun createTagItem(tagType: TagType, isChecked: Boolean): View {
        val context = requireContext()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 16, 0, 16)
            isClickable = true
            isFocusable = true
        }

        val checkBox = CheckBox(context).apply {
            id = R.id.checkBox
            this.isChecked = isChecked
        }

        val textView = TextView(context).apply {
            text = tagType.displayName
            textSize = 16f
            setTextColor(context.getColor(android.R.color.black))
        }

        layout.addView(checkBox)
        layout.addView(textView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = 16
        })

        layout.setOnClickListener {
            checkBox.isChecked = !checkBox.isChecked
        }

        return layout
    }

    fun setOnTagsChangedListener(listener: (Set<TagType>) -> Unit) {
        onTagsChangedListener = listener
    }

    companion object {
        const val TAG = "TagsSettingsBottomSheet"

        fun newInstance(): TagsSettingsBottomSheet {
            return TagsSettingsBottomSheet()
        }
    }
}

/**
 * 标签类型枚举
 */
enum class TagType(val displayName: String) {
    RAW("RAW 格式"),
    CAMERA_MAKE("相机品牌")
}

/**
 * 标签设置管理器
 */
object TagSettingsManager {
    private const val PREFS_NAME = "tag_settings"
    private const val KEY_SELECTED_TAGS = "selected_tags"

    fun getSelectedTags(context: Context): Set<TagType> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val tagNames = prefs.getStringSet(KEY_SELECTED_TAGS, null)
        return tagNames?.mapNotNull { name ->
            try {
                TagType.valueOf(name)
            } catch (e: IllegalArgumentException) {
                null
            }
        }?.toSet() ?: TagType.entries.toSet() // 默认全部选中
    }

    fun saveSelectedTags(context: Context, tags: Set<TagType>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_SELECTED_TAGS, tags.map { it.name }.toSet()).apply()
    }

    fun isTagEnabled(context: Context, tagType: TagType): Boolean {
        return getSelectedTags(context).contains(tagType)
    }
}
