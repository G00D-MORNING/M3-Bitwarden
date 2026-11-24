package com.framepersecond.m3bitwarden.android

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.framepersecond.m3bitwarden.android.ui.AndroidRoot
import com.framepersecond.m3bitwarden.android.vault.SelfTest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val demo = debuggable && intent?.getBooleanExtra("m3b_demo", false) == true
        // Vault contents must never show up in screenshots or the recent-apps preview
        // (only the debug demo with sample data may be captured).
        if (!demo) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent { AndroidRoot(demo) }
        if (intent?.getBooleanExtra("m3b_selftest", false) == true) {
            lifecycleScope.launch { runCatching { SelfTest.run(this@MainActivity) }.onFailure { android.util.Log.e("M3BSelfTest", "failed", it) } }
        }
    }
}
