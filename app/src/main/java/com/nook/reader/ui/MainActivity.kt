package com.nook.reader.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            NookApp(vm)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val data = i?.data
        if (i?.action == Intent.ACTION_VIEW && data != null) vm.importAndOpen(data)
    }

    override fun onStop() {
        super.onStop()
        vm.onAppStop()
    }
}

@Composable
fun NookApp(vm: AppViewModel) {
    val appTheme by vm.appTheme.collectAsState()
    val isDark = when (appTheme) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    NookTheme(darkTheme = isDark) {
        val screen by vm.screen.collectAsState()
        Box(Modifier.fillMaxSize()) {
            when (val s = screen) {
                is Screen.Library -> LibraryScreen(vm)
                is Screen.Reader -> ReaderScreen(vm, s.bookId)
                is Screen.Settings -> SettingsScreen(vm)
                is Screen.Chat -> ChatScreen(vm)
            }
        }
        BackHandler(enabled = screen !is Screen.Library) { vm.back() }
    }
}
