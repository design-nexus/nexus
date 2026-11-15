package io.github.design_nexus.nyx.tv.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * Delays or advances PCM audio relative to video.
 *
 * Positive [delayMs]: hold samples so audio plays later (dialogue after picture).
 * Negative [delayMs]: drop samples at the start so audio plays earlier — the usual
 * fix when dialogue lags (~−500 ms on this TV until the glitch clears).
 */
@UnstableApi
class AudioDelayProcessor : BaseAudioProcessor() {
    @Volatile var delayMs: Int = 0
        set(value) {
            val v = value.coerceIn(PlaybackPrefs.MIN_OFFSET, PlaybackPrefs.MAX_OFFSET)
            if (field == v) return
            field = v
            pendingReset = true
        }

    private var pendingReset = false
    private var queue = ByteArray(0)
    private var queueSize = 0
    private var skipRemaining = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (pendingReset) {
            queueSize = 0
            skipRemaining = 0
            pendingReset = false
            val bytesPerMs = bytesPerMillisecond()
            if (delayMs < 0) skipRemaining = (-delayMs) * bytesPerMs
            else if (delayMs > 0) {
                val silence = delayMs * bytesPerMs
                ensureCapacity(silence)
                queue.fill(0, 0, silence)
                queueSize = silence
            }
        }
        if (!inputBuffer.hasRemaining()) return
        var remaining = inputBuffer.remaining()
        if (skipRemaining > 0) {
            val skip = min(skipRemaining, remaining)
            inputBuffer.position(inputBuffer.position() + skip)
            skipRemaining -= skip
            remaining -= skip
            if (remaining == 0) return
        }
        ensureCapacity(queueSize + remaining)
        inputBuffer.get(queue, queueSize, remaining)
        queueSize += remaining
        flushOutput()
    }

    override fun onQueueEndOfStream() {
        flushOutput(force = true)
    }

    override fun onFlush() {
        queueSize = 0
        skipRemaining = 0
        pendingReset = true
    }

    override fun onReset() {
        queue = ByteArray(0)
        queueSize = 0
        skipRemaining = 0
        pendingReset = true
    }

    private fun flushOutput(force: Boolean = false) {
        val keep = if (delayMs > 0 && !force) delayMs * bytesPerMillisecond() else 0
        val available = queueSize - keep
        if (available <= 0) return
        val out = replaceOutputBuffer(available)
        out.put(queue, 0, available)
        out.flip()
        System.arraycopy(queue, available, queue, 0, keep)
        queueSize = keep
    }

    private fun bytesPerMillisecond(): Int {
        val format = outputAudioFormat
        if (format === AudioProcessor.AudioFormat.NOT_SET) return 0
        val bytesPerFrame = format.channelCount * 2 // PCM 16-bit
        return (format.sampleRate * bytesPerFrame) / 1000
    }

    private fun ensureCapacity(needed: Int) {
        if (queue.size >= needed) return
        queue = queue.copyOf(needed.coerceAtLeast(queue.size * 2).coerceAtLeast(4096))
    }
}
