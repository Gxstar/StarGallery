package com.gxstar.stargallery

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.gxstar.stargallery.ui.compose.MainScreen
import com.gxstar.stargallery.ui.compose.theme.StarGalleryTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : androidx.appcompat.app.AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install splash screen before super.onCreate
        installSplashScreen()

        super.onCreate(savedInstanceState)

        setContent {
            StarGalleryTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}
