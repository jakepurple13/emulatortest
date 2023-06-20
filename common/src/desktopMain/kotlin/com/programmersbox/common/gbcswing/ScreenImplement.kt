package com.programmersbox.common.gbcswing

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.*

internal class ScreenImplement(
    registers: ByteArray?,
    memory: Array<ByteArray?>,
    oam: ByteArray?,
    gbcFeatures: Boolean,
    defaultPalette: IntArray,
    screenListener: ScreenListener?,
    maxFrameSkip: Int
) : ScreenAbstract(registers!!, memory, oam!!, gbcFeatures, screenListener, maxFrameSkip) {
    private val frameBuffer: IntArray

    // tiles & image cache
    private val transparentImage = IntArray(0)
    private val tileImage: Array<IntArray?>

    // true if there are any images to be invalidated
    private val tileReadState: BooleanArray
    private var tempPix: IntArray = IntArray(8 * 8)
    private var windowSourceLine = 0

    init {
        colors = defaultPalette
        gbcMask = -0x80000000
        transparentCutoff = if (gbcFeatures) 32 else 4
        tileImage = arrayOfNulls(tileCount * colorCount)
        tileReadState = BooleanArray(tileCount)
        frameBuffer = IntArray(8 * 8 * 20 * 18)
    }

    /**
     * Writes data to the specified video RAM address
     */
    override fun addressWrite(addr: Int, data: Byte) {
        if (videoRam[addr] == data) return
        if (addr < 0x1800) { // Bkg Tile data area
            val tileIndex = (addr shr 4) + tileOffset
            if (tileReadState[tileIndex]) {
                var r = tileImage.size - tileCount + tileIndex
                do {
                    tileImage[r] = null
                    r -= tileCount
                } while (r >= 0)
                tileReadState[tileIndex] = false
            }
        }
        videoRam[addr] = data
    }

    /**
     * This must be called by the ProcessingChip for each scanline drawn by the display hardware.
     */
    override fun notifyScanline(line: Int) {
        if (skipping || line >= 144) {
            return
        }
        if (line == 0) {
            windowSourceLine = 0
        }

        // determine the left edge of the window (160 if window is inactive)
        var windowLeft: Int
        if (winEnabled && registers[0x4A].toInt() and 0xff <= line) {
            windowLeft = (registers[0x4B].toInt() and 0xff) - 7
            if (windowLeft > 160) windowLeft = 160
        } else windowLeft = 160

        // step 1: background+window
        val skippedAnything = drawBackgroundForLine(line, windowLeft, 0)

        // At this point, the high (alpha) byte in the frameBuffer is 0xff for colors 1,2,3 and
        // 0x00 for color_style 0. Foreground sprites draw on all colors, background sprites draw on
        // top of color_style 0 only.

        // step 2: sprites
        drawSpritesForLine(line)

        // step 3: prio tiles+window
        if (skippedAnything) {
            drawBackgroundForLine(line, windowLeft, 0x80)
        }
        if (windowLeft < 160) windowSourceLine++

        // step 4: to buffer (only last line)
        if (line == 143) {
            updateFrameBufferImage()
        }
    }

    /**
     * Invalidate all tiles in the tile cache for the given palette
     */
    override fun invalidateAll(pal: Int) {
        val start = pal * tileCount * 4
        val stop = (pal + 1) * tileCount * 4
        for (r in start until stop) {
            tileImage[r] = null
        }
    }

    override fun setGBCPalette(index: Int, data: Int) {
        super.setGBCPalette(index, data)
        if (index and 0x6 == 0) {
            gbcPalette[index shr 1] = gbcPalette[index shr 1] and 0x00ffffff
        }
    }

    private fun drawSpritesForLine(line: Int) {
        if (!spritesEnabled) return
        val minSpriteY = if (doubledSprites) line - 15 else line - 7

        // either only do priorityFlag == 0 (all foreground),
        // or first 0x80 (background) and then 0 (foreground)
        var priorityFlag = if (spritePriorityEnabled) 0x80 else 0
        while (priorityFlag >= 0) {
            var oamIx = 159
            while (oamIx >= 0) {
                val attributes = 0xff and oam[oamIx--].toInt()
                if (attributes and 0x80 == priorityFlag || !spritePriorityEnabled) {
                    var tileNum = 0xff and oam[oamIx--].toInt()
                    val spriteX = (0xff and oam[oamIx--].toInt()) - 8
                    val spriteY = (0xff and oam[oamIx--].toInt()) - 16
                    val offset = line - spriteY
                    if (spriteX >= 160 || spriteY < minSpriteY || offset < 0) continue
                    if (doubledSprites) {
                        tileNum = tileNum and 0xFE
                    }
                    // flipx: from bit 0x20 to 0x01, flipy: from bit 0x40 to 0x02
                    var spriteAttrib = attributes shr 5 and 0x03
                    if (gbcFeatures) {
                        spriteAttrib += 0x20 + (attributes and 0x07 shl 2) // palette
                        tileNum += (384 shr 3) * (attributes and 0x08) // tile vram bank
                    } else {
                        // attributes 0x10: 0x00 = OBJ1 palette, 0x10 = OBJ2 palette
                        // spriteAttrib: 0x04: OBJ1 palette, 0x08: OBJ2 palette
                        spriteAttrib += 4 + (attributes and 0x10 shr 2)
                    }
                    if (priorityFlag == 0x80) {
                        // background
                        if (doubledSprites) {
                            if (spriteAttrib and TILE_FLIPY != 0) {
                                drawPartBgSprite(
                                    (tileNum or 1) - (offset shr 3),
                                    spriteX,
                                    line,
                                    offset and 7,
                                    spriteAttrib
                                )
                            } else {
                                drawPartBgSprite(
                                    (tileNum and -2) + (offset shr 3),
                                    spriteX,
                                    line,
                                    offset and 7,
                                    spriteAttrib
                                )
                            }
                        } else {
                            drawPartBgSprite(tileNum, spriteX, line, offset, spriteAttrib)
                        }
                    } else {
                        // foreground
                        if (doubledSprites) {
                            if (spriteAttrib and TILE_FLIPY != 0) {
                                drawPartFgSprite(
                                    (tileNum or 1) - (offset shr 3),
                                    spriteX,
                                    line,
                                    offset and 7,
                                    spriteAttrib
                                )
                            } else {
                                drawPartFgSprite(
                                    (tileNum and -2) + (offset shr 3),
                                    spriteX,
                                    line,
                                    offset and 7,
                                    spriteAttrib
                                )
                            }
                        } else {
                            drawPartFgSprite(tileNum, spriteX, line, offset, spriteAttrib)
                        }
                    }
                } else {
                    oamIx -= 3
                }
            }
            priorityFlag -= 0x80
        }
    }

    private fun drawBackgroundForLine(line: Int, windowLeft: Int, priority: Int): Boolean {
        var skippedTile = false
        val sourceY = line + (registers[0x42].toInt() and 0xff)
        val sourceImageLine = sourceY and 7
        var tileNum: Int
        var tileX = registers[0x43].toInt() and 0xff shr 3
        val memStart = (if (hiBgTileMapAddress) 0x1c00 else 0x1800) + (sourceY and 0xf8 shl 2)
        var screenX = -(registers[0x43].toInt() and 7)
        while (screenX < windowLeft) {
            tileNum = if (bgWindowDataSelect) {
                videoRamBanks[0][memStart + (tileX and 0x1f)].toInt() and 0xff
            } else {
                256 + videoRamBanks[0][memStart + (tileX and 0x1f)]
            }
            var tileAttrib = 0
            if (gbcFeatures) {
                val mapAttrib = videoRamBanks[1][memStart + (tileX and 0x1f)].toInt()
                if (mapAttrib and 0x80 != priority) {
                    skippedTile = true
                    tileX++
                    screenX += 8
                    continue
                }
                tileAttrib += mapAttrib and 0x07 shl 2 // palette
                tileAttrib += mapAttrib shr 5 and 0x03 // mirroring
                tileNum += 384 * (mapAttrib shr 3 and 0x01) // tile vram bank
            }
            drawPartCopy(tileNum, screenX, line, sourceImageLine, tileAttrib)
            tileX++
            screenX += 8
        }
        if (windowLeft < 160) {
            // window!
            val windowStartAddress = if (hiWinTileMapAddress) 0x1c00 else 0x1800
            var tileAddress: Int
            val windowSourceTileY = windowSourceLine shr 3
            val windowSourceTileLine = windowSourceLine and 7
            tileAddress = windowStartAddress + windowSourceTileY * 32
            screenX = windowLeft
            while (screenX < 160) {
                tileNum = if (bgWindowDataSelect) {
                    videoRamBanks[0][tileAddress].toInt() and 0xff
                } else {
                    256 + videoRamBanks[0][tileAddress]
                }
                var tileAttrib = 0
                if (gbcFeatures) {
                    val mapAttrib = videoRamBanks[1][tileAddress].toInt()
                    if (mapAttrib and 0x80 != priority) {
                        skippedTile = true
                        tileAddress++
                        screenX += 8
                        continue
                    }
                    tileAttrib += mapAttrib and 0x07 shl 2 // palette
                    tileAttrib += mapAttrib shr 5 and 0x03 // mirroring
                    tileNum += 384 * (mapAttrib shr 3 and 0x01) // tile vram bank
                }
                drawPartCopy(tileNum, screenX, line, windowSourceTileLine, tileAttrib)
                tileAddress++
                screenX += 8
            }
        }
        return skippedTile
    }

    private fun updateFrameBufferImage() {
        if (!lcdEnabled) {
            val buffer = frameBuffer
            for (i in buffer.indices) buffer[i] = -1
            frameBufferImage = createImage(width, height, buffer)
            return
        }
        frameBufferImage = createImage(width, height, frameBuffer)
    }

    /**
     * Create the image of a tile in the tile cache by reading the relevant data from video
     * memory
     */
    private fun updateImage(tileIndex: Int, attribs: Int): IntArray? {
        val index = tileIndex + tileCount * attribs
        val otherBank = tileIndex >= 384
        var offset = if (otherBank) tileIndex - 384 shl 4 else tileIndex shl 4
        val paletteStart = attribs and 0xfc
        val vram = if (otherBank) videoRamBanks[1] else videoRamBanks[0]
        val palette = if (gbcFeatures) gbcPalette else gbPalette
        var transparent = attribs >= transparentCutoff
        var pixix = 0
        var pixixdx = 1
        var pixixdy = 0
        if (attribs and TILE_FLIPY != 0) {
            pixixdy = -2 * 8
            pixix = 8 * (8 - 1)
        }
        if (attribs and TILE_FLIPX == 0) {
            pixixdx = -1
            pixix += 8 - 1
            pixixdy += 8 * 2
        }
        var y = 8
        while (--y >= 0) {
            var num = weaveLookup[vram[offset++].toInt() and 0xff] +
                    (weaveLookup[vram[offset++].toInt() and 0xff] shl 1)
            if (num != 0) transparent = false
            var x = 8
            while (--x >= 0) {
                tempPix[pixix] = palette[paletteStart + (num and 3)]
                pixix += pixixdx
                num = num shr 2
            }
            pixix += pixixdy
        }
        if (transparent) {
            tileImage[index] = transparentImage
        } else {
            tileImage[index] = tempPix
            tempPix = IntArray(8 * 8)
        }
        tileReadState[tileIndex] = true
        return tileImage[index]
    }

    // draws one scanline of the block
    // ignores alpha byte, just copies pixels
    private fun drawPartCopy(tileIndex: Int, x: Int, y: Int, sourceLine: Int, attribs: Int) {
        val ix = tileIndex + tileCount * attribs
        var im = tileImage[ix]
        if (im == null) {
            im = updateImage(tileIndex, attribs)
        }
        var dst = x + y * 160
        var src = sourceLine * 8
        val dstEnd = if (x + 8 > 160) (y + 1) * 160 else dst + 8
        if (x < 0) { // adjust left
            dst -= x
            src -= x
        }
        while (dst < dstEnd) frameBuffer[dst++] = im!![src++]
    }

    // draws one scanline of the block
    // overwrites background when source pixel is opaque
    private fun drawPartFgSprite(tileIndex: Int, x: Int, y: Int, sourceLine: Int, attribs: Int) {
        val ix = tileIndex + tileCount * attribs
        var im = tileImage[ix]
        if (im == null) {
            im = updateImage(tileIndex, attribs)
        }
        if (im.contentEquals(transparentImage)) {
            return
        }
        var dst = x + y * 160
        var src = sourceLine * 8
        val dstEnd = if (x + 8 > 160) (y + 1) * 160 else dst + 8
        if (x < 0) { // adjust left
            dst -= x
            src -= x
        }
        while (dst < dstEnd) {
            if (im!![src] < 0) // fast check for 0xff in high byte
                frameBuffer[dst] = im[src]
            dst++
            src++
        }
    }

    // draws one scanline of the block
    // overwrites background when source pixel is opaque and background is transparent
    private fun drawPartBgSprite(tileIndex: Int, x: Int, y: Int, sourceLine: Int, attribs: Int) {
        val ix = tileIndex + tileCount * attribs
        var im = tileImage[ix]
        if (im == null) {
            im = updateImage(tileIndex, attribs)
        }
        if (im.contentEquals(transparentImage)) {
            return
        }
        var dst = x + y * 160
        var src = sourceLine * 8
        val dstEnd = if (x + 8 > 160) (y + 1) * 160 else dst + 8
        if (x < 0) { // adjust left
            dst -= x
            src -= x
        }
        while (dst < dstEnd) {
            if (im!![src] < 0 && frameBuffer[dst] >= 0) // fast check for 0xff and 0x00 in high byte
                frameBuffer[dst] = im[src]
            dst++
            src++
        }
    }

    private fun createImage(width: Int, height: Int, pixes: IntArray): ImageBitmap {
        val bitmap = Bitmap()
        val bytes = ByteArray(width * height * ColorType.RGBA_8888.bytesPerPixel)
        for ((index, c) in pixes.withIndex()) {
            try {
                val color = Color(c)
                bytes[index * ColorType.RGBA_8888.bytesPerPixel + 0] = (color.red * 255).toInt().toByte()
                bytes[index * ColorType.RGBA_8888.bytesPerPixel + 1] = (color.green * 255).toInt().toByte()
                bytes[index * ColorType.RGBA_8888.bytesPerPixel + 2] = (color.blue * 255).toInt().toByte()
                bytes[index * ColorType.RGBA_8888.bytesPerPixel + 3] = (255).toByte()
            } catch (e: Exception) {
                continue
            }
        }
        val info = ImageInfo(
            colorInfo = ColorInfo(
                colorType = ColorType.RGBA_8888,
                alphaType = ColorAlphaType.PREMUL,
                colorSpace = ColorSpace.sRGB
            ),
            width = width,
            height = height
        )
        bitmap.allocPixels(info)
        bitmap.installPixels(bytes)
        return bitmap.asComposeImageBitmap()
        //Original
        /*val bi = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val target = (bi.raster.dataBuffer as DataBufferInt).data
        System.arraycopy(pixes, 0, target, 0, target.size)
        return bi*/
    }
}