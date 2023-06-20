package com.programmersbox.common

import androidx.compose.runtime.Composable
import java.io.File

public actual fun getPlatformName(): String {
    return "Android"
}

@Composable
public fun UIShow(gbc: GBC) {
    App(gbc)
}

internal actual fun pathToBytes(path: String): ByteArray = File(path).readBytes()

internal actual class SoundPlayer actual constructor(sampleRate: Int, bufferLengthMsec: Int) {
    actual val availableSamples: Int = 0
    actual fun play(byteArray: ByteArray, numSamples: Int) {}
    actual fun stop() {}
    actual fun dispose() {}
}