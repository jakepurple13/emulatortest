package com.programmersbox.common

import androidx.compose.runtime.Composable
import java.io.File

public actual fun getPlatformName(): String {
    return "Android"
}

@Composable
public fun UIShow() {
    App()
}

internal actual fun pathToBytes(path: String): ByteArray = File(path).readBytes()

@Composable
internal actual fun GameBoyScreen(viewModel: GameBoyViewModel) {

}