package com.gxstar.stargallery.ui.about

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.gxstar.stargallery.R
import com.gxstar.stargallery.databinding.FragmentThirdPartyLibrariesBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ThirdPartyLibrariesFragment : Fragment() {

    private var _binding: FragmentThirdPartyLibrariesBinding? = null
    private val binding get() = _binding!!

    private val libraries = listOf(
        Library("Glide", "图片加载和缓存", "https://github.com/bumptech/glide", "Apache-2.0"),
        Library("ZoomImage", "高性能图片缩放查看", "https://github.com/panpf/zoomimage", "Apache-2.0"),
        Library("ExoPlayer (Media3)", "视频播放", "https://github.com/androidx/media", "Apache-2.0"),
        Library("metadata-extractor", "EXIF元数据读取", "https://github.com/drewnoakes/metadata-extractor", "Apache-2.0"),
        Library("Paging 3", "分页加载", "https://developer.android.com/kotlin/coroutines#paging", "Apache-2.0"),
        Library("Hilt", "依赖注入", "https://developer.android.com/training/dependency-injection/hilt-android", "Apache-2.0"),
        Library("Navigation Component", "页面导航", "https://developer.android.com/guide/navigation", "Apache-2.0"),
        Library("drag-select-recyclerview", "拖动多选", "https://github.com/afollestad/drag-select-recyclerview", "Apache-2.0"),
        Library("PermissionX", "权限管理", "https://github.com/guolindev/PermissionX", "Apache-2.0"),
        Library("FastScroller", "快速滚动条", "https://github.com/zhanghai/AndroidFastScroll", "Apache-2.0"),
        Library("LeakCanary", "内存泄漏检测", "https://github.com/square/leakcanary", "Apache-2.0")
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentThirdPartyLibrariesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = LibraryAdapter(libraries) { library ->
            // 打开链接
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(library.url))
            startActivity(intent)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    data class Library(
        val name: String,
        val description: String,
        val url: String,
        val license: String
    )

    class LibraryAdapter(
        private val libraries: List<Library>,
        private val onItemClick: (Library) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<LibraryAdapter.ViewHolder>() {

        class ViewHolder(itemView: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView) {
            val tvName: android.widget.TextView = itemView.findViewById(R.id.tvName)
            val tvDescription: android.widget.TextView = itemView.findViewById(R.id.tvDescription)
            val tvLicense: android.widget.TextView = itemView.findViewById(R.id.tvLicense)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_library, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val library = libraries[position]
            holder.tvName.text = library.name
            holder.tvDescription.text = library.description
            holder.tvLicense.text = library.license
            holder.itemView.setOnClickListener { onItemClick(library) }
        }

        override fun getItemCount() = libraries.size
    }
}