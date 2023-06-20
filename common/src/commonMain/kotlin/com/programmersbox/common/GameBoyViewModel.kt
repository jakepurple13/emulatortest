package com.programmersbox.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.programmersbox.common.gbcswing.Controller
import com.programmersbox.common.gbcswing.GameBoy
import com.programmersbox.common.gbcswing.Palette
import com.programmersbox.common.gbcswing.ScreenListener
import korlibs.io.file.std.localVfs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import moe.tlaster.precompose.viewmodel.ViewModel
import moe.tlaster.precompose.viewmodel.viewModelScope

class GBC(romPath: String? = null) : ViewModel() {
    var loading by mutableStateOf(false)
    val controller: Controller = Controller()
    var gameBoyImage: ImageBitmap? by mutableStateOf(null)
    private var gameBoySkipFrame = 0

    var isSoundEnabled by mutableStateOf(true)
    var currentSpeed: Int by mutableStateOf(1)

    var showInfo by mutableStateOf(true)
    var fpsInfo by mutableStateOf("")
    private val previousTime = IntArray(16)
    private var previousTimeIx = 0

    private val screenListener = ScreenListener { image, skipFrame ->
        gameBoyImage = image
        gameBoySkipFrame = skipFrame

        val now = Clock.System.now().toEpochMilliseconds().toInt()
        val eFps: Int = (32640 + now - previousTime[previousTimeIx]) / (now - previousTime[previousTimeIx]) shr 1
        previousTime[previousTimeIx] = now
        previousTimeIx = previousTimeIx + 1 and 0x0F
        fpsInfo = eFps.toString() + " fps * " + (gameBoySkipFrame + 1)
    }

    private var gameBoy: GameBoy? by mutableStateOf(null)

    init {
        romPath?.let { loadRom(it) }
    }

    fun setSpeed(s: Int) {
        currentSpeed = s
        gameBoy?.setSpeed(s)
    }

    fun toggleSound(sound: Boolean) {
        isSoundEnabled = sound
        gameBoy?.setSoundEnable(sound)
    }

    fun loadRom(path: String) {
        viewModelScope.launch {
            loading = true
            gameBoy?.shutdown()
            gameBoy = GameBoy(
                false,
                Palette.GB,
                localVfs(path).readBytes(),
                controller,
                screenListener
            )
            delay(1000)
            gameBoy?.apply {
                setSoundEnable(true)
                setChannelEnable(1, true)
                setChannelEnable(2, true)
                setChannelEnable(3, true)
                setChannelEnable(4, true)
                setSpeed(1)
                startup()
            }
            loading = false
        }
    }
}