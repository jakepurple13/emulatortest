package com.programmersbox.common

import androidx.compose.runtime.Composable

public expect fun getPlatformName(): String

internal expect fun pathToBytes(path: String): ByteArray

@Composable
internal expect fun GameBoyScreen(viewModel: GameBoyViewModel)

internal expect class SoundPlayer(sampleRate: Int, bufferLengthMsec: Int) {
    val availableSamples: Int
    fun play(byteArray: ByteArray, numSamples: Int)
    fun stop()
    fun dispose()
}