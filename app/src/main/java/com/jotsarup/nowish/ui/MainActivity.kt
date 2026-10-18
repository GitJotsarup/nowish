package com.jotsarup.nowish.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.jotsarup.nowish.data.Space
import com.jotsarup.nowish.ui.theme.NowishTheme

class MainActivity : ComponentActivity() {
    private val vm: NowishViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            NowishTheme {
                NowishRoot(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val space = intent?.getStringExtra(EXTRA_SPACE) ?: return
        runCatching { Space.valueOf(space) }.getOrNull()?.let(vm::openSpace)
    }

    companion object {
        const val EXTRA_SPACE = "space"
    }
}
