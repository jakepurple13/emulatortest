package com.programmersbox.common.gbcswing

import java.awt.image.BufferedImage

fun interface ScreenListener {
    fun onFrameReady(image: BufferedImage?, skipFrame: Int)
}