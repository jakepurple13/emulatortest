import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Maximize
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.awt.Cursor
import java.awt.event.KeyEvent
import java.util.prefs.Preferences

@OptIn(ExperimentalComposeUiApi::class)
sealed class Shortcuts(val defaultKeys: List<Key>, private val visibleText: String) {

    var keys by mutableStateOf(defaultKeys)
    val name: String = this::class.simpleName ?: this::class.java.name

    init {
        val preferences = Preferences.userNodeForPackage(ShortcutViewModel::class.java)
        keys = preferences.get(name, defaultKeys.toJson()).fromJson<List<Key>>() ?: defaultKeys
    }

    object Left : Shortcuts(listOf(Key.A), "Left")
    object Right : Shortcuts(listOf(Key.D), "Right")
    object Up : Shortcuts(listOf(Key.W), "Up")
    object Down : Shortcuts(listOf(Key.S), "Down")
    object A : Shortcuts(listOf(Key.J), "A")
    object B : Shortcuts(listOf(Key.K), "B")
    object Select : Shortcuts(listOf(Key.G), "Select")
    object Start : Shortcuts(listOf(Key.H), "Start")

    object SaveState : Shortcuts(listOf(Key.T), "Save State")
    object LoadState : Shortcuts(listOf(Key.Y), "Load State")
    object SpeedToggle : Shortcuts(listOf(Key.U), "Speed")
    object SoundToggle : Shortcuts(listOf(Key.ShiftLeft, Key.MetaLeft, Key.P), "Sound")
    object ShowInfo : Shortcuts(listOf(Key.ShiftLeft, Key.MetaLeft, Key.S), "Show Info")

    internal object Divider : Shortcuts(emptyList(), "")

    override fun toString(): String =
        "$visibleText = ${keys.joinToString(separator = "+") { KeyEvent.getKeyText(it.nativeKeyCode) }}"

    fun keyShortcut(): KeyShortcut = KeyShortcut(
        keys.filterOutModifiers().firstOrNull() ?: Key.PageUp,
        meta = keys.anyMeta(),
        shift = keys.anyShift(),
        ctrl = keys.anyCtrl(),
        alt = keys.anyAlt()
    )

    companion object {
        fun values() = arrayOf(
            Left,
            Right,
            Up,
            Down,
            A, B,
            Select,
            Start,
            Divider,
            SpeedToggle,
            SoundToggle,
            ShowInfo,
            SaveState,
            LoadState
        )
    }
}

inline fun <reified T> String?.fromJson(): T? = try {
    Gson().fromJson(this, object : TypeToken<T>() {}.type)
} catch (e: Exception) {
    null
}

fun Any?.toJson(): String = Gson().toJson(this)

@OptIn(ExperimentalComposeUiApi::class)
fun List<Key>.anyMeta() = any { it == Key.MetaLeft || it == Key.MetaRight }

@OptIn(ExperimentalComposeUiApi::class)
fun List<Key>.anyCtrl() = any { it == Key.CtrlLeft || it == Key.CtrlRight }

@OptIn(ExperimentalComposeUiApi::class)
fun List<Key>.anyAlt() = any { it == Key.AltLeft || it == Key.AltRight }

@OptIn(ExperimentalComposeUiApi::class)
fun List<Key>.anyShift() = any { it == Key.ShiftLeft || it == Key.ShiftRight }

@OptIn(ExperimentalComposeUiApi::class)
fun List<Key>.filterOutModifiers() = filterNot {
    it in listOf(Key.MetaLeft, Key.MetaRight, Key.CtrlLeft, Key.CtrlRight, Key.AltLeft, Key.AltRight, Key.ShiftLeft, Key.ShiftRight)
}

class ShortcutViewModel {
    private val preferences = Preferences.userNodeForPackage(ShortcutViewModel::class.java)

    init {
        preferences.addPreferenceChangeListener { pcl ->
            Shortcuts.values().firstOrNull { it.name == pcl.key }?.let { it.keys = pcl.newValue.fromJson<List<Key>>() ?: it.defaultKeys }
        }
    }

    fun onClick(shortcutSelected: Shortcuts?, keysPressed: List<Key>) {
        shortcutSelected?.let { preferences.put(it.name, keysPressed.toJson()) }
    }

    fun resetAll() = Shortcuts.values().forEach { onClick(it, it.defaultKeys) }

    fun resetShortcut(shortcutSelected: Shortcuts?) {
        shortcutSelected?.let { onClick(it, it.defaultKeys) }
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun KeyboardView(onCloseRequest: () -> Unit) {

    val vm = remember { ShortcutViewModel() }

    val state = rememberWindowState()

    var shortcutSelected: Shortcuts? by remember { mutableStateOf(null) }
    val keysPressed = remember { mutableStateListOf<Key>() }

    val onClick: (Key) -> Unit = {
        if (keysPressed.contains(it)) {
            keysPressed.remove(it)
        } else {
            keysPressed.add(it)
        }
    }

    val windowPosition = state.position
    val shortcutState = rememberWindowState(position = WindowPosition.Aligned(Alignment.CenterEnd))

    LaunchedEffect(windowPosition) {
        snapshotFlow {
            if (windowPosition is WindowPosition.Absolute)
                windowPosition.copy(x = windowPosition.x + state.size.width + 50.dp)
            else WindowPosition.Aligned(Alignment.CenterEnd)
        }
            .distinctUntilChanged()
            .debounce(200)
            .onEach { shortcutState.position = it }
            .launchIn(this)
    }

    Window(
        state = shortcutState,
        onCloseRequest = {},
        title = "Shortcuts",
        undecorated = true,
        transparent = true,
        focusable = false,
        resizable = false,
        alwaysOnTop = true,
    ) {
        MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
            Surface(shape = RoundedCornerShape(8.dp)) {
                Column(modifier = Modifier.padding(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        OutlinedButton({ vm.resetAll() }) { Text("Reset All") }
                        OutlinedButton(onClick = { vm.onClick(shortcutSelected, keysPressed) }) { Text("Save New Shortcut") }
                        OutlinedButton(
                            enabled = shortcutSelected != null,
                            onClick = { vm.resetShortcut(shortcutSelected) }
                        ) { Text("Reset Shortcut") }
                    }

                    val shortcutScrollState = rememberLazyListState()

                    Box(modifier = Modifier.fillMaxWidth(.5f)) {
                        LazyColumn(
                            state = shortcutScrollState,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier
                                .matchParentSize()
                                .padding(end = 4.dp)
                        ) {
                            items(Shortcuts.values()) {
                                if(it is Shortcuts.Divider) {
                                    Divider()
                                } else {
                                    CustomChip(
                                        it.toString(),
                                        textColor = animateColorAsState(
                                            if (shortcutSelected == it) MaterialTheme.colorScheme.onPrimary
                                            else MaterialTheme.colorScheme.onSurface
                                        ).value,
                                        backgroundColor = animateColorAsState(
                                            if (shortcutSelected == it) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surface
                                        ).value,
                                        modifier = Modifier.cursorForSelectable()
                                    ) {
                                        keysPressed.clear()
                                        if (shortcutSelected == it) {
                                            shortcutSelected = null
                                        } else {
                                            shortcutSelected = it
                                            keysPressed.addAll(it.keys)
                                        }
                                    }
                                }
                            }
                        }
                        VerticalScrollbar(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight(),
                            adapter = rememberScrollbarAdapter(shortcutScrollState),
                            style = ScrollbarStyle(
                                minimalHeight = 16.dp,
                                thickness = 8.dp,
                                shape = RoundedCornerShape(4.dp),
                                hoverDurationMillis = 300,
                                unhoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                                hoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f)
                            )
                        )
                    }
                }
            }
        }
    }

    Window(
        state = state,
        title = "Keyboard",
        onCloseRequest = onCloseRequest,
        undecorated = true,
        transparent = true,
        onKeyEvent = {
            if (it.key !in keysPressed && it.type == if (shortcutSelected != null) KeyEventType.KeyUp else KeyEventType.KeyDown) {
                keysPressed.add(it.key)
            } else if (it.key in keysPressed && it.type == KeyEventType.KeyUp) {
                keysPressed.remove(it.key)
            }
            true
        }
    ) {
        MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
            Surface(shape = if (state.placement == WindowPlacement.Maximized) RectangleShape else RoundedCornerShape(8.dp)) {
                Scaffold(topBar = { WindowHeader(state, { Text("Keyboard Configurations") }, onCloseRequest) }) { p ->
                    //Full weight is about 13.5 - 14
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.padding(p)
                    ) {
                        Keyboard(onClick, keysPressed)
                        Row(horizontalArrangement = Arrangement.Center) {
                            ArrowKeys(modifier = Modifier.weight(1f), onClick, keysPressed)
                            SpecialKeys(modifier = Modifier.weight(1f), onClick, keysPressed)
                            Numpad(modifier = Modifier.weight(1f), onClick, keysPressed)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ColumnScope.Keyboard(onClick: (Key) -> Unit, pressedKeys: List<Key> = emptyList()) {
    Row(
        modifier = Modifier.align(Alignment.CenterHorizontally),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        //13 keys here
        KeyView(Key.Escape, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.F1, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.F2, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.F3, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.F4, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.F5, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.F6, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.F7, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.F8, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.F9, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.F10, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.F11, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.F12, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
    }
    Row(
        modifier = Modifier.align(Alignment.CenterHorizontally),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        //14 keys here
        KeyView(Key.Grave, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.One, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.Two, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.Three, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.Four, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.Five, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.Six, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.Seven, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.Eight, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.Nine, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.Zero, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.Minus, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.Equals, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.Backspace, onClick, modifier = Modifier.weight(2f), keysPressed = pressedKeys)
    }
    Row(
        modifier = Modifier.align(Alignment.CenterHorizontally),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        //14 keys here
        KeyView(Key.Tab, onClick, modifier = Modifier.weight(2f), keysPressed = pressedKeys)
        KeyView(Key.Q, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.W, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.E, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.R, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.T, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.Y, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.U, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.I, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.O, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.P, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.LeftBracket, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.RightBracket, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.Backslash, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
    }
    Row(
        modifier = Modifier.align(Alignment.CenterHorizontally),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        //13 keys here
        KeyView(Key.CapsLock, onClick, modifier = Modifier.weight(2.5f), keysPressed = pressedKeys)
        KeyView(Key.A, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.S, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.D, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.F, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.G, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.H, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.J, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.K, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.L, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.Semicolon, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.Apostrophe, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.Enter, onClick, modifier = Modifier.weight(2.5f), keysPressed = pressedKeys)
    }
    Row(
        modifier = Modifier.align(Alignment.CenterHorizontally),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        //12 keys here
        KeyView(Key.ShiftLeft, onClick, modifier = Modifier.weight(3f), keysPressed = pressedKeys)
        KeyView(Key.Z, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.X, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.C, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.V, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.B, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.N, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.M, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.Comma, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.Period, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.Slash, onClick, modifier = Modifier.weight(1f), keysPressed = pressedKeys)
        KeyView(Key.ShiftRight, onClick, modifier = Modifier.weight(3f), keysPressed = pressedKeys)
    }
    Row(
        modifier = Modifier.align(Alignment.CenterHorizontally),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        //7 keys here
        KeyView(Key.CtrlLeft, onClick, modifier = Modifier.weight(2f), keysPressed = pressedKeys)
        KeyView(Key.AltLeft, onClick, modifier = Modifier.weight(1.5f), keysPressed = pressedKeys)
        KeyView(Key.MetaLeft, onClick, modifier = Modifier.weight(2f), keysPressed = pressedKeys)
        KeyView(Key.Spacebar, onClick, modifier = Modifier.weight(3f), keysPressed = pressedKeys)
        KeyView(Key.MetaRight, onClick, modifier = Modifier.weight(2f), keysPressed = pressedKeys)
        KeyView(Key.AltRight, onClick, modifier = Modifier.weight(1.5f), keysPressed = pressedKeys)
        KeyView(Key.CtrlRight, onClick, modifier = Modifier.weight(2f), keysPressed = pressedKeys)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ArrowKeys(modifier: Modifier = Modifier, onClick: (Key) -> Unit, pressedKeys: List<Key> = emptyList()) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        KeyView(Key.DirectionUp, onClick, modifier = Modifier.width(40.dp), keysPressed = pressedKeys)
        Row(
            modifier = Modifier.align(Alignment.CenterHorizontally),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            KeyView(Key.DirectionLeft, onClick, modifier = Modifier.width(40.dp), keysPressed = pressedKeys)
            KeyView(Key.DirectionDown, onClick, modifier = Modifier.width(40.dp), keysPressed = pressedKeys)
            KeyView(Key.DirectionRight, onClick, modifier = Modifier.width(40.dp), keysPressed = pressedKeys)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SpecialKeys(modifier: Modifier = Modifier, onClick: (Key) -> Unit, pressedKeys: List<Key> = emptyList()) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.align(Alignment.CenterHorizontally),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            KeyView(Key.Function, onClick, modifier = Modifier.width(40.dp), keysPressed = pressedKeys)
            KeyView(Key.MoveHome, onClick, modifier = Modifier.width(40.dp), keysPressed = pressedKeys)
            KeyView(Key.PageUp, onClick, modifier = Modifier.width(40.dp), keysPressed = pressedKeys)
        }
        Row(
            modifier = Modifier.align(Alignment.CenterHorizontally),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            KeyView(Key.Delete, onClick, modifier = Modifier.width(40.dp), keysPressed = pressedKeys)
            KeyView(Key.MoveEnd, onClick, modifier = Modifier.width(40.dp), keysPressed = pressedKeys)
            KeyView(Key.PageDown, onClick, modifier = Modifier.width(40.dp), keysPressed = pressedKeys)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Numpad(modifier: Modifier = Modifier, onClick: (Key) -> Unit, pressedKeys: List<Key> = emptyList()) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.align(Alignment.CenterHorizontally),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            KeyView(Key.NumLock, onClick, modifier = Modifier.width(40.dp), keysPressed = pressedKeys)
            KeyView(Key.NumPadEquals, onClick, modifier = Modifier.width(40.dp), keysPressed = pressedKeys)
            KeyView(Key.NumPadDivide, onClick, modifier = Modifier.width(40.dp), keysPressed = pressedKeys)
            KeyView(Key.NumPadMultiply, onClick, modifier = Modifier.width(40.dp), keysPressed = pressedKeys)
        }
        Row(
            modifier = Modifier.align(Alignment.CenterHorizontally),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            KeyView(Key.NumPad7, onClick, modifier = Modifier.width(40.dp), keysPressed = pressedKeys)
            KeyView(Key.NumPad8, onClick, modifier = Modifier.width(40.dp), keysPressed = pressedKeys)
            KeyView(Key.NumPad9, onClick, modifier = Modifier.width(40.dp), keysPressed = pressedKeys)
            KeyView(Key.NumPadSubtract, onClick, modifier = Modifier.width(40.dp), keysPressed = pressedKeys)
        }
        Row(
            modifier = Modifier.align(Alignment.CenterHorizontally),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            KeyView(Key.NumPad4, onClick, modifier = Modifier.width(40.dp), keysPressed = pressedKeys)
            KeyView(Key.NumPad5, onClick, modifier = Modifier.width(40.dp), keysPressed = pressedKeys)
            KeyView(Key.NumPad6, onClick, modifier = Modifier.width(40.dp), keysPressed = pressedKeys)
            KeyView(Key.NumPadAdd, onClick, modifier = Modifier.width(40.dp), keysPressed = pressedKeys)
        }

        Row(
            modifier = Modifier.align(Alignment.CenterHorizontally),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            KeyView(Key.NumPad1, onClick, modifier = Modifier.width(40.dp), keysPressed = pressedKeys)
            KeyView(Key.NumPad2, onClick, modifier = Modifier.width(40.dp), keysPressed = pressedKeys)
            KeyView(Key.NumPad3, onClick, modifier = Modifier.width(40.dp), keysPressed = pressedKeys)
            KeyView(
                Key.NumPadEnter,
                onClick,
                modifier = Modifier
                    .height(80.dp)
                    .width(40.dp),
                keysPressed = pressedKeys
            )
        }
        Row(
            modifier = Modifier.align(Alignment.CenterHorizontally),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            KeyView(Key.NumPad0, onClick, modifier = Modifier.width(80.dp), keysPressed = pressedKeys)
            KeyView(Key.NumPadDot, onClick, modifier = Modifier.width(40.dp), keysPressed = pressedKeys)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyView(key: Key, onClick: (Key) -> Unit, modifier: Modifier = Modifier, keysPressed: List<Key> = emptyList()) {
    Surface(
        onClick = { onClick(key) },
        modifier = Modifier
            .height(40.dp)
            .cursorForSelectable()
            .then(modifier),
        shape = RoundedCornerShape(4.dp),
        color = animateColorAsState(if (key in keysPressed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface).value,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
    ) {
        Box {
            Text(
                KeyEvent.getKeyText(key.nativeKeyCode),
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.cursorForSelectable(): Modifier = pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomChip(
    category: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
    onClick: () -> Unit = {}
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.then(modifier),
        tonalElevation = 8.dp,
        shape = RoundedCornerShape(16.dp),
        color = backgroundColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
    ) {
        Row {
            Text(
                text = category,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                modifier = Modifier
                    .padding(8.dp)
                    .align(Alignment.CenterVertically),
                textAlign = TextAlign.Center
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FrameWindowScope.WindowHeader(state: WindowState, title: @Composable () -> Unit, onCloseRequest: () -> Unit) {
    Column {
        WindowDraggableArea(
            modifier = Modifier.combinedClickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {},
                onDoubleClick = {
                    state.placement = if (state.placement != WindowPlacement.Maximized) {
                        WindowPlacement.Maximized
                    } else {
                        WindowPlacement.Floating
                    }
                }
            )
        ) {
            val hasFocus = LocalWindowInfo.current.isWindowFocused

            TopAppBar(
                title = title,
                colors = TopAppBarDefaults.smallTopAppBarColors(
                    containerColor = animateColorAsState(
                        if (hasFocus) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant
                    ).value
                ),
                actions = {
                    IconButton(onClick = onCloseRequest) { Icon(Icons.Default.Close, null) }
                    IconButton(onClick = { state.isMinimized = true }) { Icon(Icons.Default.Minimize, null) }
                    IconButton(
                        onClick = {
                            state.placement = if (state.placement != WindowPlacement.Maximized) {
                                WindowPlacement.Maximized
                            } else {
                                WindowPlacement.Floating
                            }
                        }
                    ) { Icon(Icons.Default.Maximize, null) }
                }
            )
        }
        Divider(color = MaterialTheme.colorScheme.onSurface)
    }
}