package com.example.llama.aichat.ai

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class K2InferenceManager private constructor(private val context: Context) {
    private val mutex = Mutex()
    private var engine: InferenceEngine? = null
    private var localLLM: LocalLLM? = null
    private var idleJob: Job? = null
    private var cachedModelPath: String? = null

    enum class State {
        UNINITIALIZED, LOADING, READY, INFERENCE, UNLOADING, UNAVAILABLE, ERROR
    }

    private val _state = MutableStateFlow(State.UNINITIALIZED)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "K2InferenceManager"
        const val EXPECTED_MODEL_SIZE = 666184672L // Exact size for K2 Horizon 0.9B Q4_K_M
        private const val IDLE_TIMEOUT_MS = 300_000L // 5 minutes

        @Volatile
        private var INSTANCE: K2InferenceManager? = null

        fun getInstance(context: Context): K2InferenceManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: K2InferenceManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    init {
        scope.launch {
            findAndLoadModel()
        }
    }

    suspend fun findAndLoadModel(): Boolean = withContext(Dispatchers.IO) {
        _errorMessage.value = null
        val internalModelsDir = File(context.filesDir, "models")
        if (!internalModelsDir.exists()) internalModelsDir.mkdirs()

        val internalModel = File(internalModelsDir, "k2-horizon-0.9b-q4_k_m.gguf")
        if (internalModel.exists() && internalModel.length() > 500_000_000L) {
            Log.i(TAG, "Found valid internal model: ${internalModel.absolutePath} (${internalModel.length()} bytes)")
            cachedModelPath = internalModel.absolutePath
            return@withContext initialize(internalModel.absolutePath)
        }

        // Search in external directories
        val candidates = mutableListOf<File>()
        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloadDir != null && downloadDir.exists()) {
                downloadDir.listFiles()?.forEach { if (it != null) candidates.add(it) }
                File(downloadDir, "models").listFiles()?.forEach { if (it != null) candidates.add(it) }
            }

            val sdcardDownload = File("/sdcard/Download")
            if (sdcardDownload.exists()) {
                sdcardDownload.listFiles()?.forEach { if (it != null) candidates.add(it) }
            }
            val sdcardModels = File("/sdcard/models")
            if (sdcardModels.exists()) {
                sdcardModels.listFiles()?.forEach { if (it != null) candidates.add(it) }
            }
            context.getExternalFilesDir(null)?.listFiles()?.forEach { if (it != null) candidates.add(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Storage scan exception: ${e.message}")
        }

        val discoveredModel = candidates.firstOrNull { file ->
            try {
                file.isFile && file.canRead() &&
                file.extension.equals("gguf", ignoreCase = true) &&
                file.length() > 500_000_000L
            } catch (e: Exception) { false }
        }

        if (discoveredModel != null) {
            Log.i(TAG, "Importing discovered GGUF: ${discoveredModel.absolutePath} (${discoveredModel.length()} bytes)")
            _state.value = State.LOADING
            return@withContext try {
                val tempFile = File(internalModelsDir, "model.tmp")
                discoveredModel.inputStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                if (tempFile.length() > 500_000_000L) {
                    if (internalModel.exists()) internalModel.delete()
                    tempFile.renameTo(internalModel)
                    Log.i(TAG, "Import completed to ${internalModel.absolutePath}")
                    cachedModelPath = internalModel.absolutePath
                    initialize(internalModel.absolutePath)
                } else {
                    tempFile.delete()
                    _errorMessage.value = "Imported file appears incomplete (${tempFile.length()} bytes)"
                    _state.value = State.ERROR
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy discovered model", e)
                _errorMessage.value = e.message ?: "Failed to copy model"
                _state.value = State.ERROR
                false
            }
        } else {
            Log.w(TAG, "No GGUF model file found on device storage")
            _state.value = State.UNAVAILABLE
            false
        }
    }

    suspend fun importModelFromUri(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        _state.value = State.LOADING
        _errorMessage.value = null
        try {
            val internalModelsDir = File(context.filesDir, "models")
            if (!internalModelsDir.exists()) internalModelsDir.mkdirs()
            val internalModel = File(internalModelsDir, "k2-horizon-0.9b-q4_k_m.gguf")
            val tempFile = File(internalModelsDir, "import.tmp")

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Could not open URI")

            if (tempFile.length() < 500_000_000L) {
                val len = tempFile.length()
                tempFile.delete()
                throw IllegalArgumentException("Selected file is only ${len / 1024 / 1024} MB (expected ~635 MB)")
            }

            if (internalModel.exists()) internalModel.delete()
            tempFile.renameTo(internalModel)
            Log.i(TAG, "Successfully imported URI model: ${internalModel.length()} bytes")
            cachedModelPath = internalModel.absolutePath
            initialize(internalModel.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "URI import failed", e)
            _errorMessage.value = e.message ?: "Import failed"
            _state.value = State.ERROR
            false
        }
    }

    suspend fun initialize(modelPath: String): Boolean = mutex.withLock {
        _state.value = State.LOADING
        _errorMessage.value = null
        val loadStart = System.currentTimeMillis()

        return try {
            val inferenceEngine = AiChat.getInferenceEngine(context)

            try {
                inferenceEngine.cleanUp()
            } catch (e: Exception) {
                Log.w(TAG, "cleanUp prior to load ignored: ${e.message}")
            }

            inferenceEngine.loadModel(modelPath)
            engine = inferenceEngine
            localLLM = K2LocalLLM(inferenceEngine)
            cachedModelPath = modelPath
            _state.value = State.READY
            val loadMs = System.currentTimeMillis() - loadStart
            Log.i(TAG, "K2 Horizon 0.9B model successfully loaded and ready in ${loadMs}ms: $modelPath")
            resetIdleTimer()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Model initialization failed for $modelPath", e)
            _errorMessage.value = e.message ?: "Native model load failed"
            _state.value = State.ERROR
            false
        }
    }

    suspend fun analyze(prompt: String): String? = mutex.withLock {
        // Cancel active idle timer during inference
        idleJob?.cancel()
        idleJob = null

        // Auto-reload on demand if model was unloaded
        if (_state.value != State.READY && _state.value != State.INFERENCE) {
            val path = cachedModelPath
            if (path != null && File(path).exists()) {
                Log.i(TAG, "Model currently in ${_state.value}; reloading on demand...")
                _state.value = State.LOADING
                try {
                    val inferenceEngine = AiChat.getInferenceEngine(context)
                    inferenceEngine.loadModel(path)
                    engine = inferenceEngine
                    localLLM = K2LocalLLM(inferenceEngine)
                    _state.value = State.READY
                    Log.i(TAG, "On-demand model reload completed successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "On-demand model reload failed", e)
                    _state.value = State.ERROR
                    return null
                }
            } else {
                Log.w(TAG, "Cannot analyze; model not loaded and no cached path available")
                return null
            }
        }

        _state.value = State.INFERENCE
        val startInference = System.currentTimeMillis()
        return try {
            val result = localLLM?.generate(prompt, 128)
            val duration = System.currentTimeMillis() - startInference
            Log.i(TAG, "K2 Analysis finished in ${duration}ms (Response length: ${result?.length ?: 0} chars)")
            _state.value = State.READY
            resetIdleTimer()
            result
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed", e)
            _state.value = State.READY
            resetIdleTimer()
            null
        }
    }

    private fun resetIdleTimer() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            unloadIdleModel()
        }
    }

    private suspend fun unloadIdleModel() = mutex.withLock {
        if (_state.value == State.READY) {
            Log.i(TAG, "5-minute idle timeout reached. Unloading K2 model to free RAM...")
            _state.value = State.UNLOADING
            val unloadStart = System.currentTimeMillis()
            try {
                engine?.cleanUp()
                val unloadMs = System.currentTimeMillis() - unloadStart
                Log.i(TAG, "Model unloaded successfully in ${unloadMs}ms. Native RAM reclaimed.")
                _state.value = State.UNINITIALIZED
            } catch (e: Exception) {
                Log.e(TAG, "Error during idle unload", e)
                _state.value = State.ERROR
            }
        }
    }
}
