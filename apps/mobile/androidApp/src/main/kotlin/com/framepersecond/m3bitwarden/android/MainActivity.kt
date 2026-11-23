package com.framepersecond.m3bitwarden.android

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.framepersecond.m3bitwarden.App
import com.framepersecond.m3bitwarden.android.autofill.AutofillHome

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Vault contents must never show up in screenshots or the recent-apps preview.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            App(extra = { AutofillHome() })
        }
    }
}
