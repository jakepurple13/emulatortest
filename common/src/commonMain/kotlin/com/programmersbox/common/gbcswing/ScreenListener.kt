package com.programmersbox.common.gbcswing

import androidx.compose.ui.graphics.ImageBitmap

fun interface ScreenListener {
    fun onFrameReady(image: ImageBitmap?, skipFrame: Int)
}