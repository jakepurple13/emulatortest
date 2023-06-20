package com.programmersbox.common.gbcswing

import androidx.compose.ui.graphics.ImageBitmap
import com.programmersbox.common.gbcswing.Common.getInt
import com.programmersbox.common.gbcswing.Common.setInt
import korlibs.memory.arraycopy
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlin.jvm.JvmStatic

internal abstract class ScreenAbstract(
    protected var registers: ByteArray,
    protected var memory: Array<ByteArray?>,
    protected var oam: ByteArray,
    protected var gbcFeatures: Boolean,
    private val screenListener: ScreenListener?,
    maxFrameSkip: Int
) {
    private val speed: Speed = Speed()
    protected val MS_PER_FRAME = 17

    /**
     * Tile is flipped horizontally
     */
    protected val TILE_FLIPX = 1 // 0x20 in oam attributes

    /**
     * Tile is flipped vertically
     */
    protected val TILE_FLIPY = 2 // 0x40 in oam attributes

    /**
     * The current contents of the video memory, mapped in at 0x8000 - 0x9FFF
     */
    protected var videoRam: ByteArray
    protected var videoRamBanks // one for gb, two for gbc
            : Array<ByteArray>

    /**
     * RGB color_style values
     */
    protected lateinit var colors // gb color_style palette
            : IntArray
    protected var gbPalette = IntArray(12)
    protected var gbcRawPalette = IntArray(128)
    protected var gbcPalette = IntArray(64)
    protected var gbcMask = 0 // 0xff000000 for simple, 0x80000000 for advanced
    protected var transparentCutoff = 0 // min "attrib" value where transparency can occur
    protected var bgEnabled = true
    protected var winEnabled = true
    protected var spritesEnabled = true
    protected var lcdEnabled = true
    protected var spritePriorityEnabled = true

    // skipping, timing:
    protected var timer = 0
    protected var skipping = true // until graphics is set
    protected var frameCount = 0
    protected var skipCount = 0

    // some statistics
    protected var lastSkipCount = 0

    /**
     * Selection of one of two addresses for the BG and Window tile data areas
     */
    protected var bgWindowDataSelect = true

    /**
     * If true, 8x16 sprites are being used.  Otherwise, 8x8.
     */
    protected var doubledSprites = false

    /**
     * Selection of one of two address for the BG/win tile map.
     */
    protected var hiBgTileMapAddress = false
    protected var hiWinTileMapAddress = false
    protected var tileOffset = 0 // 384 when in vram bank 1 in gbc mode
    protected var tileCount = 0 // 384 for gb, 384*2 for gbc
    protected var colorCount = 0 // number of "logical" colors = palette indices, 12 for gb, 64 for gbc
    protected var width = 160
    protected var height = 144
    protected var frameBufferImage: ImageBitmap? = null
    private val maxFrameSkip: Int

    /**
     * Create a new GraphicsChipImplement connected to the specified ProcessingChip
     */
    init {
        this.maxFrameSkip = maxFrameSkip
        if (gbcFeatures) {
            videoRamBanks = Array(2) { ByteArray(0x2000) }
            tileCount = 384 * 2
            colorCount = 64
            //     1: image
            // * 384: all images
            // *   2: memory banks
            // *   4: mirrored images
            // *  16: all palettes
        } else {
            videoRamBanks = Array(1) { ByteArray(0x2000) }
            tileCount = 384
            colorCount = 12
            //     1: image
            // * 384: all images
            // *   4: mirrored images
            // *   3: all palettes
        }
        videoRam = videoRamBanks[0]
        memory[4] = videoRam
        for (i in gbcRawPalette.indices) gbcRawPalette[i] = -1000 // non-initialized
        for (i in 0 until (gbcPalette.size shr 1)) gbcPalette[i] = -1 // white
        for (i in gbcPalette.size shr 1 until gbcPalette.size) gbcPalette[i] = 0 // transparent
    }

    fun unflatten(flatState: ByteArray, offset: Int): Int {
        var offset = offset
        for (i in videoRamBanks.indices) {
            arraycopy(flatState, offset, videoRamBanks[i], 0, 0x2000)
            offset += 0x2000
        }
        for (i in 0..11) {
            if (i and 3 == 0) gbPalette[i] = 0x00ffffff and getInt(flatState, offset) else gbPalette[i] =
                -0x1000000 or getInt(flatState, offset)
            offset += 4
        }
        UpdateLCDCFlags(registers[0x40].toInt())
        if (gbcFeatures) {
            setVRamBank(flatState[offset++].toInt() and 0xff) // updates tileOffset
            for (i in 0..127) {
                setGBCPalette(i, flatState[offset++].toInt() and 0xff)
            }
        } else {
            invalidateAll(0)
            invalidateAll(1)
            invalidateAll(2)
        }
        return offset
    }

    fun flatten(flatState: ByteArray, offset: Int): Int {
        var offset = offset
        for (i in videoRamBanks.indices) {
            arraycopy(videoRamBanks[i], 0, flatState, offset, 0x2000)
            offset += 0x2000
        }
        for (j in 0..11) {
            setInt(flatState, offset, gbPalette[j])
            offset += 4
        }
        if (gbcFeatures) {
            flatState[offset++] = (if (tileOffset != 0) 1 else 0).toByte()
            for (i in 0..127) {
                flatState[offset++] = getGBCPalette(i).toByte()
            }
        }
        return offset
    }

    fun UpdateLCDCFlags(data: Int) {
        bgEnabled = true

        // BIT 7
        lcdEnabled = data and 0x80 != 0

        // BIT 6
        hiWinTileMapAddress = data and 0x40 != 0

        // BIT 5
        winEnabled = data and 0x20 != 0

        // BIT 4
        bgWindowDataSelect = data and 0x10 != 0

        // BIT 3
        hiBgTileMapAddress = data and 0x08 != 0

        // BIT 2
        doubledSprites = data and 0x04 != 0

        // BIT 1
        spritesEnabled = data and 0x02 != 0

        // BIT 0
        if (gbcFeatures) {
            spritePriorityEnabled = data and 0x01 != 0
        } else {
            if (data and 0x01 == 0) {
                // this emulates the gbc-in-gb-mode, not the original gb-mode
                bgEnabled = false
                winEnabled = false
            }
        }
    }

    fun vBlank() {
        if (!speed.output()) return
        timer += MS_PER_FRAME
        frameCount++
        if (skipping) {
            skipCount++
            if (skipCount >= maxFrameSkip) {
                // can't keep up, force draw next frame and reset timer (if lagging)
                skipping = false
                val lag: Int = Clock.System.now().toEpochMilliseconds().toInt() - timer
                if (lag > MS_PER_FRAME) timer += lag - MS_PER_FRAME
            } else skipping = timer - Clock.System.now().toEpochMilliseconds().toInt() < 0
            return
        }
        lastSkipCount = skipCount
        screenListener?.onFrameReady(frameBufferImage, lastSkipCount)
        var now: Int = Clock.System.now().toEpochMilliseconds().toInt()
        skipping = if (maxFrameSkip == 0) false else timer - now < 0
        // sleep if too far ahead
        try {
            while (timer > now + MS_PER_FRAME) {
                runBlocking { delay(1) }
                now = Clock.System.now().toEpochMilliseconds().toInt()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        skipCount = 0
    }

    /**
     * Set the palette from the internal GameBoy format
     */
    fun decodePalette(startIndex: Int, data: Int) {
        for (i in 0..3) gbPalette[startIndex + i] = colors[data shr 2 * i and 0x03]
        gbPalette[startIndex] = gbPalette[startIndex] and 0x00ffffff // color_style 0: transparent
    }

    open fun setGBCPalette(index: Int, data: Int) {
        if (gbcRawPalette[index] == data) return
        gbcRawPalette[index] = data
        if (index >= 0x40 && index and 0x6 == 0) {
            // stay transparent
            return
        }
        val value = (gbcRawPalette[index or 1] shl 8) + gbcRawPalette[index and -2]
        gbcPalette[index shr 1] =
            gbcMask + (value and 0x001F shl 19) + (value and 0x03E0 shl 6) + (value and 0x7C00 shr 7)
        invalidateAll(index shr 3)
    }

    fun getGBCPalette(index: Int): Int {
        return gbcRawPalette[index]
    }

    fun setVRamBank(value: Int) {
        tileOffset = value * 384
        videoRam = videoRamBanks[value]
        memory[4] = videoRam
    }

    fun stopWindowFromLine() {}
    fun fixTimer() {
        timer = Clock.System.now().toEpochMilliseconds().toInt()
    }

    /**
     * Writes data to the specified video RAM address
     */
    abstract fun addressWrite(addr: Int, data: Byte)

    /**
     * Invalidate all tiles in the tile cache for the given palette
     */
    abstract fun invalidateAll(pal: Int)

    /**
     * This must be called by the ProcessingChip for each scanline drawn by the display hardware.
     */
    abstract fun notifyScanline(line: Int)

    // SPEED
    fun setSpeed(i: Int) {
        speed.setSpeed(i)
    }

    fun getSpeed() = speed.getSpeed()

    companion object {
        // lookup table for fast image decoding
        @JvmStatic
        protected val weaveLookup = IntArray(256)

        init {
            for (i in 1..255) {
                for (d in 0..7) weaveLookup[i] += i shr d and 1 shl d * 2
            }
        }
    }
}