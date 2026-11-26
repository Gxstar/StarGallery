package com.gxstar.stargallery

import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.gxstar.stargallery.databinding.ActivityMainBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlin.math.abs

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var swipeDetector: GestureDetector

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 必须放在 Activity 层：FragmentContainerView 是 ViewGroup，子 View（RecyclerView）
        // 消费 ACTION_DOWN 后父容器的 OnTouchListener 永远不会被调用，手势收不到任何事件。
        // 这里只喂给 detector、不消费，super 照常分发，不影响网格滚动和详情页 ViewPager2。
        if (::swipeDetector.isInitialized) swipeDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 安装 SplashScreen (必须在 super.onCreate 之前调用)
        installSplashScreen()
        
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        
        // 显式确保系统栏透明（双重保险）
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        
        // 允许内容绘制到刘海屏区域（minSdk 35，直接启用）
        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        
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
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNavGlass) { view, windowInsets ->
            val insets = windowInsets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val params = view.layoutParams as ViewGroup.MarginLayoutParams

            // 基础 Margin (12dp) 加上系统导航栏的高度（对齐系统相册的贴底间距）
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
        // 屏幕旋转后重新应用底部导航栏的 insets（宽度由布局定死，不再按屏宽重算）
        binding.bottomNavGlass.requestApplyInsets()
        // 屏幕旋转后重算导航形态（底部导航 / 侧边导航）
        applyNavigationVisibility()
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        setupGlassNavBar()
        setupSwipeNavigation()
        // rail 不用 setupWithNavController：它用默认 NavOptions 硬切，没有切换动画。
        // 改成和手机端底部导航同一入口 navigateToTab（方向感知滑动过渡），
        // 选中态由下面的 onDestinationChangedListener 手动同步。
        binding.navigationRail.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.photosFragment -> { navigateToTab(R.id.photosFragment); true }
                R.id.albumsFragment -> { navigateToTab(R.id.albumsFragment); true }
                else -> false
            }
        }

        // 监听导航目标变化：同步 rail 菜单选中态 + 玻璃导航条选中态 + 控制导航栏（底部/侧边）显示/隐藏
        // 仅在首页网格和相册列表两个主页面显示，其余页面一律隐藏
        navController.addOnDestinationChangedListener { _, destination, _ ->
            binding.navigationRail.menu.findItem(destination.id)?.isChecked = true
            syncGlassNavSelection(destination.id)
            applyNavigationVisibility()
        }
        binding.navigationRail.menu.findItem(
            navController.currentDestination?.id ?: R.id.photosFragment
        )?.isChecked = true
        syncGlassNavSelection(navController.currentDestination?.id ?: R.id.photosFragment)
        applyNavigationVisibility()
    }

    /**
     * 浮动玻璃导航条。
     *
     * 自绘两个导航项而非使用 Material 的 BottomNavigationView：
     * 后者在 80dp 及以下会折叠文字标签、需 96dp 才稳定显示，条身过厚。
     *
     * 材质与详情页六块玻璃同一配方（2026-11 统一）：
     * regular 材质 + blurAmount 0.08（比例值）+ 薄白纱 bg_detail_glass_shell + 边缘高光。
     * 三个坑：
     * 1. `enableDynamicBackground` 没有 XML 属性，只能代码开启，否则玻璃只采样首帧。
     * 2. `backdropSource` 必须显式指到内容根容器，默认采样「直接父容器」会拍到空面板 → 死灰。
     * 3. `enableShadow` 是矩形灰块，必须关。
     */
    private fun setupGlassNavBar() {
        val glass = binding.bottomNavGlass
        glass.enableDynamicBackground = true
        glass.backdropSource = binding.rootLayout
        glass.enableShadow = false
        glass.enableEdgeHighlight = true
        glass.edgeHighlightOpacity = 0.5f

        binding.navItemPhotos.setOnClickListener { navigateToTab(R.id.photosFragment) }
        binding.navItemAlbums.setOnClickListener { navigateToTab(R.id.albumsFragment) }

        applyNavForegroundColors()
    }

    /** 两个顶层 tab 之间切换：前进用 nav action（带动画），返回用 popBackStack（用 action 的 pop 动画） */
    private fun navigateToTab(destinationId: Int) {
        if (navController.currentDestination?.id == destinationId) return

        // 回到图库：pop 掉相册页，返回动画从入栈 action 的 popEnter/popExit 恢复
        if (destinationId == R.id.photosFragment &&
            navController.popBackStack(R.id.photosFragment, false)
        ) {
            return
        }

        // 前进到相册：必须传 null！NavController 只在 navOptions == null 时才采用 action 上的
        // enter/exit/popEnter/popExit（NavController.android.kt:854）。传任何非 null 的 NavOptions
        // 都会整段覆盖 action 动画，进而 back stack 里也没存 pop 动画，返回就变硬切。
        navController.navigate(R.id.action_photosFragment_to_albumsFragment)
    }

    /**
     * 首页两个 tab 左右滑动切换：只创建 GestureDetector，事件由 Activity 的
     * dispatchTouchEvent() 喂入（见该方法注释：不能挂在 nav host 容器的
     * OnTouchListener 上，ViewGroup 的子 View 会吃掉事件）。
     *
     * 只喂不消费，网格滚动、详情页 ViewPager2、按钮全部照常工作；
     * onFling 里再判断当前 destination，只在 photosFragment / albumsFragment 之间生效。
     *
     * 这是 fling 触发的整页 slide（复用 nav_graph 上的 action 动画），不是跟手拖拽。
     */
    private fun setupSwipeNavigation() {
        val density = resources.displayMetrics.density
        val distanceThreshold = SWIPE_DISTANCE_DP * density
        val velocityThreshold = SWIPE_VELOCITY_DP * density
        swipeDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val currentId = navController.currentDestination?.id
                if (currentId != R.id.photosFragment && currentId != R.id.albumsFragment) return false

                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) < distanceThreshold || abs(dx) <= abs(dy)) return false
                if (abs(velocityX) < velocityThreshold) return false

                // 左滑前进到相册，右滑返回图库
                navigateToTab(if (dx < 0) R.id.albumsFragment else R.id.photosFragment)
                return true
            }
        })
    }

    /** 把当前导航目的地反映到导航项的选中镜片 */
    private fun syncGlassNavSelection(destinationId: Int) {
        binding.navItemPhotos.isSelected = destinationId == R.id.photosFragment
        binding.navItemAlbums.isSelected = destinationId == R.id.albumsFragment
        applyNavForegroundColors()
    }

    /**
     * 导航项前景色。
     *
     * 早期版本玻璃只有 50% 白底，照片明暗不定，只能按背后亮度动态切换前景色。
     * 导航胶囊是薄白纱玻璃（与详情页同配方）+ adaptiveTint 明暗自适应，
     * 网格页背后以白色 surface 为主，前景色直接取 day/night 资源：
     * 选中近黑（夜间纯白），未选中中性灰。
     */
    private fun applyNavForegroundColors() {
        val idleColor = ContextCompat.getColor(this, R.color.nav_item_idle)
        val activeColor = ContextCompat.getColor(this, R.color.nav_item_active)

        paintNavItem(binding.ivNavPhotos, binding.tvNavPhotos, binding.navItemPhotos.isSelected, idleColor, activeColor)
        paintNavItem(binding.ivNavAlbums, binding.tvNavAlbums, binding.navItemAlbums.isSelected, idleColor, activeColor)
    }

    private fun paintNavItem(
        icon: android.widget.ImageView,
        label: android.widget.TextView,
        selected: Boolean,
        idleColor: Int,
        activeColor: Int
    ) {
        val color = if (selected) activeColor else idleColor
        icon.setColorFilter(color)
        label.setTextColor(color)
    }

    /**
     * 平板（宽度 >= 600dp）使用左侧 NavigationRail，手机使用底部 BottomNavigationView。
     * 仅在首页网格与相册列表两个主页面显示导航，其余页面（详情/设置等）一律隐藏。
     * 同时处理屏幕旋转后的导航形态切换。
     *
     * 显隐作用在玻璃外层：底部导航现在由 bottomNavGlass 承载尺寸与可见性，
     * 内层 BottomNavigationView 只负责菜单内容。
     */
    private fun applyNavigationVisibility() {
        val isTablet = resources.configuration.screenWidthDp >= 600
        val currentId = navController.currentDestination?.id
        val isMain = currentId == R.id.photosFragment || currentId == R.id.albumsFragment
        if (isMain) {
            binding.navigationRail.visibility = if (isTablet) View.VISIBLE else View.GONE
            binding.bottomNavGlass.visibility = if (isTablet) View.GONE else View.VISIBLE
        } else {
            binding.navigationRail.visibility = View.GONE
            binding.bottomNavGlass.visibility = View.GONE
        }
    }
    
    private fun setupBackPressedCallback() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 临时禁用自身再转发，让其他 OnBackPressedCallback（如选择模式退出）
                // 有机会优先处理；所有 Fragment 一视同仁，原先的 if/else 两分支完全相同。
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
    }

    companion object {
        private const val SWIPE_DISTANCE_DP = 120f
        private const val SWIPE_VELOCITY_DP = 120f
    }
}