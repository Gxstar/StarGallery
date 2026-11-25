plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.navigation.safeargs)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.parcelize)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.gxstar.stargallery"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.gxstar.stargallery"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystoreFile = rootProject.file("keystore.properties")
            if (keystoreFile.exists()) {
                val props = keystoreFile.readLines()
                    .filter { it.contains("=") && !it.startsWith("#") }
                    .associate {
                        val idx = it.indexOf("=")
                        it.substring(0, idx).trim() to it.substring(idx + 1).trim()
                    }
                storeFile = file(props["storeFile"] ?: "")
                storePassword = props["storePassword"] ?: ""
                keyAlias = props["keyAlias"] ?: ""
                keyPassword = props["keyPassword"] ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    buildToolsVersion = "36.0.0"
}

// 产物命名：StarGallery-v{versionName}-{versionCode}-{variant}.apk
// 使用 AGP 原生 outputFileName，避免旧版 doLast+renameTo 在增量构建时
// 把 Gradle 输出的 APK 改名移走、导致下次构建产出残缺文件（无 dex）的问题。
val appLabel = "StarGallery"
val appVerName = android.defaultConfig.versionName ?: "unknown"
val appVerCode = android.defaultConfig.versionCode
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("$appLabel-v$appVerName-$appVerCode-${variant.name}.apk")
        }
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

    // Liquid Glass - iOS 26 风格折射玻璃（View 体系）
    implementation(libs.liquidglass)
    
    // RecyclerView
    implementation(libs.androidx.recyclerview)

    // SplashScreen API
    implementation(libs.androidx.core.splashscreen)

    // 图片加载 - Glide
    implementation(libs.glide)
    ksp(libs.glide.compiler)
    implementation(libs.glide.recyclerview)

    // 大图查看 - ZoomImage (替代 SubsamplingScaleImageView)
    implementation(libs.zoomimage.view)
    implementation(libs.zoomimage.view.glide)


    // 依赖注入 - Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.fragment)

    // 导航 - Navigation
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)

    // 数据库 - Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // 元数据提取
    implementation(libs.metadata.extractor)

    // JXL 图片解码 - jxl-coder (Glide 集成)
    implementation(libs.jxl.coder.glide)

    // AVIF/HEIC 图片解码 - avif-coder (Glide 集成 + 核心库)
    // Android 原生仅保证 AVIF baseline（4:2:0 8/10-bit），4:2:2/4:4:4 与 12-bit
    // 样本需 libavif 兜底；详情页子采样由 ui/detail/AvifRegionDecoder 承担
    implementation(libs.avif.coder)
    implementation(libs.avif.coder.glide)

    // 视频播放 - Media3 (ExoPlayer)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    // 图片裁剪 - uCrop
    implementation(libs.ucrop)

    // 生物识别 (指纹/密码验证)
    implementation(libs.androidx.biometric)

    // 快速滚动条
    implementation(libs.fastscroll)

    // 拖动多选功能
    implementation(libs.drag.select)

    // 协程
    implementation(libs.kotlinx.coroutines.android)

    // 核心库脱糖（zoomimage 1.6.0 要求启用）
    coreLibraryDesugaring(libs.coreLibraryDesugaring)

    // 内存泄漏检测 (仅 debug)
    debugImplementation(libs.leakcanary)

    // 测试
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}