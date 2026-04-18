plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.navigation.safeargs)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.gxstar.stargallery"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gxstar.stargallery"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // AndroidX 核心
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    
    // SplashScreen API
    implementation(libs.androidx.core.splashscreen)

    // 图片加载 - Glide
    implementation(libs.glide)
    ksp(libs.glide.compiler)
    implementation(libs.glide.recyclerview)

    // 大图查看 - ZoomImage (替代 SubsamplingScaleImageView)
    implementation(libs.zoomimage.view)
    implementation(libs.zoomimage.view.glide)

    // 现代图片加载 - Coil (支持 AVIF/HEIC/GIF/HDR)
    implementation(libs.coil)

    // 依赖注入 - Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.fragment)

    // 导航 - Navigation
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)

    // 分页 - Paging 3
    implementation(libs.androidx.paging.runtime)

    // 列表分组 - Groupie (未使用，已用 Paging 3 insertSeparators 替代)
    // implementation(libs.groupie)
    // implementation(libs.groupie.viewbinding)

    // 权限 - PermissionX
    implementation(libs.permissionx)

    // EXIF 信息
    implementation(libs.androidx.exifinterface)
    implementation(libs.metadata.extractor)

    // 视频播放 - Media3 (ExoPlayer)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    // 快速滚动
    implementation(libs.android.fastscroll)

    // 拖动多选
    implementation(libs.drag.select.recyclerview)

    // 协程
    implementation(libs.kotlinx.coroutines.android)

    // Room 数据库
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)

    // 内存泄漏检测 (仅 debug)
    debugImplementation(libs.leakcanary)

    // 测试
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}