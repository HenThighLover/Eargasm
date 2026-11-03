package app.openhearing.assist

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

enum class TranscriptionLang(val label: String, val code: String, val folderName: String) {
    ENGLISH("English", "en", "en"),
    MALAYALAM("മലയാളം", "ml", "ml"),
    BILINGUAL("EN + മലയാളം", "auto", "bilingual"),
}

data class TranscriptionUiState(
    val enabled: Boolean = false,
    val language: TranscriptionLang = TranscriptionLang.BILINGUAL,
    val usingAssistTap: Boolean = false,
    val liveText: String = "",
    val historyText: String = "",
    val modelReady: Boolean = false,
    val statusMessage: String = "",
)

@Singleton
class SherpaTranscriptionController
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val assistController: AssistController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var workerJob: Job? = null

    private val _state = MutableStateFlow(TranscriptionUiState())
    val state: StateFlow<TranscriptionUiState> = _state.asStateFlow()

    init {
        scope.launch {
            assistController.running.collect { assistRunning ->
                if (_state.value.enabled) {
                    restartPipeline(assistRunning)
                }
            }
        }
    }

    /** Automatically unpacks bundled models from APK assets on first launch. */
    fun getModelsRootDir(): File {
        val root = File(context.filesDir, "sherpa_models")
        if (!root.exists()) {
            root.mkdirs()
            copyAssetsRecursively("sherpa_models", root)
        }
        return root
    }

    private fun copyAssetsRecursively(assetDirName: String, targetDir: File) {
        val assetManager = context.assets
        val assets = assetManager.list(assetDirName) ?: return
        targetDir.mkdirs()

        for (asset in assets) {
            val p = if (assetDirName.isEmpty()) asset else "$assetDirName/$asset"
            val subFiles = assetManager.list(p)
            val destFile = File(targetDir, asset)

            if (subFiles != null && subFiles.isNotEmpty()) {
                copyAssetsRecursively(p, destFile)
            } else {
                runCatching {
                    assetManager.open(p).use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (_state.value.enabled == enabled) return
        if (enabled) {
            _state.value = _state.value.copy(enabled = true)
            restartPipeline(assistController.running.value)
        } else {
            stopPipeline()
            _state.value = _state.value.copy(
                enabled = false,
                usingAssistTap = false,
                statusMessage = "",
            )
        }
    }

    fun setLanguage(lang: TranscriptionLang) {
        if (_state.value.language == lang) return
        _state.value = _state.value.copy(language = lang)
        if (_state.value.enabled) {
            restartPipeline(assistController.running.value)
        }
    }

    fun clearTranscript() {
        _state.value = _state.value.copy(liveText = "", historyText = "")
    }

    private fun stopPipeline() {
        assistController.asrTap.enabled = false
        workerJob?.cancel()
        workerJob = null
        assistController.asrTap.clear()
    }

    private fun restartPipeline(useAssistTap: Boolean) {
        stopPipeline()
        val lang = _state.value.language
        val modelDir = File(getModelsRootDir(), lang.folderName).apply { mkdirs() }
        val tokensFile = File(modelDir, "tokens.txt")

        if (!tokensFile.exists()) {
            _state.value = _state.value.copy(
                usingAssistTap = useAssistTap,
                modelReady = false,
                statusMessage = "Model files missing for ${lang.label} in:\n${modelDir.absolutePath}",
            )
        } else {
            _state.value = _state.value.copy(
                usingAssistTap = useAssistTap,
                modelReady = true,
                statusMessage = if (useAssistTap) {
                    "Listening via Hearing Assist (${lang.label})"
                } else {
                    "Standalone Mic Mode (${lang.label})"
                },
            )
        }

        workerJob = scope.launch {
            val engine = SherpaRuntimeBridge.create(modelDir, lang.code)
            try {
                if (useAssistTap) {
                    runFromAssistTap(engine)
                } else {
                    runStandaloneMic(engine)
                }
            } finally {
                engine?.release()
            }
        }
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.runFromAssistTap(
        engine: SherpaRuntimeBridge?,
    ) {
        val tap = assistController.asrTap
        tap.clear()
        tap.enabled = true
        val chunk16k = FloatArray(1600)
        val speechWindow = FloatArray(16_000 * 6)
        var speechLen = 0
        var silenceChunks = 0

        while (isActive) {
            val read = tap.drain16kMono(chunk16k)
            if (read == 0) {
                delay(25L)
                continue
            }
            processAudioChunk(
                chunk = chunk16k,
                read = read,
                engine = engine,
                speechWindow = speechWindow,
                getSpeechLen = { speechLen },
                setSpeechLen = { speechLen = it },
                getSilenceChunks = { silenceChunks },
                setSilenceChunks = { silenceChunks = it },
            )
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun kotlinx.coroutines.CoroutineScope.runStandaloneMic(
        engine: SherpaRuntimeBridge?,
    ) {
        val minBuf = AudioRecord.getMinBufferSize(
            16_000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(3200 * 4)

        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                16_000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
                minBuf,
            )
        }.getOrNull()

        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            _state.value = _state.value.copy(statusMessage = "Microphone unavailable")
            return
        }

        val chunk16k = FloatArray(1600)
        val speechWindow = FloatArray(16_000 * 6)
        var speechLen = 0
        var silenceChunks = 0

        try {
            recorder.startRecording()
            while (isActive) {
                val read = recorder.read(chunk16k, 0, chunk16k.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) {
                    delay(20L)
                    continue
                }
                processAudioChunk(
                    chunk = chunk16k,
                    read = read,
                    engine = engine,
                    speechWindow = speechWindow,
                    getSpeechLen = { speechLen },
                    setSpeechLen = { speechLen = it },
                    getSilenceChunks = { silenceChunks },
                    setSilenceChunks = { silenceChunks = it },
                )
            }
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
    }

    private fun processAudioChunk(
        chunk: FloatArray,
        read: Int,
        engine: SherpaRuntimeBridge?,
        speechWindow: FloatArray,
        getSpeechLen: () -> Int,
        setSpeechLen: (Int) -> Unit,
        getSilenceChunks: () -> Int,
        setSilenceChunks: (Int) -> Unit,
    ) {
        val rms = computeRms(chunk, read)
        val isSpeech = rms > VAD_RMS_THRESHOLD

        if (engine == null) {
            if (isSpeech) {
                _state.value = _state.value.copy(liveText = "[Voice detected — bundle model files in assets]")
            }
            return
        }

        if (engine.isStreaming) {
            if (isSpeech) {
                setSilenceChunks(0)
                engine.acceptStreamingWaveform(chunk.copyOfRange(0, read))
                val partial = engine.getStreamingResult()
                if (partial.isNotBlank()) {
                    _state.value = _state.value.copy(liveText = partial)
                }
            } else if (getSilenceChunks() < 4) {
                setSilenceChunks(getSilenceChunks() + 1)
                engine.acceptStreamingWaveform(chunk.copyOfRange(0, read))
                if (engine.isStreamingEndpoint()) {
                    val final = engine.getStreamingResult()
                    engine.resetStreaming()
                    appendFinalText(final)
                }
            }
        } else {
            var len = getSpeechLen()
            if (isSpeech) {
                setSilenceChunks(0)
                val toCopy = minOf(read, speechWindow.size - len)
                System.arraycopy(chunk, 0, speechWindow, len, toCopy)
                len += toCopy
                setSpeechLen(len)
                _state.value = _state.value.copy(liveText = "…")
                if (len >= speechWindow.size) {
                    val decoded = engine.decodeOffline(speechWindow.copyOfRange(0, len))
                    appendFinalText(decoded)
                    setSpeechLen(0)
                }
            } else if (len > 3200) {
                val sil = getSilenceChunks() + 1
                setSilenceChunks(sil)
                if (sil >= 4) {
                    val decoded = engine.decodeOffline(speechWindow.copyOfRange(0, len))
                    appendFinalText(decoded)
                    setSpeechLen(0)
                    setSilenceChunks(0)
                }
            } else {
                setSpeechLen(0)
            }
        }
    }

    private fun appendFinalText(segment: String) {
        val clean = segment.trim()
        if (clean.isEmpty()) {
            _state.value = _state.value.copy(liveText = "")
            return
        }
        val prev = _state.value.historyText
        val updated = if (prev.isBlank()) clean else "$prev\n$clean"
        val trimmed = updated.lines().takeLast(10).joinToString("\n")
        _state.value = _state.value.copy(liveText = "", historyText = trimmed)
    }

    private fun computeRms(buf: FloatArray, len: Int): Float {
        var sum = 0f
        for (i in 0 until len) {
            val s = buf[i]
            sum += s * s
        }
        return sqrt(sum / len)
    }

    private companion object {
        const val VAD_RMS_THRESHOLD = 0.008f
    }
}

internal class SherpaRuntimeBridge private constructor(
    val isStreaming: Boolean,
    private val recognizer: Any,
    private val stream: Any?,
) {
    fun acceptStreamingWaveform(samples: FloatArray) {
        val s = stream ?: return
        runCatching {
            s.javaClass.getMethod("acceptWaveform", FloatArray::class.java, Int::class.javaPrimitiveType)
                .invoke(s, samples, 16_000)
            val isReady = recognizer.javaClass.getMethod("isReady", s.javaClass)
            val decode = recognizer.javaClass.getMethod("decode", s.javaClass)
            while (isReady.invoke(recognizer, s) as Boolean) {
                decode.invoke(recognizer, s)
            }
        }
    }

    fun getStreamingResult(): String = runCatching {
        val s = stream ?: return ""
        val res = recognizer.javaClass.getMethod("getResult", s.javaClass).invoke(recognizer, s)
        res.javaClass.getMethod("getText").invoke(res) as? String ?: ""
    }.getOrDefault("")

    fun isStreamingEndpoint(): Boolean = runCatching {
        val s = stream ?: return false
        recognizer.javaClass.getMethod("isEndpoint", s.javaClass).invoke(recognizer, s) as Boolean
    }.getOrDefault(false)

    fun resetStreaming() {
        val s = stream ?: return
        runCatching {
            recognizer.javaClass.getMethod("reset", s.javaClass, Boolean::class.javaPrimitiveType)
                .invoke(recognizer, s, false)
        }
    }

    fun decodeOffline(samples: FloatArray): String = runCatching {
        val createStream = recognizer.javaClass.getMethod("createStream")
        val offlineStream = createStream.invoke(recognizer)
        offlineStream.javaClass.getMethod("acceptWaveform", FloatArray::class.java, Int::class.javaPrimitiveType)
            .invoke(offlineStream, samples, 16_000)
        recognizer.javaClass.getMethod("decode", offlineStream.javaClass).invoke(recognizer, offlineStream)
        val res = recognizer.javaClass.getMethod("getResult", offlineStream.javaClass)
            .invoke(recognizer, offlineStream)
        val text = res.javaClass.getMethod("getText").invoke(res) as? String ?: ""
        offlineStream.javaClass.getMethod("release").invoke(offlineStream)
        text
    }.getOrDefault("")

    fun release() {
        runCatching { stream?.javaClass?.getMethod("release")?.invoke(stream) }
        runCatching { recognizer.javaClass.getMethod("release").invoke(recognizer) }
    }

    companion object {
        fun create(modelDir: File, langCode: String): SherpaRuntimeBridge? = runCatching {
            val tokens = File(modelDir, "tokens.txt")
            if (!tokens.exists()) return null

            val files = modelDir.listFiles().orEmpty()
            val encoder = files.firstOrNull { it.name.startsWith("encoder") && it.name.endsWith(".onnx") }
            val decoder = files.firstOrNull { it.name.startsWith("decoder") && it.name.endsWith(".onnx") }
            val joiner = files.firstOrNull { it.name.startsWith("joiner") && it.name.endsWith(".onnx") }
            val senseVoice = files.firstOrNull { it.name.contains("model") && it.name.endsWith(".onnx") }

            if (encoder != null && decoder != null && joiner != null) {
                val transducerCls = Class.forName("com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig")
                val transducerCfg = transducerCls.getDeclaredConstructor().newInstance().apply {
                    transducerCls.getMethod("setEncoder", String::class.java).invoke(this, encoder.absolutePath)
                    transducerCls.getMethod("setDecoder", String::class.java).invoke(this, decoder.absolutePath)
                    transducerCls.getMethod("setJoiner", String::class.java).invoke(this, joiner.absolutePath)
                }
                val modelCfgCls = Class.forName("com.k2fsa.sherpa.onnx.OnlineModelConfig")
                val modelCfg = modelCfgCls.getDeclaredConstructor().newInstance().apply {
                    modelCfgCls.getMethod("setTransducer", transducerCls).invoke(this, transducerCfg)
                    modelCfgCls.getMethod("setTokens", String::class.java).invoke(this, tokens.absolutePath)
                    modelCfgCls.getMethod("setNumThreads", Int::class.javaPrimitiveType).invoke(this, 2)
                }
                val recCfgCls = Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizerConfig")
                val recCfg = recCfgCls.getDeclaredConstructor().newInstance().apply {
                    recCfgCls.getMethod("setModelConfig", modelCfgCls).invoke(this, modelCfg)
                    recCfgCls.getMethod("setEnableEndpoint", Boolean::class.javaPrimitiveType).invoke(this, true)
                }
                val recCls = Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizer")
                val recognizer = recCls.getConstructor(android.content.res.AssetManager::class.java, recCfgCls)
                    .newInstance(null, recCfg)
                val stream = recCls.getMethod("createStream", String::class.java).invoke(recognizer, "")
                return SherpaRuntimeBridge(isStreaming = true, recognizer = recognizer, stream = stream)
            }

            val offModelCfgCls = Class.forName("com.k2fsa.sherpa.onnx.OfflineModelConfig")
            val offModelCfg = offModelCfgCls.getDeclaredConstructor().newInstance().apply {
                offModelCfgCls.getMethod("setTokens", String::class.java).invoke(this, tokens.absolutePath)
                offModelCfgCls.getMethod("setNumThreads", Int::class.javaPrimitiveType).invoke(this, 2)
            }
            if (encoder != null && decoder != null) {
                val whisperCls = Class.forName("com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig")
                val whisperCfg = whisperCls.getDeclaredConstructor().newInstance().apply {
                    whisperCls.getMethod("setEncoder", String::class.java).invoke(this, encoder.absolutePath)
                    whisperCls.getMethod("setDecoder", String::class.java).invoke(this, decoder.absolutePath)
                    whisperCls.getMethod("setLanguage", String::class.java)
                        .invoke(this, if (langCode == "auto") "" else langCode)
                    whisperCls.getMethod("setTask", String::class.java).invoke(this, "transcribe")
                }
                offModelCfgCls.getMethod("setWhisper", whisperCls).invoke(offModelCfg, whisperCfg)
            } else if (senseVoice != null) {
                val svCls = Class.forName("com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig")
                val svCfg = svCls.getDeclaredConstructor().newInstance().apply {
                    svCls.getMethod("setModel", String::class.java).invoke(this, senseVoice.absolutePath)
                    svCls.getMethod("setLanguage", String::class.java).invoke(this, langCode)
                }
                offModelCfgCls.getMethod("setSenseVoice", svCls).invoke(offModelCfg, svCfg)
            } else {
                return null
            }

            val offRecCfgCls = Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizerConfig")
            val offRecCfg = offRecCfgCls.getDeclaredConstructor().newInstance().apply {
                offRecCfgCls.getMethod("setModelConfig", offModelCfgCls).invoke(this, offModelCfg)
            }
            val offRecCls = Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer")
            val recognizer = offRecCls.getConstructor(android.content.res.AssetManager::class.java, offRecCfgCls)
                .newInstance(null, offRecCfg)
            SherpaRuntimeBridge(isStreaming = false, recognizer = recognizer, stream = null)
        }.getOrNull()
    }
}