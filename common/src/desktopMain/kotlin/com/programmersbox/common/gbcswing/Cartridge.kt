package com.programmersbox.common.gbcswing

import korlibs.memory.arraycopy
import korlibs.time.Date
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant


internal open class Cartridge(bin: ByteArray) {
    val rom: Array<ByteArray>
    val type: Int
    protected val colored: Boolean
    val hasBattery: Boolean
    val ram: Array<ByteArray>
    val rtcReg: ByteArray
    protected var lastRtcUpdate: Int

    init {
        rom = loadRom(bin)
        type = loadType(bin)
        colored = loadColored(bin)
        hasBattery = loadHasBattery(bin)
        ram = loadRam(bin)
        rtcReg = ByteArray(5)
        lastRtcUpdate = Clock.System.now().toEpochMilliseconds().toInt()//System.currentTimeMillis().toInt()
    }

    private fun loadColored(bin: ByteArray): Boolean {
        return bin[0x143].toInt() and 0x80 == 0x80
    }

    private fun loadRam(bin: ByteArray): Array<ByteArray> {
        /** Translation between ROM size byte contained in the ROM header, and the number
         * of 16kB ROM banks the cartridge will contain
         */
        val ramBankNumber: Int = when (bin[0x149]) {
            1.toByte(), 2.toByte() -> 1
            3.toByte() -> 4
            4.toByte(), 5.toByte(), 6.toByte() -> 16
            else -> 0
        }
        // mbc2 has built-in ram, and anyway we want the memory mapped
        return Array(if (ramBankNumber == 0) 1 else ramBankNumber) { ByteArray(0x2000) }
    }

    // Update the RTC registers before reading/writing (if active) with small delta
    fun rtcSync() {
        if (rtcReg[4].toInt() and 0x40 == 0) {
            // active
            val now: Int = Clock.System.now().toEpochMilliseconds().toInt()
            while (now - lastRtcUpdate > 1000) {
                lastRtcUpdate += 1000
                if ((++rtcReg[0]).toInt() == 60) {
                    rtcReg[0] = 0
                    if ((++rtcReg[1]).toInt() == 60) {
                        rtcReg[1] = 0
                        if ((++rtcReg[2]).toInt() == 24) {
                            rtcReg[2] = 0
                            if ((++rtcReg[3]).toInt() == 0) {
                                rtcReg[4] = (rtcReg[4].toInt() or (rtcReg[4].toInt() shl 7) xor 1).toByte()
                            }
                        }
                    }
                }
            }
        }
    }

    // Update the RTC registers after resuming (large delta)
    protected fun rtcSkip(s: Int) {
        // seconds
        var sum = s + rtcReg[0]
        rtcReg[0] = (sum % 60).toByte()
        sum /= 60
        if (sum == 0) return

        // minutes
        sum += rtcReg[1]
        rtcReg[1] = (sum % 60).toByte()
        sum /= 60
        if (sum == 0) return

        // hours
        sum += rtcReg[2]
        rtcReg[2] = (sum % 24).toByte()
        sum /= 24
        if (sum == 0) return

        // days, bit 0-7
        sum += (rtcReg[3].toInt() and 0xff) + (rtcReg[4].toInt() and 1 shl 8)
        rtcReg[3] = sum.toByte()

        // overflow & day bit 8
        if (sum > 511) rtcReg[4] = (rtcReg[4].toInt() or 0x80).toByte()
        rtcReg[4] = ((rtcReg[4].toInt() and 0xfe) + (sum shr 8 and 1)).toByte()
    }

    fun dumpSram(): ByteArray {
        val bankCount = ram.size
        val bankSize = ram[0].size
        val size = bankCount * bankSize + 13
        val b = ByteArray(size)
        for (i in 0 until bankCount) arraycopy(ram[i], 0, b, i * bankSize, bankSize)
        arraycopy(rtcReg, 0, b, bankCount * bankSize, 5)
        val now: Long = Clock.System.now().toEpochMilliseconds()
        Common.setInt(b, bankCount * bankSize + 5, (now shr 32).toInt())
        Common.setInt(b, bankCount * bankSize + 9, now.toInt())
        return b
    }

    fun setSram(b: ByteArray) {
        val bankCount = ram.size
        val bankSize = ram[0].size
        for (i in 0 until bankCount) arraycopy(b, i * bankSize, ram[i], 0, bankSize)
        if (b.size == bankCount * bankSize + 13) {
            // load real time clock
            arraycopy(b, bankCount * bankSize, rtcReg, 0, 5)
            var time: Long = Common.getInt(b, bankCount * bankSize + 5).toLong()
            time = (time shl 32) + (Common.getInt(b, bankCount * bankSize + 9).toLong() and 0xffffffffL)
            time = Clock.System.now().toEpochMilliseconds().toInt() - time
            rtcSkip((time / 1000).toInt())
        }
    }

    companion object {
        private fun loadRom(bin: ByteArray): Array<ByteArray> {
            /** Translation between ROM size byte contained in the ROM header, and the number
             * of 16kB ROM banks the cartridge will contain
             */
            val cartRomBankNumber: Int
            val sizeByte = bin[0x0148].toInt()
            cartRomBankNumber =
                if (sizeByte < 8) 2 shl sizeByte else if (sizeByte == 0x52) 72 else if (sizeByte == 0x53) 80 else if (sizeByte == 0x54) 96 else -1
            //
            val rom = Array(cartRomBankNumber * 2) {
                ByteArray(
                    0x2000
                )
            }
            for (i in 0 until cartRomBankNumber * 2) {
                if (0x2000 * i < bin.size) arraycopy(bin, 0x2000 * i, rom[i], 0, 0x2000)
            }
            return rom
        }

        private fun loadType(bin: ByteArray): Int {
            return bin[0x0147].toInt() and 0xff
        }

        private fun loadHasBattery(bin: ByteArray): Boolean {
            val type = loadType(bin)
            return type == 3 || type == 9 || type == 0x1B || type == 0x1E || type == 6 || type == 0x10 || type == 0x13
        }
    }
}