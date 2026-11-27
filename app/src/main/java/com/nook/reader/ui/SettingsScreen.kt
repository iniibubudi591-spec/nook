package com.nook.reader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.nook.reader.ai.AiClient
import com.nook.reader.data.AiTask
import com.nook.reader.data.ProviderConfig
import com.nook.reader.data.ProviderType
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    val syncUi by vm.syncUi.collectAsState()

    var selectedProvider by remember { mutableStateOf(ProviderType.entries.first()) }
    var cfg by remember(selectedProvider) { mutableStateOf(vm.settings.provider(selectedProvider)) }
    var showKey by remember { mutableStateOf(false) }
    var models by remember(selectedProvider) { mutableStateOf<List<String>>(emptyList()) }
    var loadingModels by remember { mutableStateOf(false) }
    var statusMsg by remember(selectedProvider) { mutableStateOf<String?>(null) }

    var lang by remember { mutableStateOf(vm.settings.answerLanguage()) }
    var autoMeta by remember { mutableStateOf(vm.settings.autoMeta()) }
    var taskMap by remember {
        mutableStateOf(AiTask.entries.associateWith { vm.settings.taskProvider(it) })
    }

    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        vm.onSignInResult(res.data)
    }

    fun persistProvider(updated: ProviderConfig) {
        cfg = updated
        vm.settings.setProvider(selectedProvider, updated)
    }

    fun applyProviderToAllTasks(type: ProviderType) {
        val next = AiTask.entries.associateWith { type }
        taskMap = next
        next.forEach { (task, prov) -> vm.settings.setTaskProvider(task, prov) }
        statusMsg = "Provider ${type.label} diterapkan ke semua tugas AI."
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text("Pengaturan") },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 840.dp).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Tema Tampilan (Light / Dark)", style = MaterialTheme.typography.titleMedium)
                            val currentTheme by vm.appTheme.collectAsState()
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FilterChip(
                                    selected = currentTheme == "system",
                                    onClick = { vm.setAppTheme("system") },
                                    label = { Text("Ikuti Sistem") },
                                )
                                FilterChip(
                                    selected = currentTheme == "light",
                                    onClick = { vm.setAppTheme("light") },
                                    label = { Text("Mode Terang (Light)") },
                                )
                                FilterChip(
                                    selected = currentTheme == "dark",
                                    onClick = { vm.setAppTheme("dark") },
                                    label = { Text("Mode Gelap (Dark)") },
                                )
                            }
                        }
                    }
                }

                item {
                    Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Konfigurasi Provider AI", style = MaterialTheme.typography.titleMedium)

                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ProviderType.entries.forEach { p ->
                                FilterChip(
                                    selected = selectedProvider == p,
                                    onClick = { selectedProvider = p },
                                    label = { Text(p.label) },
                                )
                            }
                        }

                        OutlinedTextField(
                            value = cfg.apiKey,
                            onValueChange = { persistProvider(cfg.copy(apiKey = it)) },
                            label = { Text("API Key (${selectedProvider.label})") },
                            singleLine = true,
                            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showKey = !showKey }) {
                                    Icon(
                                        if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = "Tampilkan/Sembunyikan API Key",
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )

                        OutlinedTextField(
                            value = cfg.model,
                            onValueChange = { persistProvider(cfg.copy(model = it)) },
                            label = { Text("Model (default: ${selectedProvider.defaultModel})") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        OutlinedTextField(
                            value = cfg.baseUrl,
                            onValueChange = { persistProvider(cfg.copy(baseUrl = it)) },
                            label = { Text("Base URL (default: ${selectedProvider.defaultBase})") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(
                                enabled = !loadingModels,
                                onClick = {
                                    loadingModels = true
                                    statusMsg = null
                                    scope.launch {
                                        try {
                                            val list = AiClient.listModels(selectedProvider, cfg)
                                            models = list
                                            statusMsg = if (list.isEmpty()) "Tidak ada daftar model." else "${list.size} model ditemukan."
                                        } catch (e: Exception) {
                                            statusMsg = "Gagal memuat model: ${e.message}"
                                        } finally {
                                            loadingModels = false
                                        }
                                    }
                                },
                            ) {
                                if (loadingModels) {
                                    CircularProgressIndicator(Modifier.size(16.dp).padding(end = 6.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp).padding(end = 4.dp))
                                }
                                Text("Ambil Model")
                            }

                            Button(onClick = { applyProviderToAllTasks(selectedProvider) }) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = null, Modifier.size(18.dp).padding(end = 4.dp))
                                Text("Pakai untuk Semua Tugas")
                            }
                        }

                        if (models.isNotEmpty()) {
                            Text("Pilih model hasil fetch:", style = MaterialTheme.typography.labelMedium)
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                models.take(25).forEach { m ->
                                    FilterChip(
                                        selected = cfg.model == m,
                                        onClick = { persistProvider(cfg.copy(model = m)) },
                                        label = { Text(m, maxLines = 1) },
                                    )
                                }
                            }
                        }

                        statusMsg?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Pemetaan Tugas AI", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Pastikan setiap tugas di bawah punya provider yang dipilih agar fitur AI aktif.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        AiTask.entries.forEach { task ->
                            val current = taskMap[task]
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(task.name, style = MaterialTheme.typography.labelLarge)
                                Row(
                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    ProviderType.entries.forEach { p ->
                                        FilterChip(
                                            selected = current == p,
                                            onClick = {
                                                taskMap = taskMap + (task to p)
                                                vm.settings.setTaskProvider(task, p)
                                            },
                                            label = { Text(p.label) },
                                        )
                                    }
                                    FilterChip(
                                        selected = current == null,
                                        onClick = {
                                            taskMap = taskMap + (task to null)
                                            vm.settings.setTaskProvider(task, null)
                                        },
                                        label = { Text("Nonaktif") },
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Bahasa & Perpustakaan", style = MaterialTheme.typography.titleMedium)

                        Text("Bahasa jawaban AI", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = lang != "en",
                                onClick = {
                                    lang = "id"
                                    vm.settings.setAnswerLanguage("id")
                                },
                                label = { Text("Indonesia") },
                            )
                            FilterChip(
                                selected = lang == "en",
                                onClick = {
                                    lang = "en"
                                    vm.settings.setAnswerLanguage("en")
                                },
                                label = { Text("English") },
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Ambil metadata otomatis saat impor buku", modifier = Modifier.weight(1f))
                            Switch(
                                checked = autoMeta,
                                onCheckedChange = {
                                    autoMeta = it
                                    vm.settings.setAutoMeta(it)
                                },
                            )
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Google Drive Sync", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = if (syncUi.email != null) "Akun: ${syncUi.email}" else "Belum login Google",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (syncUi.status.isNotBlank()) {
                            Text("Status: ${syncUi.status}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (syncUi.lastSync > 0) {
                            val fmt = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date(syncUi.lastSync))
                            Text("Terakhir sinkron: $fmt", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (syncUi.email == null) {
                                Button(onClick = { signInLauncher.launch(vm.signInIntent()) }) {
                                    Text("Login Google")
                                }
                            } else {
                                Button(
                                    enabled = !syncUi.running,
                                    onClick = { vm.syncNow(silent = false) },
                                ) {
                                    Icon(Icons.Filled.CloudSync, contentDescription = null, Modifier.size(18.dp).padding(end = 4.dp))
                                    Text("Sinkron Sekarang")
                                }
                                TextButton(onClick = { vm.signOut() }) {
                                    Text("Logout")
                                }
                            }
                        }
                        Text("SHA-1 Signing Key (Salin untuk Google Cloud Console):", style = MaterialTheme.typography.labelMedium)
                        val clipboardManager = LocalClipboardManager.current
                        val sha1 = vm.signingSha1()
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(sha1, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                            OutlinedButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(sha1))
                                    vm.toast("SHA-1 berhasil disalin!")
                                },
                            ) {
                                Text("Salin SHA-1")
                            }
                        }

                        HorizontalDivider(Modifier.padding(vertical = 4.dp))

                        Text("Solusi Login Google Gagal (Error 10 & 12502):", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(
                            "1. Kode 10 (DEVELOPER_ERROR):\n" +
                            "   Buka Google Cloud Console -> APIs & Services -> Credentials -> Edit Android Client ID.\n" +
                            "   Pastikan Package Name = com.nook.reader dan SHA-1 di atas sudah didaftarkan persis.\n\n" +
                            "2. Kode 12502 (SIGN_IN_FAILED):\n" +
                            "   a. Buka Google Cloud Console -> APIs & Services -> Library, cari 'Google Drive API' lalu klik ENABLES/AKTIFKAN.\n" +
                            "   b. Buka OAuth consent screen -> Test users, tambahkan email Google milikmu sebagai Test user.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
}