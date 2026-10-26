package com.sonar.app

internal class PcmPacketizer(rate: Int, frameMs: Int) {
    private val frameBytes = rate * frameMs / 1000 * 2
    private var pending = byteArrayOf()
    init { require(frameBytes > 0) }

    fun push(pcm: ByteArray): List<ByteArray> {
        require(pcm.size % 2 == 0)
        val bytes = pending + pcm
        val count = bytes.size / frameBytes
        pending = bytes.copyOfRange(count * frameBytes, bytes.size)
        return (0 until count).map { bytes.copyOfRange(it * frameBytes, (it + 1) * frameBytes) }
    }

    fun finish(): List<ByteArray> {
        if (pending.isEmpty()) return emptyList()
        val result = listOf(pending.copyOf(frameBytes))
        pending = byteArrayOf()
        return result
    }
}
