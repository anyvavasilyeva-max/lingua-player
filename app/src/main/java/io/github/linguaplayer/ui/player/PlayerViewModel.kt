package io.github.linguaplayer.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.linguaplayer.AppContainer
import io.github.linguaplayer.player.PlaybackSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel(private val c: AppContainer) : ViewModel() {
    private val player = c.player
    val state = player.state

    /** Отмечена ли текущая запись тегом «Не помню» (обновляется из БД). */
    val dontRemember = player.state.map { it.current?.entryId }.distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(false) else c.entries.observeDontRemember(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun hasNext(st: io.github.linguaplayer.player.PlayerState) = player.hasNext(st)
    fun hasPrevious(st: io.github.linguaplayer.player.PlayerState) = player.hasPrevious(st)

    fun togglePlay() = player.togglePlay()

    fun toggleShuffle() = updateSettings {
        it.copy(order = if (it.order == io.github.linguaplayer.player.PlayOrder.SHUFFLE) io.github.linguaplayer.player.PlayOrder.SEQUENTIAL else io.github.linguaplayer.player.PlayOrder.SHUFFLE)
    }

    fun cycleRepeat() = updateSettings { it.copy(repeatMode = it.repeatMode.next()) }
    fun next() = player.next()
    fun previous() = player.previous()

    fun toggleDontRemember() {
        val id = player.state.value.current?.entryId ?: return
        viewModelScope.launch { runCatching { c.entries.toggleDontRemember(id) } }
    }

    /** Настройки сохраняются в DataStore; плеер получает их оттуда (единый источник). */
    fun updateSettings(transform: (PlaybackSettings) -> PlaybackSettings) {
        c.appScope.launch { c.settings.updatePlayback(transform) }
    }
}
