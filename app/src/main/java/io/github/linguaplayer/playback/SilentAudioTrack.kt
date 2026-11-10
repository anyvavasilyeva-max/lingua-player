package io.github.linguaplayer.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Неслышимый аудиопоток (нули PCM) в процессе приложения на время воспроизведения.
 *
 * Зачем: речь синтезирует и проигрывает процесс TTS-движка (например, Google TTS),
 * поэтому для Android «звук играет» другое приложение — и кнопки гарнитуры/Bluetooth
 * (▶/⏸, ⏭, ⏮) система отправляет не в нашу MediaSession. Пока этот поток активен,
 * Lingua Player считается приложением, воспроизводящим медиа, и медиакнопки приходят к нам.
 * Побочный плюс: Bluetooth-наушники не «засыпают» в паузах между словами, поэтому
 * не обрезается начало следующей фразы.
 */
class SilentAudioTrack {
    @Volatile private var running = false
    private var thread: Thread? = null

    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "LinguaPlayer-silence").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferBytes = maxOf(minBuffer, SAMPLE_RATE / 5 * 2) // ≥ 200 мс
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferBytes)
                .build()
        }.getOrNull() ?: run { running = false; return }
        val silence = ByteArray(bufferBytes)
        try {
            track.play()
            while (running) {
                // Блокирующая запись сама задаёт темп (≈ реальное время), процессор почти не нагружается.
                if (track.write(silence, 0, silence.size) < 0) break
            }
        } catch (_: Exception) {
        } finally {
            runCatching { track.pause(); track.flush() }
            track.release()
            running = false
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}
