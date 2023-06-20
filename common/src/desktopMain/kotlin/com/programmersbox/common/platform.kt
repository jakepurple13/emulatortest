package com.programmersbox.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
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
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.roundToInt


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

val romString = "~/Downloads/Tetris (JUE) (V1.1) [!].gb"

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
            readFile(romPath),
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

fun readFile(path: String?): ByteArray? {
    val loadFile = File(path)
    if (loadFile.exists()) {
        val bin = ByteArray(loadFile.length().toInt())
        var total = loadFile.length().toInt()
        var fis: FileInputStream? = null
        try {
            fis = FileInputStream(loadFile)
            while (total > 0) {
                total -= fis.read(bin, (loadFile.length() - total).toInt(), total)
            }
            return bin
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            if (fis != null) {
                try {
                    fis.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
    }
    return null
}