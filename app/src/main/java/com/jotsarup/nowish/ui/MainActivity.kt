package com.jotsarup.nowish.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jotsarup.nowish.data.Space
import com.jotsarup.nowish.notes.NotesRoot
import com.jotsarup.nowish.notes.NotesViewModel
import com.jotsarup.nowish.nowish
import com.jotsarup.nowish.ui.theme.NowishTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// FragmentActivity (still a ComponentActivity) so BiometricPrompt can unlock notes.
class MainActivity : FragmentActivity() {
    private val vm: NowishViewModel by viewModels()
    private val notesVm: NotesViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            NowishTheme {
                AppShell(vm, notesVm)
            }
        }
        // While the app is on screen, pick up laptop changes every 20s.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                notesVm.refreshLock()
                while (true) {
                    nowish.sync.sync()
                    notesVm.refreshLock()
                    delay(20_000)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Locked notes hide again once you leave the app.
        if (!isChangingConfigurations) notesVm.relock()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        intent.getStringExtra(EXTRA_SPACE)
            ?.let { runCatching { Space.valueOf(it) }.getOrNull() }
            ?.let {
                vm.openSpace(it)
                vm.showRemindersRequested.value = true
            }
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

@Composable
private fun AppShell(vm: NowishViewModel, notesVm: NotesViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val addRequested by vm.addRequested.collectAsStateWithLifecycle()
    val showReminders by vm.showRemindersRequested.collectAsStateWithLifecycle()
    LaunchedEffect(addRequested) { if (addRequested) tab = 0 }
    LaunchedEffect(showReminders) {
        if (showReminders) {
            tab = 0
            vm.showRemindersRequested.value = false
        }
    }
    RequestNotificationPermission()

    val bottomBar: @Composable () -> Unit = {
        NavigationBar {
            NavigationBarItem(
                selected = tab == 0,
                onClick = { tab = 0 },
                icon = { Icon(if (tab == 0) Icons.Rounded.TaskAlt else Icons.Outlined.TaskAlt, null) },
                label = { Text("Reminders") },
            )
            NavigationBarItem(
                selected = tab == 1,
                onClick = { tab = 1 },
                icon = { Icon(if (tab == 1) Icons.Rounded.Lightbulb else Icons.Outlined.Lightbulb, null) },
                label = { Text("Notes") },
            )
        }
    }
    if (tab == 0) NowishRoot(vm, bottomBar) else NotesRoot(notesVm, bottomBar)
}
