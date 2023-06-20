package com.programmersbox.common

import androidx.compose.runtime.Composable
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

public actual fun getPlatformName(): String {
    return "Desktop"
}

@Composable
public fun UIShow(gbc: GBC) {
    App(gbc)
}

internal actual fun pathToBytes(path: String): ByteArray = File(path).readBytes()

internal actual class SoundPlayer actual constructor(sampleRate: Int, bufferLengthMsec: Int) {
    private var sourceLine: SourceDataLine? = null

    init {
        val format = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            sampleRate.toFloat(), 8, 2, 2, sampleRate.toFloat(), true
        )
        val lineInfo = DataLine.Info(SourceDataLine::class.java, format)
        if (!AudioSystem.isLineSupported(lineInfo)) {
            println("Error: Can't find audio output system!")
        } else {
            val line = AudioSystem.getLine(lineInfo) as SourceDataLine
            val bufferLength = sampleRate / 1000 * bufferLengthMsec
            line.open(format, bufferLength)
            line.start()
            sourceLine = line
            //    System.out.println("Initialized audio successfully.");
        }
    }

    actual val availableSamples: Int get() = sourceLine?.available() ?: 0
    actual fun play(byteArray: ByteArray, numSamples: Int) {
        sourceLine!!.write(byteArray, 0, numSamples)
    }

    actual fun stop() {
        sourceLine?.flush()
    }

    actual fun dispose() {
        sourceLine?.close()
    }
}

