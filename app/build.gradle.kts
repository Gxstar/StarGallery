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
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gxstar.stargallery"
        minSdk = 30
        targetSdk = 35
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
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

tasks.matching { it.name.startsWith("bundle") }.configureEach {
    val variant = name.removePrefix("bundle").decapitalize()
    outputs.files.matching {
        include("*.aab")
    }.configureEach {
        val ver = android.defaultConfig.versionName ?: "unknown"
        val code = android.defaultConfig.versionCode
        rename(".*.aab", "StarGallery-v${ver}-${code}-${variant}.aab")
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

    // 列表分组 - Groupie (未使用，已用 Paging 3 insertSeparators 替代)
    // implementation(libs.groupie)
    // implementation(libs.groupie.viewbinding)

    // 元数据提取
    implementation(libs.metadata.extractor)

    // 视频播放 - Media3 (ExoPlayer)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    // 生物识别 (指纹/密码验证)
    implementation(libs.androidx.biometric)

    // 快速滚动条
    implementation(libs.fastscroll)

    // 拖动多选功能
    implementation(libs.drag.select)

    // 协程
    implementation(libs.kotlinx.coroutines.android)

    // 内存泄漏检测 (仅 debug)
    debugImplementation(libs.leakcanary)

    // 测试
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}