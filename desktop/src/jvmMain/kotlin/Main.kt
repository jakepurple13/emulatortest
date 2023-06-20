import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.application
import com.programmersbox.common.GBC
import com.programmersbox.common.UIShow
import com.programmersbox.common.gbcswing.GameBoyButton
import com.studiohartman.jamepad.ControllerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import moe.tlaster.precompose.PreComposeWindow

fun main() = application {
    val gbc = remember { GBC() }

    DisposableEffect(Unit) {
        val c = ControllerSupport(gbc)
        onDispose { c.dispose() }
    }

    var showKeyboard by remember { mutableStateOf(false) }

    PreComposeWindow(
        onCloseRequest = ::exitApplication,
        onKeyEvent = { k ->
            when {
                Shortcuts.SpeedToggle.keys.all { k.key == it } -> {
                    val speed = when (k.type) {
                        KeyEventType.KeyDown -> 8
                        KeyEventType.KeyUp -> 1
                        else -> 1
                    }
                    gbc.setSpeed(speed)
                }

                Shortcuts.SaveState.keys.all { k.key == it } -> {

                }
            }

            when {
                Shortcuts.Left.keys.all { k.key == it } -> GameBoyButton.Left
                Shortcuts.Right.keys.all { k.key == it } -> GameBoyButton.Right
                Shortcuts.Up.keys.all { k.key == it } -> GameBoyButton.Up
                Shortcuts.Down.keys.all { k.key == it } -> GameBoyButton.Down
                Shortcuts.A.keys.all { k.key == it } -> GameBoyButton.A
                Shortcuts.B.keys.all { k.key == it } -> GameBoyButton.B
                Shortcuts.Select.keys.all { k.key == it } -> GameBoyButton.Select
                Shortcuts.Start.keys.all { k.key == it } -> GameBoyButton.Start
                else -> null
            }?.let {
                when (k.type) {
                    KeyEventType.KeyUp -> gbc.controller.buttonUp(it)
                    KeyEventType.KeyDown -> gbc.controller.buttonDown(it)
                }
            }
            true
        }
    ) {
        MenuBar {
            Menu("Settings") {
                CheckboxItem(
                    "Change Mappings",
                    checked = showKeyboard,
                    onCheckedChange = { showKeyboard = it }
                )
                CheckboxItem(
                    "Sound",
                    checked = gbc.isSoundEnabled,
                    onCheckedChange = { gbc.toggleSound(it) },
                    shortcut = Shortcuts.SoundToggle.keyShortcut()
                )
                CheckboxItem(
                    "Show Info",
                    checked = gbc.showInfo,
                    onCheckedChange = gbc::toggleInfo,
                    shortcut = Shortcuts.ShowInfo.keyShortcut()
                )
            }
            Menu("Actions") {
                Menu("Speed") {
                    Item(
                        "Speed Toggle",
                        onClick = { gbc.setSpeed(if (gbc.currentSpeed == 1) 8 else 1) },
                        shortcut = Shortcuts.SpeedToggle.keyShortcut()
                    )
                    repeat(10) {
                        Item(
                            "Speed ${it + 1}x",
                            onClick = { gbc.setSpeed(it + 1) },
                        )
                    }
                }
                Separator()
                Menu("Save State") {
                    Item(
                        "Quick Save State",
                        onClick = gbc::saveState,
                        shortcut = Shortcuts.SaveState.keyShortcut()
                    )
                    Item(
                        "Save",
                        onClick = gbc::save,
                    )
                    repeat(4) {
                        Item(
                            "Save State ${it + 2}",
                            onClick = { gbc.saveState(it + 2) },
                        )
                    }
                }
                Menu("Load State") {
                    Item(
                        "Quick Load State",
                        onClick = gbc::loadState,
                        shortcut = Shortcuts.LoadState.keyShortcut()
                    )
                    repeat(4) {
                        Item(
                            "Load State ${it + 2}",
                            onClick = { gbc.loadState(it + 2) },
                        )
                    }
                }
            }
        }
        MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
            Surface { PlayArea(gbc) }
        }
    }

    if (showKeyboard) KeyboardView { showKeyboard = false }
}

@Composable
fun FrameWindowScope.PlayArea(gbc: GBC) {
    UIShow(gbc)

    var isDragging by remember { mutableStateOf(false) }

    AnimatedVisibility(
        isDragging,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Surface {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { Text("Load new ROM/Save. A new ROM will stop current emulation.") }
        }
    }

    DragDrop(
        onDragStateChange = { isDragging = it },
        onDropped = {
            when (it) {
                is List<*> -> {
                    it.firstOrNull()?.toString()?.let { file ->
                        when {
                            file.endsWith(".gb") -> gbc.loadRom(file)
                            file.endsWith(".gbc") -> gbc.loadRom(file)
                            file.endsWith(".sav") -> gbc.loadSave(file)
                        }
                    }
                }
            }
        }
    )
}

class ControllerSupport(
    private val gbc: GBC,
) {
    private val c = ControllerManager()

    init {
        c.initSDLGamepad()
        flow {
            while (true) {
                val s = c.getState(0)
                if (!s.isConnected) break
                emit(s)
            }
        }
            .onEach {
                pressCheck(it.dpadRight, GameBoyButton.Right)
                pressCheck(it.dpadLeft, GameBoyButton.Left)
                pressCheck(it.dpadUp, GameBoyButton.Up)
                pressCheck(it.dpadDown, GameBoyButton.Down)
                pressCheck(it.a, GameBoyButton.A)
                pressCheck(it.b, GameBoyButton.B)
                pressCheck(it.start, GameBoyButton.Start)
                pressCheck(it.back, GameBoyButton.Select)
                if (it.rbJustPressed) gbc.saveState()
                if (it.lbJustPressed) gbc.loadState()
                if (it.xJustPressed) gbc.setSpeed(if (gbc.currentSpeed == 1) 8 else 1)
            }
            .launchIn(CoroutineScope(Job() + Dispatchers.IO))
    }

    private fun pressCheck(type: Boolean, button: GameBoyButton) {
        if (type) {
            gbc.controller.buttonDown(button)
        } else {
            gbc.controller.buttonUp(button)
        }
    }

    fun dispose() {
        c.quitSDLGamepad()
    }
}