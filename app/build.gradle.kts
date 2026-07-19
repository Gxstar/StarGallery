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
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    buildToolsVersion = "36.0.0"
}

// 重命名产物为专业命名：{AppName}-v{versionName}-{versionCode}-{variant}[-{abi}].{ext}
val appName = "StarGallery"
val ver = android.defaultConfig.versionName ?: "unknown"
val code = android.defaultConfig.versionCode

fun renameOutputs(taskName: String, outputDir: File, ext: String) {
    tasks.matching { it.name == taskName }.configureEach {
        doLast {
            if (!outputDir.exists()) return@doLast
            outputDir.walkTopDown()
                .filter { it.isFile && it.extension == ext }
                .forEach { f ->
                    val captured = Regex("app-(.+)\\.${ext}").matchEntire(f.name)?.groupValues?.get(1)
                        ?: return@forEach
                    val newName = "${appName}-v${ver}-${code}-${captured}.${ext}"
                    val target = f.parentFile.resolve(newName)
                    if (f.name != newName) {
                        if (target.exists()) target.delete()
                        f.renameTo(target)
                    }
                }
        }
    }
}

renameOutputs("packageDebug", file("$buildDir/outputs/apk/debug"), "apk")
renameOutputs("packageRelease", file("$buildDir/outputs/apk/release"), "apk")
renameOutputs("bundleDebug", file("$buildDir/outputs/bundle/debug"), "aab")
renameOutputs("bundleRelease", file("$buildDir/outputs/bundle/release"), "aab")

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

    // JXL 图片解码 - jxl-coder (Glide 集成)
    implementation(libs.jxl.coder.glide)

    // AVIF/HEIC 图片解码 - avif-coder (Glide 集成 + 核心库)
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