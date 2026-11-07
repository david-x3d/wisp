package dev.davidx3d.wisp.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Full-duplex audio for a voice session.
 *
 * Capture uses the VOICE_COMMUNICATION source plus the platform AcousticEchoCanceler, and
 * playback runs on the voice-communication path, so the echo canceller has the far-end
 * signal it needs and the model does not hear itself. Audio is 24 kHz mono PCM16, which
 * both realtime APIs accept natively.
 */
class AudioEngine(private val context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val _inputLevel = MutableStateFlow(0f)
    val inputLevel: StateFlow<Float> = _inputLevel.asStateFlow()
    private val _outputLevel = MutableStateFlow(0f)
    val outputLevel: StateFlow<Float> = _outputLevel.asStateFlow()
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    @Volatile var muted = false

    /** 0..1; > 0 enables a client-side noise gate (used where the server VAD cannot be tuned). */
    @Volatile var gateSensitivity: Float? = null

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null
    @Volatile private var running = false
    private val effects = mutableListOf<android.media.audiofx.AudioEffect>()

    private class Chunk(val itemId: String, val pcm: ByteArray)
    private val queue = LinkedBlockingQueue<Chunk>()
    private val lock = Any()
    @Volatile private var framesWritten = 0L
    @Volatile private var currentItem: String? = null
    @Volatile private var currentItemStartFrame = 0L
    @Volatile private var generation = 0

    var onPlaybackIdle: (() -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun start(useSpeaker: Boolean, onFrame: (ByteArray) -> Unit) {
        if (running) return
        running = true
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        routeOutput(useSpeaker)

        val minIn = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            )
            .setBufferSizeInBytes(maxOf(minIn, FRAME_BYTES * 8))
            .build()
        record = rec
        attachEffects(rec.audioSessionId)

        val minOut = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            )
            .setBufferSizeInBytes(maxOf(minOut * 2, FRAME_BYTES * 6))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
            .also { it.play() }

        captureThread = Thread({ captureLoop(rec, onFrame) }, "wisp-capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        playbackThread = Thread({ playbackLoop() }, "wisp-playback").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun attachEffects(sessionId: Int) {
        if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(sessionId)?.let { it.enabled = true; effects += it }
        }
        if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(sessionId)?.let { it.enabled = true; effects += it }
        }
        if (AutomaticGainControl.isAvailable()) {
            AutomaticGainControl.create(sessionId)?.let { it.enabled = true; effects += it }
        }
        Log.i(TAG, "audio effects: ${effects.map { it.javaClass.simpleName }}")
    }

    /** Speaker unless a headset or Bluetooth device is connected. */
    fun routeOutput(preferSpeaker: Boolean) {
        val devices = audioManager.availableCommunicationDevices
        val external = devices.firstOrNull {
            it.type in setOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET,
            )
        }
        val target = external
            ?: devices.firstOrNull { it.type == if (preferSpeaker) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        target?.let { audioManager.setCommunicationDevice(it) }
    }

    private fun captureLoop(rec: AudioRecord, onFrame: (ByteArray) -> Unit) {
        val buf = ByteArray(FRAME_BYTES)
        val silence = ByteArray(FRAME_BYTES)
        var hangover = 0
        rec.startRecording()
        while (running) {
            val n = rec.read(buf, 0, buf.size)
            if (n <= 0) continue
            val level = rms(buf, n)
            _inputLevel.value = if (muted) 0f else smooth(_inputLevel.value, level)
            val gate = gateSensitivity
            val open = if (gate == null) true else {
                // Sensitivity 1 -> threshold ~0.01, sensitivity 0 -> ~0.09 (normalised RMS).
                val threshold = 0.09f - gate.coerceIn(0f, 1f) * 0.08f
                if (level > threshold) hangover = HANGOVER_FRAMES
                hangover-- > 0
            }
            val frame = if (muted || !open) silence else buf.copyOf(n)
            onFrame(frame)
        }
        runCatching { rec.stop() }
    }

    private fun playbackLoop() {
        var wasPlaying = false
        while (running) {
            val chunk = queue.poll(30, TimeUnit.MILLISECONDS)
            val t = track ?: break
            if (chunk == null) {
                // Idle once everything written has actually been played out.
                val pending = synchronized(lock) { framesWritten - t.playbackHeadPosition.toLong() }
                if (wasPlaying && pending <= 0) {
                    wasPlaying = false
                    _playing.value = false
                    _outputLevel.value = 0f
                    onPlaybackIdle?.invoke()
                } else if (wasPlaying) {
                    _outputLevel.value = _outputLevel.value * 0.8f
                }
                continue
            }
            val gen = generation
            synchronized(lock) {
                if (chunk.itemId != currentItem) {
                    currentItem = chunk.itemId
                    currentItemStartFrame = framesWritten
                }
            }
            wasPlaying = true
            _playing.value = true
            _outputLevel.value = smooth(_outputLevel.value, rms(chunk.pcm, chunk.pcm.size))
            var off = 0
            while (off < chunk.pcm.size && running && gen == generation) {
                val w = t.write(chunk.pcm, off, chunk.pcm.size - off)
                if (w <= 0) break
                off += w
                synchronized(lock) { framesWritten += w / 2 }
            }
        }
    }

    fun enqueue(itemId: String, pcm: ByteArray) {
        if (pcm.isNotEmpty() && running) queue.offer(Chunk(itemId, pcm))
    }

    /**
     * Barge-in: stop playback immediately and drop queued audio.
     * @return the item that was playing and how many ms of it were actually heard.
     */
    fun interrupt(): Pair<String, Long>? {
        val t = track ?: return null
        generation++
        queue.clear()
        val result = synchronized(lock) {
            val item = currentItem
            val played = (t.playbackHeadPosition.toLong() - currentItemStartFrame).coerceAtLeast(0)
            val wasActive = framesWritten > t.playbackHeadPosition.toLong()
            runCatching { t.pause(); t.flush(); t.stop(); t.play() }
            framesWritten = 0
            currentItemStartFrame = 0
            currentItem = null
            if (item != null && wasActive) item to played * 1000 / RATE else null
        }
        _playing.value = false
        _outputLevel.value = 0f
        return result
    }

    val isPlaying get() = _playing.value || queue.isNotEmpty()

    fun stop() {
        if (!running) return
        running = false
        queue.clear()
        captureThread?.join(300)
        playbackThread?.join(300)
        effects.forEach { runCatching { it.release() } }
        effects.clear()
        runCatching { record?.release() }
        runCatching { track?.stop(); track?.release() }
        record = null
        track = null
        audioManager.clearCommunicationDevice()
        audioManager.mode = AudioManager.MODE_NORMAL
        _inputLevel.value = 0f
        _outputLevel.value = 0f
        _playing.value = false
    }

    private fun smooth(old: Float, new: Float) = if (new > old) old * 0.4f + new * 0.6f else old * 0.75f + new * 0.25f

    companion object {
        private const val TAG = "WispAudio"
        const val RATE = 24_000
        /** 20 ms of 16-bit mono audio. */
        const val FRAME_BYTES = RATE / 50 * 2
        private const val HANGOVER_FRAMES = 25 // 500 ms

        /** Normalised RMS (0..1) of little-endian PCM16, boosted so speech fills the range. */
        fun rms(pcm: ByteArray, len: Int): Float {
            var sum = 0.0
            val samples = len / 2
            if (samples == 0) return 0f
            for (i in 0 until samples) {
                val s = (pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)
                sum += (s.toShort().toDouble() * s.toShort())
            }
            val r = sqrt(sum / samples) / 32768.0
            return (r * 4.0).coerceIn(0.0, 1.0).toFloat()
        }
    }
}
