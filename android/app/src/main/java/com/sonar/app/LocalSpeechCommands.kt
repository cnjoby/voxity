package com.sonar.app

import android.content.Context
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.Model
import org.vosk.Recognizer

/** Bundled, offline Chinese control recognition. It consumes the existing PCM, never another mic. */
class LocalSpeechCommands(private val context: Context) {
    suspend fun prepare() = withContext(Dispatchers.IO) {
        modelLock.withLock {
            if (model == null) {
                val directory = File(context.filesDir, "offline-controls/v1")
                val marker = File(directory, ".verified")
                if (!marker.isFile || marker.readText() != MODEL_SHA) unpack(directory)
                LibVosk.vosk_set_log_level(-1)
                model = Model(directory.absolutePath)
            }
        }
    }

    fun available() = model != null

    suspend fun recognize(file: File): String? = withContext(Dispatchers.Default) {
        // Long content is sent to Linx. Local recognition has only a control role.
        if (file.length() !in 46..(6 * 32000 + 44)) return@withContext null
        val ready = model ?: return@withContext null
        val bytes = file.readBytes()
        Recognizer(ready, 16000f).use { recognizer ->
            recognizer.setWords(true)
            recognizer.acceptWaveForm(bytes.copyOfRange(44, bytes.size), bytes.size - 44)
            val result = JSONObject(recognizer.finalResult)
            val text = result.text("text")
            val words = result.objects("result")
            if (words.isEmpty() || words.any { it.optDouble("conf", 0.0) < 0.75 }) return@withContext null
            // A free-vocabulary recognizer must actually hear a whole control utterance.
            // A forced control grammar could turn arbitrary conversation into a command.
            text.takeIf { localExchangeCommand(it) != null }
        }
    }

    private fun unpack(directory: File) {
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(MODEL_ASSET).use { input ->
            DigestInputStream(input, digest).use { checked ->
                val buffer = ByteArray(32768)
                while (checked.read(buffer) >= 0) { /* stream the package checksum */ }
            }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } == MODEL_SHA) { "离线停止模型校验失败" }
        val temporary = File(directory.parentFile, "unpack-${UUID.randomUUID()}")
        temporary.mkdirs()
        try {
            var bytes = 0L
            context.assets.open(MODEL_ASSET).use { input ->
                ZipInputStream(input).use { archive ->
                    while (true) {
                        val entry = archive.nextEntry ?: break
                        val relative = entry.name.substringAfter('/', "")
                        if (relative.isEmpty()) continue
                        val output = File(temporary, relative)
                        check(output.canonicalPath.startsWith(temporary.canonicalPath + File.separator)) { "语音模型路径无效" }
                        if (entry.isDirectory) output.mkdirs() else {
                            output.parentFile?.mkdirs()
                            output.outputStream().use { target ->
                                val buffer = ByteArray(32768)
                                while (true) {
                                    val read = archive.read(buffer)
                                    if (read < 0) break
                                    bytes += read
                                    check(bytes <= 100 * 1024 * 1024) { "语音模型内容过大" }
                                    target.write(buffer, 0, read)
                                }
                            }
                        }
                    }
                }
            }
            check(File(temporary, "am/final.mdl").isFile && File(temporary, "conf/model.conf").isFile) { "语音模型不完整" }
            File(temporary, ".verified").writeText(MODEL_SHA)
            directory.deleteRecursively() // Only this generated, versioned model cache.
            check(temporary.renameTo(directory)) { "无法准备离线停止模型" }
        } finally {
            temporary.deleteRecursively()
        }
    }

    companion object {
        private const val MODEL_ASSET = "offline/vosk-model-small-cn-0.22.zip"
        private const val MODEL_SHA = "3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba"
        private val modelLock = Mutex()
        // Shared immutable native model; each utterance owns a short-lived Recognizer.
        @Volatile private var model: Model? = null
    }
}
