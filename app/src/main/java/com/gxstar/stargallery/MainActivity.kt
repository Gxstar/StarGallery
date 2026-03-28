package com.gxstar.stargallery

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.gxstar.stargallery.databinding.ActivityMainBinding
import com.gxstar.stargallery.ui.photos.PhotosFragment
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupNavigation()
        setupBackPressedCallback()
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        
        binding.bottomNav.setupWithNavController(navController)
        
        // 监听导航目标变化，控制底部导航栏显示/隐藏
        navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {
                R.id.photoDetailFragment,
                R.id.albumDetailFragment -> {
                    // 详情页完全移除底部导航栏（不占用空间）
                    if (binding.bottomNav.visibility != View.GONE) {
                        binding.bottomNav.visibility = View.GONE
                    }
                }
                else -> {
                    // 主页面显示底部导航栏
                    if (binding.bottomNav.visibility != View.VISIBLE) {
                        binding.bottomNav.visibility = View.VISIBLE
                    }
                }
            }
        }
    }
    
    private fun setupBackPressedCallback() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
                val currentFragment = navHostFragment?.childFragmentManager?.fragments?.firstOrNull()
                
                // 如果当前是 PhotosFragment 且处于选择模式，退出选择模式
                if (currentFragment is PhotosFragment && currentFragment.onBackPressed()) {
                    // 已由 Fragment 处理，不再执行默认返回行为
                    return
                }
                
                // 否则执行默认返回行为
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
    }
}