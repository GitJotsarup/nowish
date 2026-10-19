package com.jotsarup.nowish.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jotsarup.nowish.data.Space
import com.jotsarup.nowish.nowish
import com.jotsarup.nowish.ui.theme.NowishTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
        // While the app is on screen, pick up laptop changes every 20s.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    nowish.sync.sync()
                    delay(20_000)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        intent.getStringExtra(EXTRA_SPACE)
            ?.let { runCatching { Space.valueOf(it) }.getOrNull() }
            ?.let(vm::openSpace)
        if (intent.getBooleanExtra(EXTRA_ADD, false)) {
            vm.addRequested.value = true
            intent.removeExtra(EXTRA_ADD)
        }
    }

    companion object {
        const val EXTRA_SPACE = "space"
        const val EXTRA_ADD = "add"
    }
}
