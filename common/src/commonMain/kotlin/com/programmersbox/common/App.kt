package com.programmersbox.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale

@Composable
internal fun App(gbc: GBC) {
    Surface {
        GameBoy(gbc)
    }
}

@Composable
fun GameBoy(gbc: GBC) {
    Box(
        Modifier.fillMaxSize()
    ) {
        if (gbc.showInfo) {
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
    AnimatedVisibility(
        gbc.loading,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) { CircularProgressIndicator() }
    }
}