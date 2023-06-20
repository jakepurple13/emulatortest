package com.programmersbox.common

public expect fun getPlatformName(): String

internal expect fun pathToBytes(path: String): ByteArray

internal expect class SoundPlayer(sampleRate: Int, bufferLengthMsec: Int) {
    val availableSamples: Int
    fun play(byteArray: ByteArray, numSamples: Int)
    fun stop()
    fun dispose()
}