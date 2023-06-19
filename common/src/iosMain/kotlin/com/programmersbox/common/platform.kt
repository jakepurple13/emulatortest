package com.programmersbox.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Application
import moe.tlaster.precompose.PreComposeApplication
import platform.UIKit.UIViewController

public actual fun getPlatformName(): String {
    return "iOS"
}

@Composable
private fun UIShow() {
    App()
}

public fun MainViewController(): UIViewController = PreComposeApplication("My Application") {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                Spacer(Modifier.height(30.dp))
                UIShow()
            }
        }
    }
}

internal actual fun pathToBytes(path: String): ByteArray = byteArrayOf()

@Composable
internal actual fun GameBoyScreen(viewModel: GameBoyViewModel) {

}