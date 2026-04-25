package com.gxstar.stargallery

import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.gxstar.stargallery.databinding.ActivityMainBinding
import com.gxstar.stargallery.ui.photos.PhotosFragment
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        // 安装 SplashScreen (必须在 super.onCreate 之前调用)
        val splashScreen = installSplashScreen()
        
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        
        // 显式确保系统栏透明（双重保险）
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        
        // 允许内容绘制到刘海屏区域
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = 
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupNavigation()
        setupBackPressedCallback()
        setupWindowInsets()
    }

    /**
     * 处理底部导航栏的 Insets，确保其在各机型上都能正确显示并避开系统导航栏
     * 针对悬浮样式的 bottom_nav，通过调整 Margin 来实现适配
     */
    private fun setupWindowInsets() {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNav) { view, windowInsets ->
            val insets = windowInsets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val params = view.layoutParams as ViewGroup.MarginLayoutParams
            
            // 基础 Margin (12dp) 加上系统导航栏的高度
            val baseMarginBottom = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 12f, resources.displayMetrics
            ).toInt()
            
            params.bottomMargin = baseMarginBottom + insets.bottom
            view.layoutParams = params
            
            windowInsets
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 屏幕旋转后重新应用底部导航栏的 insets
        binding.bottomNav.requestApplyInsets()
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        
        binding.bottomNav.setupWithNavController(navController)
        
        // 监听导航目标变化，控制底部导航栏显示/隐藏
        navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {
                R.id.photoDetailFragment,
                R.id.albumDetailFragment,
                R.id.aboutFragment,
                R.id.privacyPolicyFragment,
                R.id.permissionsFragment,
                R.id.thirdPartyLibrariesFragment,
                R.id.contactFragment,
                R.id.licenseFragment -> {
                    // 详情页和关于页面完全移除底部导航栏（不占用空间）
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

                if (currentFragment is PhotosFragment) {
                    // PhotosFragment 自己的 backPressedCallback 会处理选择模式
                    // 这里只需委托给系统处理默认返回行为
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                } else {
                    // 对于其他 Fragment，委托给系统处理（返回）
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }
}