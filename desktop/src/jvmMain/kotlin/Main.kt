import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.application
import com.programmersbox.common.GBC
import com.programmersbox.common.gbcswing.GameBoyButton
import com.programmersbox.common.romString
import moe.tlaster.precompose.PreComposeWindow

@OptIn(ExperimentalComposeUiApi::class)
fun main() = application {
    val gbc = remember { GBC(romString) }

    var showKeyboard by remember { mutableStateOf(false) }

    PreComposeWindow(
        onCloseRequest = ::exitApplication,
        onKeyEvent = { k ->
            when (k.key) {
                Key.U -> {
                    val speed = when (k.type) {
                        KeyEventType.KeyDown -> 8
                        KeyEventType.KeyUp -> 1
                        else -> 1
                    }
                    gbc.setSpeed(speed)
                }

                Key.P -> {
                    if (k.type == KeyEventType.KeyUp) gbc.toggleSound(!gbc.isSoundEnabled)
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
            }
        }
        MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
            Surface { GameBoy(gbc) }
        }
    }

    if(showKeyboard) KeyboardView { showKeyboard = false }
}

@Composable
fun GameBoy(gbc: GBC) {
    Box(
        Modifier.fillMaxSize()
    ) {
        if (gbc.showFps) {
            Column(modifier = Modifier.align(Alignment.TopStart)) {
                Text(
                    gbc.fpsInfo,
                    color = Color.Red,
                )
                Text("Speed: ${gbc.currentSpeed}")
                Icon(
                    if (gbc.isSoundEnabled) {
                        Icons.Default.VolumeUp
                    } else {
                        Icons.Default.VolumeMute
                    },
                    null
                )
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