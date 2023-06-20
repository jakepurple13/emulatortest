package com.programmersbox.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import com.programmersbox.common.gbcswing.Controller
import com.programmersbox.common.gbcswing.GameBoy
import com.programmersbox.common.gbcswing.Palette
import com.programmersbox.common.gbcswing.ScreenListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import moe.tlaster.precompose.viewmodel.ViewModel
import moe.tlaster.precompose.viewmodel.viewModel
import moe.tlaster.precompose.viewmodel.viewModelScope
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine


public actual fun getPlatformName(): String {
    return "Desktop"
}

@Composable
public fun UIShow() {
    App()
}

internal actual fun pathToBytes(path: String): ByteArray = File(path).readBytes()

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun GameBoyScreen(viewModel: GameBoyViewModel) {
    //GameBoyOne(viewModel)
    GameBoyTwo(viewModel)
}

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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun GameBoyTwo(viewModel: GameBoyViewModel) {
    val gbc = viewModel(GBC::class) { GBC(romString) }
    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { k ->
                when (k.key) {
                    Key.U -> {
                        val speed = when (k.type) {
                            KeyEventType.KeyDown -> 8
                            KeyEventType.KeyUp -> 1
                            else -> 1
                        }
                        gbc.setSpeed(speed)
                        true
                    }

                    else -> true
                }
            }
    ) {
        if (gbc.showFps) {
            Column(modifier = Modifier.align(Alignment.TopStart)) {
                Text(
                    gbc.fpsInfo,
                    color = Color.Red,
                )
                Text("Speed: ${gbc.currentSpeed}")
            }
        }

        gbc.gameBoyImage?.let {
            Image(
                it,
                null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.matchParentSize()
            )
        }
    }
}

class GBC(romPath: String?) : ViewModel() {
    val controller: Controller = Controller()
    var gameBoyImage: ImageBitmap? by mutableStateOf(null)
    private var gameBoySkipFrame = 0

    var isSoundEnabled by mutableStateOf(true)
    var currentSpeed: Int by mutableStateOf(1)

    var showFps by mutableStateOf(true)
    var fpsInfo by mutableStateOf("")
    private val previousTime = IntArray(16)
    private var previousTimeIx = 0

    private val screenListener = ScreenListener { image, skipFrame ->
        gameBoyImage = image//?.toComposeImageBitmap()
        gameBoySkipFrame = skipFrame

        val now = Clock.System.now().toEpochMilliseconds().toInt()
        val eFps: Int = (32640 + now - previousTime[previousTimeIx]) / (now - previousTime[previousTimeIx]) shr 1
        previousTime[previousTimeIx] = now
        previousTimeIx = previousTimeIx + 1 and 0x0F
        fpsInfo = eFps.toString() + " fps * " + (gameBoySkipFrame + 1)
    }

    private val gameBoy: GameBoy by lazy {
        GameBoy(
            false,
            Palette.GB,
            File(romPath).inputStream().readBytes(),
            controller,
            screenListener
        )
    }

    init {
        viewModelScope.launch {
            delay(1000)
            gameBoy.apply {
                setSoundEnable(true)
                setChannelEnable(1, true)
                setChannelEnable(2, true)
                setChannelEnable(3, true)
                setChannelEnable(4, true)
                setSpeed(1)
                startup()
            }
        }
    }

    fun setSpeed(s: Int) {
        currentSpeed = s
        gameBoy.setSpeed(s)
    }

    fun toggleSound(sound: Boolean) {
        isSoundEnabled = sound
        gameBoy.setSoundEnable(sound)
    }
}