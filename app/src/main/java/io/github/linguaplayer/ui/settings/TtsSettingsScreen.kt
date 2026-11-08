package io.github.linguaplayer.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.linguaplayer.AppContainer
import io.github.linguaplayer.R
import io.github.linguaplayer.data.repo.languageDisplayName
import io.github.linguaplayer.player.LanguageStatus
import io.github.linguaplayer.player.SpeakResult
import io.github.linguaplayer.player.TtsEngineInfo
import io.github.linguaplayer.ui.components.appContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

data class TtsUiState(
    val loading: Boolean = true,
    val engineFailed: Boolean = false,
    val engines: List<TtsEngineInfo> = emptyList(),
    val defaultEngine: String? = null,
    val selected: String? = null,
    val languages: List<Pair<String, LanguageStatus>> = emptyList(),
    val testError: String? = null,
)

class TtsSettingsViewModel(private val c: AppContainer) : ViewModel() {
    val state = MutableStateFlow(TtsUiState())

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        state.update { it.copy(loading = true) }
        val speech = c.speech
        speech.setEnginePackage(c.settings.ttsEngine.first())
        val ok = speech.awaitReady()
        val langs = c.languagePairs.getAll().flatMap { listOf(it.sourceLang, it.targetLang) }.distinct()
        state.update {
            it.copy(
                loading = false,
                engineFailed = !ok,
                engines = speech.engines(),
                defaultEngine = speech.defaultEnginePackage(),
                selected = speech.selectedEnginePackage(),
                languages = langs.map { l -> l to speech.languageStatus(l) },
            )
        }
    }

    fun selectEngine(pkg: String?) = viewModelScope.launch {
        c.settings.setTtsEngine(pkg)
        c.speech.setEnginePackage(pkg)
        refresh()
    }

    fun test(lang: String) = viewModelScope.launch {
        c.player.pause() // не говорить одновременно с плеером
        val r = c.speech.speak(samplePhrase(lang), lang, 1f)
        state.update { it.copy(testError = if (r == SpeakResult.Done) null else r.toString()) }
        if (r !is SpeakResult.Done) refresh()
    }

    companion object {
        fun samplePhrase(lang: String): String {
            val locale = Locale.forLanguageTag(lang)
            return when (locale.language) {
                "ko" -> "안녕하세요. 포크, 숟가락, 설거지를 하다."
                "ru" -> "Здравствуйте. Вилка, ложка, мыть посуду."
                "en" -> "Hello. Fork, spoon, to wash the dishes."
                "ja" -> "こんにちは。フォーク、スプーン。"
                "zh" -> "你好。叉子，勺子。"
                "de" -> "Hallo. Gabel, Löffel."
                "fr" -> "Bonjour. Fourchette, cuillère."
                "es" -> "Hola. Tenedor, cuchara."
                else -> locale.getDisplayLanguage(locale)
            }
        }
    }
}

private fun Context.startSafely(vararg intents: Intent) {
    for (i in intents) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
}

private fun ttsSettingsIntent() = Intent("com.android.settings.TTS_SETTINGS")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsSettingsScreen(onBack: () -> Unit) {
    val c = appContainer()
    val vm: TtsSettingsViewModel = viewModel { TtsSettingsViewModel(c) }
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Вернулись из системных настроек (например, после скачивания голоса) — перепроверить.
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose {}
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tts_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }) { Icon(Icons.Outlined.Refresh, stringResource(R.string.tts_refresh)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.loading) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.padding(end = 12.dp))
                        Text(stringResource(R.string.tts_starting))
                    }
                }
            }
            if (s.engineFailed) {
                item { Text(stringResource(R.string.player_err_engine), color = MaterialTheme.colorScheme.error) }
            }

            item { Text(stringResource(R.string.tts_languages), style = MaterialTheme.typography.titleMedium) }
            items(s.languages, key = { it.first }) { (lang, status) ->
                LanguageCard(
                    lang, status,
                    onTest = { vm.test(lang) },
                    onInstall = {
                        val pkg = s.selected ?: s.defaultEngine
                        val install = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply { pkg?.let(::setPackage) }
                        context.startSafely(install, ttsSettingsIntent(), Intent(Settings.ACTION_SETTINGS))
                    },
                )
            }
            s.testError?.let { item { Text(stringResource(R.string.tts_test_failed, it), color = MaterialTheme.colorScheme.error) } }

            item { Text(stringResource(R.string.tts_engine), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
            if (!s.loading && s.engines.isEmpty()) {
                item { Text(stringResource(R.string.tts_engine_none), color = MaterialTheme.colorScheme.error) }
            }
            if (s.engines.isNotEmpty()) {
                item {
                    val defaultLabel = s.engines.firstOrNull { it.packageName == s.defaultEngine }?.label ?: s.defaultEngine.orEmpty()
                    EngineRow(stringResource(R.string.tts_engine_default, defaultLabel), s.selected == null) { vm.selectEngine(null) }
                }
                items(s.engines, key = { it.packageName }) { e ->
                    EngineRow(e.label, s.selected == e.packageName) { vm.selectEngine(e.packageName) }
                }
            }
            item {
                OutlinedButton(
                    onClick = { context.startSafely(ttsSettingsIntent(), Intent(Settings.ACTION_SETTINGS)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text(stringResource(R.string.tts_open_system)) }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.tts_help_title), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.tts_help_text), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun EngineRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun LanguageCard(lang: String, status: LanguageStatus, onTest: () -> Unit, onInstall: () -> Unit) {
    val (icon, tint, text) = when (status) {
        LanguageStatus.AVAILABLE_OFFLINE -> Triple(Icons.Outlined.CheckCircle, Color(0xFF2E7D32), R.string.tts_status_offline)
        LanguageStatus.AVAILABLE -> Triple(Icons.Outlined.CheckCircle, Color(0xFF2E7D32), R.string.tts_status_available)
        LanguageStatus.AVAILABLE_NETWORK_ONLY -> Triple(Icons.Outlined.CloudOff, MaterialTheme.colorScheme.tertiary, R.string.tts_status_network)
        LanguageStatus.MISSING_DATA -> Triple(Icons.Outlined.WarningAmber, MaterialTheme.colorScheme.tertiary, R.string.tts_status_missing)
        LanguageStatus.NOT_SUPPORTED -> Triple(Icons.Outlined.ErrorOutline, MaterialTheme.colorScheme.error, R.string.tts_status_unsupported)
        LanguageStatus.UNKNOWN -> Triple(Icons.Outlined.WarningAmber, MaterialTheme.colorScheme.onSurfaceVariant, R.string.tts_status_unknown)
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = tint)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text("${languageDisplayName(lang)} ($lang)", style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(text), style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.align(Alignment.End)) {
                if (status != LanguageStatus.AVAILABLE_OFFLINE) {
                    TextButton(onClick = onInstall) { Text(stringResource(R.string.tts_install)) }
                }
                TextButton(onClick = onTest, enabled = status != LanguageStatus.NOT_SUPPORTED && status != LanguageStatus.MISSING_DATA) {
                    Text(stringResource(R.string.tts_test))
                }
            }
        }
    }
}
