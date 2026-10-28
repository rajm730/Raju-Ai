package com.raju.aiassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.raju.aiassistant.ui.navigation.RajuNavHost
import com.raju.aiassistant.ui.theme.RajuTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RajuTheme {
                RajuNavHost()
            }
        }
    }
}
