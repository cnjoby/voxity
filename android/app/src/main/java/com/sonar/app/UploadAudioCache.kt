package com.sonar.app

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Hash and upload bytes are committed by one atomic rename before any network attempt. */
internal class UploadAudioCache(source: File) {
    private val committed = File(source.path + ".upload.cache")
    private val pending = File(source.path + ".upload.cache.pending")

    fun read(hash: ByteArray): ByteArray? {
        if (!committed.isFile) return null
        val bytes = committed.readBytes()
        require(hash.size == 32 && bytes.size > hash.size) { "压缩录音缓存不完整" }
        check(bytes.copyOfRange(0, hash.size).contentEquals(hash)) { "原录音已改变，请重新录制" }
        return bytes.copyOfRange(hash.size, bytes.size)
    }

    fun commit(hash: ByteArray, audio: ByteArray) {
        require(hash.size == 32 && audio.isNotEmpty())
        try {
            FileOutputStream(pending).use { output ->
                output.write(hash)
                output.write(audio)
                output.fd.sync()
            }
            Files.move(pending.toPath(), committed.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        } finally { pending.delete() }
    }

    fun clear() { committed.delete(); pending.delete() }
}
