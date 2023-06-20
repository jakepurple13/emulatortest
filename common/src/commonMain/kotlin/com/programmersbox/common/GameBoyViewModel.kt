package com.programmersbox.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.programmersbox.common.database.GameBoyDatabase
import com.programmersbox.common.gbcswing.Controller
import com.programmersbox.common.gbcswing.GameBoy
import com.programmersbox.common.gbcswing.Palette
import com.programmersbox.common.gbcswing.ScreenListener
import korlibs.io.file.VfsFile
import korlibs.io.file.fullPathWithoutExtension
import korlibs.io.file.std.localVfs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import moe.tlaster.precompose.viewmodel.ViewModel
import moe.tlaster.precompose.viewmodel.viewModelScope
import kotlin.time.Duration.Companion.minutes

class GBC : ViewModel() {
    private val db = GameBoyDatabase()
    var loading by mutableStateOf(false)
    val controller: Controller = Controller()
    var gameBoyImage: ImageBitmap? by mutableStateOf(null)
    private var gameBoySkipFrame = 0

    var isSoundEnabled by mutableStateOf(true)
    var currentSpeed: Int by mutableStateOf(1)

    var isSaving by mutableStateOf(false)

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

    private var romLocation: VfsFile? = null

    init {
        db
            .getSettings()
            .distinctUntilChangedBy { it.lastRomLocation }
            .mapNotNull { it.lastRomLocation?.let { l -> localVfs(l) } }
            .filter { it.isFile() }
            .onEach {
                romLocation = it
                loading = true
                gameBoy?.shutdown()
                gameBoy = GameBoy(
                    false,
                    Palette.GB,
                    it.readBytes(),
                    controller,
                    screenListener
                )
                romLocation?.fullPathWithoutExtension?.let { pathName ->
                    val path = localVfs("$pathName.sav")
                    if (path.exists()) gameBoy!!.unflatten(path.readBytes())
                }
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
            .catch { gameBoy?.shutdown() }
            .launchIn(viewModelScope)

        db
            .getSettings()
            .map { it.showInfo }
            .onEach { showInfo = it }
            .launchIn(viewModelScope)

        flow {
            while (true) {
                delay(5.minutes.inWholeMilliseconds)
                emit(Unit)
            }
        }
            .onEach {
                romLocation?.fullPathWithoutExtension?.let { pathName ->
                    saveData("$pathName.sav")
                }
            }
            .launchIn(viewModelScope)
    }

    fun setSpeed(s: Int) {
        currentSpeed = s
        gameBoy?.setSpeed(s)
    }

    fun toggleSound(sound: Boolean) {
        isSoundEnabled = sound
        gameBoy?.setSoundEnable(sound)
    }

    fun loadRom(path: String?) {
        viewModelScope.launch { db.romLocation(path) }
    }

    fun toggleInfo(show: Boolean) {
        viewModelScope.launch { db.showInfo(show) }
    }

    fun saveState(count: Int = 1) = runCatching {
        viewModelScope.launch {
            romLocation?.fullPathWithoutExtension?.let { pathName ->
                saveData("$pathName$count.sav")
            }
        }
    }

    fun loadState(count: Int = 1) = runCatching {
        viewModelScope.launch {
            gameBoy?.let { g ->
                romLocation?.fullPathWithoutExtension?.let { pathName ->
                    val path = localVfs("$pathName$count.sav")
                    if (path.exists()) g.unflatten(path.readBytes())
                }
            }
        }
    }

    fun save() {
        viewModelScope.launch {
            romLocation?.fullPathWithoutExtension?.let { pathName ->
                saveData("$pathName.sav")
            }
        }
    }

    fun loadSave(path: String) {
        viewModelScope.launch {
            gameBoy?.let { g ->
                val location = localVfs(path)
                if (location.exists()) g.unflatten(location.readBytes())
            }
        }
    }

    private suspend fun saveData(path: String) {
        isSaving = true
        gameBoy?.let { g -> localVfs(path).write(g.flatten()) }
        delay(2500)
        isSaving = false
    }
}