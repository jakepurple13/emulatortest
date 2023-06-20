package com.programmersbox.common.gbcswing

import com.programmersbox.common.gbcswing.Common.getInt
import com.programmersbox.common.gbcswing.Common.setInt
import com.programmersbox.common.gbcswing.Common.showError
import korlibs.memory.KmemGC
import korlibs.memory.arraycopy
import kotlinx.coroutines.*

class GameBoy(
    var gbcFeatures: Boolean,
    palette: IntArray?,
    cartridgeBin: ByteArray?,
    private val controller: Controller,
    screenListener: ScreenListener?
) : Runnable {
    // Constants for flags register
    /**
     * Zero flag
     */
    private val F_ZERO = 0x80

    /**
     * Subtract/negative flag
     */
    private val F_SUBTRACT = 0x40

    /**
     * Half carry flag
     */
    private val F_HALFCARRY = 0x20

    /**
     * Carry flag
     */
    private val F_CARRY = 0x10

    // same in single and double speed:
    protected val INSTRS_PER_DIV = 64

    // single speed values:
    protected val BASE_INSTRS_IN_MODE_0 = 51
    protected val BASE_INSTRS_IN_MODE_2 = 20
    protected val BASE_INSTRS_IN_MODE_3 = 43

    /**
     * Used to set the speed of the emulator.  This controls how
     * many instructions are executed for each horizontal line scanned
     * on the gbc.  Multiply by 154 to find out how many instructions
     * per frame.
     */
    protected var INSTRS_IN_MODE_0 = BASE_INSTRS_IN_MODE_0
    protected var INSTRS_IN_MODE_2 = BASE_INSTRS_IN_MODE_2
    protected var INSTRS_IN_MODE_3 = BASE_INSTRS_IN_MODE_3
    // Constants for interrupts
    /**
     * Vertical blank interrupt
     */
    val INT_VBLANK = 0x01

    /**
     * LCD Coincidence interrupt
     */
    val INT_LCDC = 0x02

    /**
     * TIMA (programmable timer) interrupt
     */
    val INT_TIMA = 0x04

    /**
     * Serial interrupt
     */
    val INT_SER = 0x08

    /**
     * P10 - P13 (Joypad) interrupt
     */
    val INT_P10 = 0x10

    /**
     * Registers: 8-bit
     */
    private var a = 0
    private var b = 0
    private var c = 0
    private var d = 0
    private var e = 0
    private var f = 0

    /**
     * Registers: 16-bit
     */
    private var sp = 0
    private var hl = 0

    // decoder variables
    private var decoderMemory: ByteArray? = null
    private var localPC = 0
    private var globalPC = 0
    private var decoderMaxCruise = 0 // if localPC exceeds this, a new (half)bank should be found

    /**
     * The number of instructions that have been executed since the last reset
     */
    private var instrCount = 0
    private var graphicsChipMode = 0 // takes values 0,2,3 -- mode 1 is signaled by line>=144
    private var nextModeTime = 0
    private var nextTimaOverflow = 0
    private var nextTimedInterrupt = 0
    var interruptsEnabled = false
    var interruptsArmed = false
    private var timaActive = false
    private var interruptEnableRequested = false
    protected var gbcRamBank = 0
    protected var hdmaRunning = false

    // 0,1 = rom bank 0
    // 2,3 = mapped rom bank
    // 4 = vram (read only)
    // 5 = mapped cartram
    // 6 = main ram
    // 7 = main ram again (+ oam+reg)
    var memory = arrayOfNulls<ByteArray>(8)

    // 8kB main system RAM appears at 0xC000 in address space
    // 32kB for GBC
    private val mainRam: ByteArray

    // sprite ram, at 0xfe00
    var oam = ByteArray(0x100)

    /**
     * registers, at 0xff00
     */
    var registers = ByteArray(0x100)

    /**
     * instrCount at the time register[4] was reset
     */
    private var divReset = 0
    private var instrsPerTima = 256
    var isTerminated = false

    /**
     * The bank number which is currently mapped at 0x4000 in ProcessingChip address space
     */
    private var currentRomBank = 1

    /**
     * The RAM bank number which is currently mapped at 0xA000 in ProcessingChip address space
     */
    private var currentRamBank = 0
    private var mbc1LargeRamMode = false
    private var cartRamEnabled = false
    private val incflags = IntArray(256)
    private val decflags = IntArray(256)
    private val cartridge: Cartridge
    private val screen: ScreenAbstract
    private var speaker: Speaker? = null
    private fun init() {
        gbcRamBank = 1
        memory[0] = cartridge.rom[0]
        memory[1] = cartridge.rom[1]
        mapRom(1)
        memory[5] = cartridge.ram[0]
        memory[6] = mainRam
        memory[7] = mainRam
        interruptsEnabled = false
        a = if (gbcFeatures) 0x11 else 0x01
        b = 0x00
        c = 0x13
        d = 0x00
        e = 0xd8
        f = 0xB0
        hl = 0x014D
        setPC(0x0100)
        sp = 0xFFFE
        graphicsChipMode = 0
        nextModeTime = 0
        timaActive = false
        interruptEnableRequested = false
        nextTimedInterrupt = 0
        initIncDecFlags()
        ioHandlerReset()
    }

    private fun initIncDecFlags() {
        incflags[0] = F_ZERO + F_HALFCARRY
        run {
            var i = 0x10
            while (i < 0x100) {
                incflags[i] = F_HALFCARRY
                i += 0x10
            }
        }
        decflags[0] = F_ZERO + F_SUBTRACT
        for (i in 1..0xff) decflags[i] = F_SUBTRACT + if (i and 0x0f == 0x0f) F_HALFCARRY else 0
    }

    /**
     * Perform a ProcessingChip address space read.  This maps all the relevant objects into the correct parts of
     * the memory
     */
    fun addressRead1(addr: Int): Int {
        return if (addr < 0xa000) {
            memory[addr shr 13]!![addr and 0x1fff].toInt()
        } else if (addr < 0xc000) {
            if (currentRamBank >= 8) { // real time clock
                cartridge.rtcSync()
                cartridge.rtcReg[currentRamBank - 8].toInt()
            } else memory[addr shr 13]!![addr and 0x1fff].toInt()
        } else if (addr and 0x1000 == 0) {
            mainRam[addr and 0x0fff].toInt()
        } else if (addr < 0xfe00) {
            mainRam[(addr and 0x0fff) + gbcRamBank * 0x1000].toInt()
        } else if (addr < 0xFF00) {
            if (addr > 0xFEA0) 0xff else oam[addr - 0xFE00].toInt() and 0xFF
        } else {
            ioRead(addr - 0xFF00)
        }
    }

    /**
     * Performs a ProcessingChip address space write.  Maps all of the relevant object into the right parts of
     * memory.
     */
    fun addressWrite(addr: Int, data: Int) {
        val bank = addr shr 12
        when (bank) {
            0x0, 0x1, 0x2, 0x3, 0x4, 0x5, 0x6, 0x7 -> cartridgeWrite(addr, data)
            0x8, 0x9 -> screen.addressWrite(addr - 0x8000, data.toByte())
            0xA, 0xB -> cartridgeWrite(addr, data)
            0xC -> mainRam[addr - 0xC000] = data.toByte()
            0xD -> mainRam[addr - 0xD000 + gbcRamBank * 0x1000] = data.toByte()
            0xE -> mainRam[addr - 0xE000] = data.toByte()
            0xF -> if (addr < 0xFE00) {
                mainRam[addr - 0xF000 + gbcRamBank * 0x1000] = data.toByte()
            } else if (addr < 0xFF00) {
                oam[addr - 0xFE00] = data.toByte()
            } else {
                ioWrite(addr - 0xFF00, data)
            }
        }
    }

    private fun pushPC() {
        val pc = globalPC + localPC
        if (sp shr 13 == 6) {
            mainRam[--sp - 0xC000] = (pc shr 8).toByte()
            mainRam[--sp - 0xC000] = pc.toByte()
        } else {
            addressWrite(--sp, pc shr 8)
            addressWrite(--sp, pc and 0xFF)
        }
    }

    private fun popPC() {
        if (sp shr 13 == 6) {
            setPC((mainRam[sp++ - 0xc000].toInt() and 0xff) + (mainRam[sp++ - 0xc000].toInt() and 0xff shl 8))
        } else {
            setPC((addressRead(sp++) and 0xff) + (addressRead(sp++) and 0xff shl 8))
        }
    }

    /**
     * Performs a read of a register by internal register number
     */
    private fun registerRead(regNum: Int): Int {
        return when (regNum) {
            0 -> b
            1 -> c
            2 -> d
            3 -> e
            4 -> hl shr 8
            5 -> hl and 0xFF
            6 -> addressRead(hl) and 0xff
            7 -> a
            else -> -1
        }
    }

    /**
     * Performs a write of a register by internal register number
     */
    private fun registerWrite(regNum: Int, data: Int) {
        when (regNum) {
            0 -> {
                b = data
            }

            1 -> {
                c = data
            }

            2 -> {
                d = data
            }

            3 -> {
                e = data
            }

            4 -> {
                // h
                hl = hl and 0x00FF or (data shl 8)
            }

            5 -> {
                // l
                hl = hl and 0xFF00 or data
            }

            6 -> {
                // (hl)
                addressWrite(hl, data)
            }

            7 -> {
                a = data
            }
        }
    }

    private fun performHdma() {
        var dmaSrc = (registers[0x51].toInt() and 0xff shl 8) + (registers[0x52].toInt() and 0xff and 0xF0)
        var dmaDst = (registers[0x53].toInt() and 0x1F shl 8) + (registers[0x54].toInt() and 0xF0) + 0x8000
        for (r in 0..15) {
            addressWrite(dmaDst + r, addressRead(dmaSrc + r))
        }
        dmaSrc += 16
        dmaDst += 16
        registers[0x51] = (dmaSrc and 0xFF00 shr 8).toByte()
        registers[0x52] = (dmaSrc and 0x00F0).toByte()
        registers[0x53] = (dmaDst and 0x1F00 shr 8).toByte()
        registers[0x54] = (dmaDst and 0x00F0).toByte()
        if (registers[0x55].toInt() == 0) {
            hdmaRunning = false
        }
        registers[0x55]--
    }

    /**
     * If an interrupt is enabled an the interrupt register shows that it has occured, jump to
     * the relevant interrupt vector address
     */
    private fun checkInterrupts() {
        pushPC()
        val mask = registers[0xff].toInt() and registers[0x0f].toInt()
        if (mask and INT_VBLANK != 0) {
            setPC(0x40)
            registers[0x0f] = (registers[0x0f] - INT_VBLANK.toByte()).toByte()
        } else if (mask and INT_LCDC != 0) {
            setPC(0x48)
            registers[0x0f] = (registers[0x0f] - INT_LCDC.toByte()).toByte()
        } else if (mask and INT_TIMA != 0) {
            setPC(0x50)
            registers[0x0f] = (registers[0x0f] - INT_TIMA.toByte()).toByte()
        } else if (mask and INT_SER != 0) {
            setPC(0x58)
            registers[0x0f] = (registers[0x0f] - INT_SER.toByte()).toByte()
        } else if (mask and INT_P10 != 0) {
            setPC(0x60)
            registers[0x0f] = (registers[0x0f] - INT_P10.toByte()).toByte()
        } else {
            // throw new RuntimeException("concurrent modification exception: " + mask + " "
            //		+ registers[0xff] + " " + registers[0x0f]);
        }
        interruptsEnabled = false
        interruptsArmed = registers[0xff].toInt() and registers[0x0f].toInt() != 0
    }

    /**
     * Check for interrupts that need to be initiated
     */
    private fun initiateInterrupts() {
        if (instrCount - nextModeTime >= 0) {
            // changed graphics chip mode
            if (graphicsChipMode == 3) {
                // entered mode 0 (unless in vblank)
                graphicsChipMode = 0
                nextModeTime += INSTRS_IN_MODE_0
                val line = registers[0x44].toInt() and 0xff
                if (line < 144) {
                    if (gbcFeatures && hdmaRunning) {
                        performHdma()
                    }
                    if (registers[0x40].toInt() and 0x80 != 0 && registers[0xff].toInt() and INT_LCDC != 0) {
                        if (registers[0x41].toInt() and 0x08 != 0) {
                            // trigger "mode 0 entered" interrupt
                            interruptsArmed = true
                            registers[0x0f] = (registers[0x0f].toInt() or INT_LCDC).toByte()
                        }
                    }
                }
            } else if (graphicsChipMode == 0) {
                // entered mode 2 (or mode 1, i.e. vblank)
                graphicsChipMode = 2
                nextModeTime += INSTRS_IN_MODE_2
                registers[0x44]++
                if (registers[0x44].toInt() and 0xff == 154) {
                    registers[0x44] = 0
                }
                val line = registers[0x44].toInt() and 0xff

                // check for mode 2 interrupt
                if (line < 144) {
                    if (registers[0x41].toInt() and 0x20 != 0) {
                        // trigger "mode 2 entered" interrupt
                        interruptsArmed = true
                        registers[0x0f] = (registers[0x0f].toInt() or INT_LCDC).toByte()
                    }
                }

                // check for lyc coincidence interrupt
                if (registers[0x40].toInt() and 0x80 != 0 && registers[0xff].toInt() and INT_LCDC != 0) {
                    if (registers[0x41].toInt() and 0x40 != 0 && registers[0x45].toInt() and 0xff == line) {
                        // trigger "lyc coincidence" interrupt
                        interruptsArmed = true
                        registers[0x0f] = (registers[0x0f].toInt() or INT_LCDC).toByte()
                    }
                }
                if (line == 144) {
                    // whole frame done, draw buffer and start vblank
                    screen.vBlank()
                    if (registers[0x40].toInt() and 0x80 != 0 && registers[0xff].toInt() and INT_VBLANK != 0) {
                        interruptsArmed = true
                        registers[0x0f] = (registers[0x0f].toInt() or INT_VBLANK).toByte()
                        if (registers[0x41].toInt() and 0x10 != 0 && registers[0xff].toInt() and INT_LCDC != 0) {
                            // VBLANK LCDC
                            // armed is already set
                            registers[0x0f] = (registers[0x0f].toInt() or INT_LCDC).toByte()
                        }
                    }
                    speaker?.outputSound()
                }
                if (line == 0) {
                    if (controller.p10Requested) {
                        controller.p10Requested = false
                        if (registers[0xff].toInt() and INT_P10 != 0) {
                            registers[0x0f] = (registers[0x0f].toInt() or INT_P10).toByte()
                        }
                        interruptsArmed = registers[0xff].toInt() and registers[0x0f].toInt() != 0
                    }
                }
            } else {
                // entered mode 3 (unless in vblank)
                graphicsChipMode = 3
                nextModeTime += INSTRS_IN_MODE_3
                val line = registers[0x44].toInt() and 0xff
                if (line < 144) {
                    // send the line to graphic chip
                    screen.notifyScanline(line)
                }
            }
        }
        if (timaActive && instrCount - nextTimaOverflow >= 0) {
            nextTimaOverflow += instrsPerTima * (0x100 - (registers[0x06].toInt() and 0xff))
            if (registers[0xff].toInt() and INT_TIMA != 0) {
                interruptsArmed = true
                registers[0x0f] = (registers[0x0f].toInt() or INT_TIMA).toByte()
            }
        }
        if (interruptEnableRequested) {
            interruptsEnabled = true
            interruptEnableRequested = false
        }
        nextTimedInterrupt = nextModeTime
        if (timaActive && nextTimaOverflow < nextTimedInterrupt) nextTimedInterrupt = nextTimaOverflow
    }

    fun setPC(pc: Int) {
        if (pc < 0xff00) {
            decoderMemory = memory[pc shr 13]
            localPC = pc and 0x1fff
            globalPC = pc and 0xe000
            decoderMaxCruise = if (pc < 0xe000) 0x1ffd else 0x1dfd
            if (gbcFeatures) {
                if (gbcRamBank > 1 && pc >= 0xC000) decoderMaxCruise =
                    decoderMaxCruise and 0x0fff // can't cruise in switched ram bank
            }
        } else {
            decoderMemory = registers
            localPC = pc and 0xff
            globalPC = 0xff00
            decoderMaxCruise = 0xfd
        }
    }

    private fun executeShift(b2: Int) {
        val regNum = b2 and 0x07
        var data = registerRead(regNum)
        val newf: Int
        instrCount += cyclesPerInstrShift[b2]

        /*
        00ooorrr = operation ooo on register rrr
		01bbbrrr = test bit bbb of register rrr
		10bbbrrr = reset bit bbb of register rrr
		11bbbrrr = set bit bbb of register rrr
		*/if (b2 and 0xC0 == 0) {
            when (b2 and 0xF8) {
                0x00 -> {
                    f = 0
                    if (data >= 0x80) {
                        f = F_CARRY
                    }
                    data = data shl 1 and 0xff
                    if (f and F_CARRY != 0) {
                        data = data or 1
                    }
                }

                0x08 -> {
                    f = 0
                    if (data and 0x01 != 0) {
                        f = F_CARRY
                    }
                    data = data shr 1
                    if (f and F_CARRY != 0) {
                        data = data or 0x80
                    }
                }

                0x10 -> {
                    newf = if (data >= 0x80) {
                        F_CARRY
                    } else {
                        0
                    }
                    data = data shl 1 and 0xff
                    if (f and F_CARRY != 0) {
                        data = data or 1
                    }
                    f = newf
                }

                0x18 -> {
                    newf = if (data and 0x01 != 0) {
                        F_CARRY
                    } else {
                        0
                    }
                    data = data shr 1
                    if (f and F_CARRY != 0) {
                        data = data or 0x80
                    }
                    f = newf
                }

                0x20 -> {
                    f = 0
                    if (data and 0x80 != 0) {
                        f = F_CARRY
                    }
                    data = data shl 1 and 0xff
                }

                0x28 -> {
                    f = 0
                    if (data and 0x01 != 0) {
                        f = F_CARRY
                    }
                    data = (data and 0x80) + (data shr 1) // i.e. duplicate high bit=sign
                }

                0x30 -> {
                    data = data and 0x0F shl 4 or (data shr 4)
                    f = 0
                }

                0x38 -> {
                    f = 0
                    if (data and 0x01 != 0) {
                        f = F_CARRY
                    }
                    data = data shr 1
                }
            }
            if (data == 0) {
                f = f or F_ZERO
            }
            registerWrite(regNum, data)
        } else {
            val bitMask = 1 shl (b2 and 0x38 shr 3)
            if (b2 and 0xC0 == 0x40) { // BIT n, r
                f = f and F_CARRY or F_HALFCARRY
                if (data and bitMask == 0) {
                    f = f or F_ZERO
                }
            } else if (b2 and 0xC0 == 0x80) { // RES n, r
                registerWrite(regNum, data and 0xFF - bitMask)
            } else if (b2 and 0xC0 == 0xC0) { // SET n, r
                registerWrite(regNum, data or bitMask)
            }
        }
    }

    private fun executeDAA() {
        val upperNibble = a shr 4 and 0x0f
        val lowerNibble = a and 0x0f
        var newf = f and (F_SUBTRACT or F_CARRY)
        if (f and F_SUBTRACT == 0) {
            if (f and F_CARRY == 0) {
                if (upperNibble <= 8 && lowerNibble >= 0xA && f and F_HALFCARRY == 0) {
                    a += 0x06
                }
                if (upperNibble <= 9 && lowerNibble <= 0x3 && f and F_HALFCARRY != 0) {
                    a += 0x06
                }
                if (upperNibble >= 0xA && lowerNibble <= 0x9 && f and F_HALFCARRY == 0) {
                    a += 0x60
                    newf = newf or F_CARRY
                }
                if (upperNibble >= 0x9 && lowerNibble >= 0xA && f and F_HALFCARRY == 0) {
                    a += 0x66
                    newf = newf or F_CARRY
                }
                if (upperNibble >= 0xA && lowerNibble <= 0x3 && f and F_HALFCARRY != 0) {
                    a += 0x66
                    newf = newf or F_CARRY
                }
            } else { // carry is set
                if (upperNibble <= 0x2 && lowerNibble <= 0x9 && f and F_HALFCARRY == 0) {
                    a += 0x60
                }
                if (upperNibble <= 0x2 && lowerNibble >= 0xA && f and F_HALFCARRY == 0) {
                    a += 0x66
                }
                if (upperNibble <= 0x3 && lowerNibble <= 0x3 && f and F_HALFCARRY != 0) {
                    a += 0x66
                }
            }
        } else { // subtract is set
            if (f and F_CARRY == 0) {
                if (upperNibble <= 0x8 && lowerNibble >= 0x6 && f and F_HALFCARRY != 0) {
                    a += 0xFA
                }
            } else { // Carry is set
                if (upperNibble >= 0x7 && lowerNibble <= 0x9 && f and F_HALFCARRY == 0) {
                    a += 0xA0
                }
                if (upperNibble >= 0x6 && lowerNibble >= 0x6 && f and F_HALFCARRY != 0) {
                    a += 0x9A
                }
            }
        }
        a = a and 0xff
        if (a == 0) newf = newf or F_ZERO

        // halfcarry is wrong
        f = newf
    }

    private fun executeALU(b1: Int) {
        var operand = registerRead(b1 and 0x07)
        when (b1 and 0x38 shr 3) {
            1 -> {
                if (f and F_CARRY != 0) {
                    operand++
                }
                f = if ((a and 0x0F) + (operand and 0x0F) >= 0x10) {
                    F_HALFCARRY
                } else {
                    0
                }
                a += operand
                if (a > 0xff) {
                    f = f or F_CARRY
                    a = a and 0xff
                }
                if (a == 0) {
                    f = f or F_ZERO
                }
            }

            0 -> {
                f = if ((a and 0x0F) + (operand and 0x0F) >= 0x10) {
                    F_HALFCARRY
                } else {
                    0
                }
                a += operand
                if (a > 0xff) {
                    f = f or F_CARRY
                    a = a and 0xff
                }
                if (a == 0) {
                    f = f or F_ZERO
                }
            }

            3 -> {
                if (f and F_CARRY != 0) {
                    operand++
                }
                f = F_SUBTRACT
                if (a and 0x0F < operand and 0x0F) {
                    f = f or F_HALFCARRY
                }
                a -= operand
                if (a < 0) {
                    f = f or F_CARRY
                    a = a and 0xff
                }
                if (a == 0) {
                    f = f or F_ZERO
                }
            }

            2 -> {
                f = F_SUBTRACT
                if (a and 0x0F < operand and 0x0F) {
                    f = f or F_HALFCARRY
                }
                a -= operand
                if (a < 0) {
                    f = f or F_CARRY
                    a = a and 0xff
                }
                if (a == 0) {
                    f = f or F_ZERO
                }
            }

            4 -> {
                a = a and operand
                f = if (a == 0) {
                    F_HALFCARRY + F_ZERO
                } else {
                    F_HALFCARRY
                }
            }

            5 -> {
                a = a xor operand
                f = if (a == 0) F_ZERO else 0
            }

            6 -> {
                a = a or operand
                f = if (a == 0) F_ZERO else 0
            }

            7 -> {
                f = F_SUBTRACT
                if (a == operand) {
                    f = f or F_ZERO
                } else if (a < operand) {
                    f = f or F_CARRY
                }
                if (a and 0x0F < operand and 0x0F) {
                    f = f or F_HALFCARRY
                }
            }
        }
    }

    override fun run() {
        try {
            isTerminated = false
            var newf: Int
            var b1: Int
            var b2: Int
            var offset: Int
            var b3: Int
            KmemGC.collect()
            screen.fixTimer()
            while (!isTerminated) {
                if (localPC <= decoderMaxCruise) {
                    b1 = decoderMemory!![localPC++].toInt() and 0xff
                    offset = decoderMemory!![localPC].toInt()
                    b2 = offset and 0xff
                    b3 = decoderMemory!![localPC + 1].toInt()
                } else {
                    var pc = localPC + globalPC
                    b1 = addressRead(pc++) and 0xff
                    offset = addressRead(pc)
                    b2 = offset and 0xff
                    b3 = addressRead(pc + 1)
                    setPC(pc)
                }
                when (b1) {
                    0x00 -> {}
                    0x01 -> {
                        localPC += 2
                        b = b3 and 0xff
                        c = b2
                    }

                    0x02 -> addressWrite(b shl 8 or c, a)
                    0x03 -> {
                        c++
                        if (c == 0x0100) {
                            c = 0
                            b = b + 1 and 0xff
                        }
                    }

                    0x04 -> {
                        b = b + 1 and 0xff
                        f = f and F_CARRY or incflags[b]
                    }

                    0x05 -> {
                        b = b - 1 and 0xff
                        f = f and F_CARRY or decflags[b]
                    }

                    0x06 -> {
                        localPC++
                        b = b2
                    }

                    0x07 -> if (a >= 0x80) {
                        f = F_CARRY
                        a = (a shl 1) + 1 and 0xff
                    } else if (a == 0) {
                        f = F_ZERO
                    } else {
                        a = a shl 1
                        f = 0
                    }

                    0x08 -> {
                        localPC += 2
                        newf = (b3 and 0xff shl 8) + b2
                        addressWrite(newf, sp)
                        addressWrite(newf + 1, sp shr 8)
                    }

                    0x09 -> {
                        hl += ((b shl 8) + c)
                        if (hl and -0x10000 != 0) {
                            f = f and F_ZERO or F_CARRY // halfcarry is wrong
                            hl = hl and 0xFFFF
                        } else {
                            f = f and F_ZERO // halfcarry is wrong
                        }
                    }

                    0x0A -> a = addressRead((b shl 8) + c) and 0xff
                    0x0B -> {
                        c--
                        if (c < 0) {
                            c = 0xFF
                            b = b - 1 and 0xff
                        }
                    }

                    0x0C -> {
                        c = c + 1 and 0xff
                        f = f and F_CARRY or incflags[c]
                    }

                    0x0D -> {
                        c = c - 1 and 0xff
                        f = f and F_CARRY or decflags[c]
                    }

                    0x0E -> {
                        localPC++
                        c = b2
                    }

                    0x0F -> {
                        f = if (a and 0x01 == 0x01) {
                            F_CARRY
                        } else {
                            0
                        }
                        a = a shr 1
                        if (f and F_CARRY != 0) {
                            a = a or 0x80
                        }
                        if (a == 0) {
                            f = f or F_ZERO
                        }
                    }

                    0x10 -> {
                        localPC++
                        if (gbcFeatures) {
                            if (registers[0x4D].toInt() and 0x01 != 0) {
                                var newKey1Reg = registers[0x4D].toInt() and 0xFE
                                var multiplier = 1
                                if (newKey1Reg and 0x80 != 0) {
                                    newKey1Reg = newKey1Reg and 0x7F
                                } else {
                                    multiplier = 2
                                    newKey1Reg = newKey1Reg or 0x80
                                }
                                INSTRS_IN_MODE_0 = BASE_INSTRS_IN_MODE_0 * multiplier
                                INSTRS_IN_MODE_2 = BASE_INSTRS_IN_MODE_2 * multiplier
                                INSTRS_IN_MODE_3 = BASE_INSTRS_IN_MODE_3 * multiplier
                                registers[0x4D] = newKey1Reg.toByte()
                            }
                        }
                    }

                    0x11 -> {
                        localPC += 2
                        d = b3 and 0xff
                        e = b2
                    }

                    0x12 -> addressWrite((d shl 8) + e, a)
                    0x13 -> {
                        e++
                        if (e == 0x0100) {
                            e = 0
                            d = d + 1 and 0xff
                        }
                    }

                    0x14 -> {
                        d = d + 1 and 0xff
                        f = f and F_CARRY or incflags[d]
                    }

                    0x15 -> {
                        d = d - 1 and 0xff
                        f = f and F_CARRY or decflags[d]
                    }

                    0x16 -> {
                        localPC++
                        d = b2
                    }

                    0x17 -> {
                        newf = if (a and 0x80 != 0) {
                            F_CARRY
                        } else {
                            0
                        }
                        a = a shl 1
                        if (f and F_CARRY != 0) {
                            a = a or 1
                        }
                        a = a and 0xFF
                        if (a == 0) {
                            newf = newf or F_ZERO
                        }
                        f = newf
                    }

                    0x18 -> {
                        localPC += 1 + offset
                        if (localPC < 0 || localPC > decoderMaxCruise) {
                            // switch bank
                            setPC(localPC + globalPC)
                        }
                    }

                    0x19 -> {
                        hl += (d shl 8) + e
                        if (hl and -0x10000 != 0) {
                            f = f and F_ZERO or F_CARRY // halfcarry is wrong
                            hl = hl and 0xFFFF
                        } else {
                            f = f and F_ZERO // halfcarry is wrong
                        }
                    }

                    0x1A -> a = addressRead((d shl 8) + e) and 0xff
                    0x1B -> {
                        e--
                        if (e < 0) {
                            e = 0xFF
                            d = d - 1 and 0xff
                        }
                    }

                    0x1C -> {
                        e = e + 1 and 0xff
                        f = f and F_CARRY or incflags[e]
                    }

                    0x1D -> {
                        e = e - 1 and 0xff
                        f = f and F_CARRY or decflags[e]
                    }

                    0x1E -> {
                        localPC++
                        e = b2
                    }

                    0x1F -> {
                        newf = if (a and 0x01 != 0) {
                            F_CARRY
                        } else {
                            0
                        }
                        a = a shr 1
                        if (f and F_CARRY != 0) {
                            a = a or 0x80
                        }
                        if (a == 0) {
                            newf = newf or F_ZERO
                        }
                        f = newf
                    }

                    0x20 -> if (f < F_ZERO) {
                        localPC += 1 + offset
                        if (localPC < 0 || localPC > decoderMaxCruise) {
                            // switch bank
                            setPC(localPC + globalPC)
                        }
                    } else {
                        localPC++
                    }

                    0x21 -> {
                        localPC += 2
                        hl = (b3 and 0xff shl 8) + b2
                    }

                    0x22 -> addressWrite(hl++, a)
                    0x23 -> hl = hl + 1 and 0xFFFF
                    0x24 -> {
                        b2 = (hl shr 8) + 1 and 0xff
                        f = f and F_CARRY or incflags[b2]
                        hl = (hl and 0xff) + (b2 shl 8)
                    }

                    0x25 -> {
                        b2 = (hl shr 8) - 1 and 0xff
                        f = f and F_CARRY or decflags[b2]
                        hl = (hl and 0xff) + (b2 shl 8)
                    }

                    0x26 -> {
                        localPC++
                        hl = hl and 0xFF or (b2 shl 8)
                    }

                    0x27 -> executeDAA()
                    0x28 -> if (f >= F_ZERO) {
                        localPC += 1 + offset
                        if (localPC < 0 || localPC > decoderMaxCruise) {
                            // switch bank
                            setPC(localPC + globalPC)
                        }
                    } else {
                        localPC++
                    }

                    0x29 -> {
                        hl *= 2
                        if (hl and -0x10000 != 0) {
                            f = f and F_ZERO or F_CARRY // halfcarry is wrong
                            hl = hl and 0xFFFF
                        } else {
                            f = f and F_ZERO // halfcarry is wrong
                        }
                    }

                    0x2A -> a = addressRead(hl++) and 0xff
                    0x2B -> hl = hl - 1 and 0xffff
                    0x2C -> {
                        b2 = hl + 1 and 0xff
                        f = f and F_CARRY or incflags[b2]
                        hl = (hl and 0xff00) + b2
                    }

                    0x2D -> {
                        b2 = hl - 1 and 0xff
                        f = f and F_CARRY or decflags[b2]
                        hl = (hl and 0xff00) + b2
                    }

                    0x2E -> {
                        localPC++
                        hl = hl and 0xFF00 or b2
                    }

                    0x2F -> {
                        a = a.inv() and 0x00FF
                        f = f or (F_SUBTRACT or F_HALFCARRY)
                    }

                    0x30 -> if (f and F_CARRY == 0) {
                        localPC += 1 + offset
                        if (localPC < 0 || localPC > decoderMaxCruise) {
                            // switch bank
                            setPC(localPC + globalPC)
                        }
                    } else {
                        localPC++
                    }

                    0x31 -> {
                        localPC += 2
                        sp = (b3 and 0xff shl 8) + b2
                    }

                    0x32 -> addressWrite(hl--, a) // LD (HL-), A
                    0x33 -> sp = sp + 1 and 0xFFFF
                    0x34 -> {
                        b2 = addressRead(hl) + 1 and 0xff
                        f = f and F_CARRY or incflags[b2]
                        addressWrite(hl, b2)
                    }

                    0x35 -> {
                        b2 = addressRead(hl) - 1 and 0xff
                        f = f and F_CARRY or decflags[b2]
                        addressWrite(hl, b2)
                    }

                    0x36 -> {
                        localPC++
                        addressWrite(hl, b2)
                    }

                    0x37 -> f = f and F_ZERO or F_CARRY
                    0x38 -> if (f and F_CARRY != 0) {
                        localPC += 1 + offset
                        if (localPC < 0 || localPC > decoderMaxCruise) {
                            // switch bank
                            setPC(localPC + globalPC)
                        }
                    } else {
                        localPC += 1
                    }

                    0x39 -> {
                        hl += sp
                        if (hl > 0x0000FFFF) {
                            f = f and F_ZERO or F_CARRY // halfcarry is wrong
                            hl = hl and 0xFFFF
                        } else {
                            f = f and F_ZERO // halfcarry is wrong
                        }
                    }

                    0x3A -> a = addressRead(hl--) and 0xff
                    0x3B -> sp = sp - 1 and 0xFFFF
                    0x3C -> {
                        a = a + 1 and 0xff
                        f = f and F_CARRY or incflags[a]
                    }

                    0x3D -> {
                        a = a - 1 and 0xff
                        f = f and F_CARRY or decflags[a]
                    }

                    0x3E -> {
                        localPC++
                        a = b2
                    }

                    0x3F -> f = f and (F_CARRY or F_ZERO) xor F_CARRY
                    0x40 -> {}
                    0x41 -> b = c
                    0x42 -> b = d
                    0x43 -> b = e
                    0x44 -> b = hl shr 8
                    0x45 -> b = hl and 0xFF
                    0x46 -> b = addressRead(hl) and 0xff
                    0x47 -> b = a
                    0x48 -> c = b
                    0x49 -> {}
                    0x4a -> c = d
                    0x4b -> c = e
                    0x4c -> c = hl shr 8
                    0x4d -> c = hl and 0xFF
                    0x4e -> c = addressRead(hl) and 0xff
                    0x4f -> c = a
                    0x50 -> d = b
                    0x51 -> d = c
                    0x52 -> {}
                    0x53 -> d = e
                    0x54 -> d = hl shr 8
                    0x55 -> d = hl and 0xFF
                    0x56 -> d = addressRead(hl) and 0xff
                    0x57 -> d = a
                    0x58 -> e = b
                    0x59 -> e = c
                    0x5a -> e = d
                    0x5b -> {}
                    0x5c -> e = hl shr 8
                    0x5d -> e = hl and 0xFF
                    0x5e -> e = addressRead(hl) and 0xff
                    0x5f -> e = a
                    0x60 -> hl = hl and 0xFF or (b shl 8)
                    0x61 -> hl = hl and 0xFF or (c shl 8)
                    0x62 -> hl = hl and 0xFF or (d shl 8)
                    0x63 -> hl = hl and 0xFF or (e shl 8)
                    0x64 -> {}
                    0x65 -> hl = (hl and 0xFF) * 0x0101
                    0x66 -> hl = hl and 0xFF or (addressRead(hl) and 0xff shl 8)
                    0x67 -> hl = hl and 0xFF or (a shl 8)
                    0x68 -> hl = hl and 0xFF00 or b
                    0x69 -> hl = hl and 0xFF00 or c
                    0x6a -> hl = hl and 0xFF00 or d
                    0x6b -> hl = hl and 0xFF00 or e
                    0x6c -> hl = (hl shr 8) * 0x0101
                    0x6d -> {}
                    0x6e -> hl = hl and 0xFF00 or (addressRead(hl) and 0xff)
                    0x6f -> hl = hl and 0xFF00 or a
                    0x70 -> addressWrite(hl, b)
                    0x71 -> addressWrite(hl, c)
                    0x72 -> addressWrite(hl, d)
                    0x73 -> addressWrite(hl, e)
                    0x74 -> addressWrite(hl, hl shr 8)
                    0x75 -> addressWrite(hl, hl)
                    0x76 -> {
                        interruptsEnabled = true
                        if (interruptsArmed) {
                            nextTimedInterrupt = instrCount
                        } else {
                            while (!interruptsArmed) {
                                instrCount = nextTimedInterrupt
                                initiateInterrupts()
                            }
                            instrCount++
                            nextTimedInterrupt = instrCount
                        }
                    }

                    0x77 -> addressWrite(hl, a)
                    0x78 -> a = b
                    0x79 -> a = c
                    0x7a -> a = d
                    0x7b -> a = e
                    0x7c -> a = hl shr 8
                    0x7d -> a = hl and 0xFF
                    0x7e -> a = addressRead(hl) and 0xff
                    0x7f -> {}
                    0xA7 -> f = if (a == 0) {
                        F_HALFCARRY + F_ZERO
                    } else {
                        F_HALFCARRY
                    }

                    0xAF -> {
                        a = 0
                        f = F_ZERO
                    }

                    0xC0 -> if (f < F_ZERO) {
                        popPC()
                    }

                    0xC1 -> {
                        c = addressRead(sp++) and 0xff
                        b = addressRead(sp++) and 0xff
                    }

                    0xC2 -> if (f < F_ZERO) {
                        setPC((b3 and 0xff shl 8) + b2)
                    } else {
                        localPC += 2
                    }

                    0xC3 -> setPC((b3 and 0xff shl 8) + b2)
                    0xC4 -> {
                        localPC += 2
                        if (f < F_ZERO) {
                            pushPC()
                            setPC((b3 and 0xff shl 8) + b2)
                        }
                    }

                    0xC5 -> {
                        addressWrite(--sp, b)
                        addressWrite(--sp, c)
                    }

                    0xC6 -> {
                        localPC++
                        f = if ((a and 0x0F) + (b2 and 0x0F) >= 0x10) {
                            F_HALFCARRY
                        } else {
                            0
                        }
                        a += b2
                        if (a > 0xff) {
                            f = f or F_CARRY
                            a = a and 0xff
                        }
                        if (a == 0) {
                            f = f or F_ZERO
                        }
                    }

                    0xC7 -> {
                        pushPC()
                        setPC(0x00)
                    }

                    0xC8 -> if (f >= F_ZERO) {
                        popPC()
                    }

                    0xC9 -> popPC()
                    0xCA -> if (f >= F_ZERO) {
                        setPC((b3 and 0xff shl 8) + b2)
                    } else {
                        localPC += 2
                    }

                    0xCB -> {
                        localPC++
                        executeShift(b2)
                    }

                    0xCC -> {
                        localPC += 2
                        if (f >= F_ZERO) {
                            pushPC()
                            setPC((b3 and 0xff shl 8) + b2)
                        }
                    }

                    0xCD -> {
                        localPC += 2
                        pushPC()
                        setPC((b3 and 0xff shl 8) + b2)
                    }

                    0xCE -> {
                        localPC++
                        if (f and F_CARRY != 0) {
                            b2++
                        }
                        f = if ((a and 0x0F) + (b2 and 0x0F) >= 0x10) {
                            F_HALFCARRY
                        } else {
                            0
                        }
                        a += b2
                        if (a > 0xff) {
                            f = f or F_CARRY
                            a = a and 0xff
                        }
                        if (a == 0) {
                            f = f or F_ZERO
                        }
                    }

                    0xCF -> {
                        pushPC()
                        setPC(0x08)
                    }

                    0xD0 -> if (f and F_CARRY == 0) {
                        popPC()
                    }

                    0xD1 -> {
                        e = addressRead(sp++) and 0xff
                        d = addressRead(sp++) and 0xff
                    }

                    0xD2 -> if (f and F_CARRY == 0) {
                        setPC((b3 and 0xff shl 8) + b2)
                    } else {
                        localPC += 2
                    }

                    0xD4 -> {
                        localPC += 2
                        if (f and F_CARRY == 0) {
                            pushPC()
                            setPC((b3 and 0xff shl 8) + b2)
                        }
                    }

                    0xD5 -> {
                        addressWrite(--sp, d)
                        addressWrite(--sp, e)
                    }

                    0xD6 -> {
                        localPC++
                        f = F_SUBTRACT
                        if (a and 0x0F < b2 and 0x0F) {
                            f = f or F_HALFCARRY
                        }
                        a -= b2
                        if (a < 0) {
                            f = f or F_CARRY
                            a = a and 0xff
                        } else if (a == 0) {
                            f = f or F_ZERO
                        }
                    }

                    0xD7 -> {
                        pushPC()
                        setPC(0x10)
                    }

                    0xD8 -> if (f and F_CARRY != 0) {
                        popPC()
                    }

                    0xD9 -> {
                        interruptsEnabled = true
                        if (interruptsArmed) {
                            nextTimedInterrupt = instrCount
                        }
                        popPC()
                    }

                    0xDA -> if (f and F_CARRY != 0) {
                        setPC((b3 and 0xff shl 8) + b2)
                    } else {
                        localPC += 2
                    }

                    0xDC -> {
                        localPC += 2
                        if (f and F_CARRY != 0) {
                            pushPC()
                            setPC((b3 and 0xff shl 8) + b2)
                        }
                    }

                    0xDE -> {
                        localPC++
                        if (f and F_CARRY != 0) {
                            b2++
                        }
                        f = F_SUBTRACT
                        if (a and 0x0F < b2 and 0x0F) {
                            f = f or F_HALFCARRY
                        }
                        a -= b2
                        if (a < 0) {
                            f = f or F_CARRY
                            a = a and 0xff
                        } else if (a == 0) {
                            f = f or F_ZERO
                        }
                    }

                    0xDF -> {
                        pushPC()
                        setPC(0x18)
                    }

                    0xE0 -> {
                        localPC++
                        ioWrite(b2, a)
                    }

                    0xE1 -> {
                        hl = (addressRead(sp + 1) and 0xff shl 8) + (addressRead(sp) and 0xff)
                        sp += 2
                    }

                    0xE2 -> ioWrite(c, a)
                    0xE5 -> {
                        addressWrite(--sp, hl shr 8)
                        addressWrite(--sp, hl)
                    }

                    0xE6 -> {
                        localPC++
                        a = a and b2
                        f = if (a == 0) F_ZERO else 0
                    }

                    0xE7 -> {
                        pushPC()
                        setPC(0x20)
                    }

                    0xE8 -> {
                        localPC++
                        sp += offset
                        f = 0
                        if (sp > 0xffff || sp < 0) {
                            sp = sp and 0xffff
                            f = F_CARRY
                        }
                    }

                    0xE9 -> setPC(hl)
                    0xEA -> {
                        localPC += 2
                        addressWrite((b3 and 0xff shl 8) + b2, a)
                    }

                    0xEE -> {
                        localPC++
                        a = a xor b2
                        f = 0
                        if (a == 0) f = F_ZERO
                    }

                    0xEF -> {
                        pushPC()
                        setPC(0x28)
                    }

                    0xF0 -> {
                        localPC++
                        a = ioRead(b2) and 0xff // fixme, direct access?
                    }

                    0xF1 -> {
                        f = addressRead(sp++) and 0xff // fixme, f0 or ff?
                        a = addressRead(sp++) and 0xff
                    }

                    0xF2 -> a = ioRead(c) and 0xff // fixme, direct access?
                    0xF3 -> interruptsEnabled = false
                    0xF5 -> {
                        addressWrite(--sp, a)
                        addressWrite(--sp, f)
                    }

                    0xF6 -> {
                        localPC++
                        a = a or b2
                        f = 0
                        if (a == 0) {
                            f = F_ZERO
                        }
                    }

                    0xF7 -> {
                        pushPC()
                        setPC(0x30)
                    }

                    0xF8 -> {
                        localPC++
                        hl = sp + offset
                        f = 0
                        if (hl and -0x10000 != 0) {
                            f = F_CARRY
                            hl = hl and 0xFFFF
                        }
                    }

                    0xF9 -> sp = hl
                    0xFA -> {
                        localPC += 2
                        a = addressRead((b3 and 0xff shl 8) + b2) and 0xff
                    }

                    0xFB -> {
                        interruptEnableRequested = true
                        nextTimedInterrupt = instrCount + cyclesPerInstr[b1] + 1 // fixme, this is an ugly hack
                    }

                    0xFE -> {
                        localPC++
                        f = if (a and 0x0F < b2 and 0x0F) F_HALFCARRY or F_SUBTRACT else F_SUBTRACT
                        if (a == b2) {
                            f = f or F_ZERO
                        } else if (a < b2) {
                            f = f or F_CARRY
                        }
                    }

                    0xFF -> {
                        pushPC()
                        setPC(0x38)
                    }

                    else -> if (b1 and 0xC0 == 0x80) { // Byte 0x10?????? indicates ALU op, i.e. 0x80 - 0xbf
                        executeALU(b1)
                    } else {
                        throw RuntimeException(b1.toHexString())
                    }
                }
                instrCount += cyclesPerInstr[b1]
                if (instrCount - nextTimedInterrupt >= 0) {
                    initiateInterrupts()
                    if (interruptsArmed && interruptsEnabled) {
                        checkInterrupts()
                    }
                }
            }
        } catch (ex: Exception) {
            terminate()
            ex.printStackTrace()
            showError(null, "error#20", ex)
        }
    }
    // IOHandler
    /**
     * Initialize IO to initial power on state
     */
    private fun ioHandlerReset() {
        ioWrite(0x0F, 0x01)
        ioWrite(0x26, 0xf1)
        ioWrite(0x40, 0x91)
        ioWrite(0x47, 0xFC)
        ioWrite(0x48, 0xFF)
        ioWrite(0x49, 0xFF)
        registers[0x55] = 0x80.toByte()
        hdmaRunning = false
    }

    /**
     * Read data from IO Ram
     */
    private fun ioRead(num: Int): Int {
        when (num) {
            0x41 -> {
                // LCDSTAT
                var output = registers[0x41].toInt()
                if (registers[0x44] == registers[0x45]) {
                    output = output or 4
                }
                output = if (registers[0x44].toInt() and 0xff >= 144) {
                    output or 1 // mode 1
                } else {
                    output or graphicsChipMode
                }
                return output
            }

            0x04 -> {
                // DIV
                return ((instrCount - divReset - 1) / INSTRS_PER_DIV).toByte().toInt()
            }

            0x05 -> {
                // TIMA
                return if (!timaActive) registers[num].toInt() else (instrCount + instrsPerTima * 0x100 - nextTimaOverflow) / instrsPerTima
            }

            else -> return registers[num].toInt()
        }
    }

    /**
     * Write data to IO Ram
     */
    fun ioWrite(num: Int, data: Int) {
        when (num) {
            0x00 -> {
                var output = 0
                if (data and 0x10 == 0) {
                    // P14
                    output = output or (controller.buttonState and 0x0f)
                }
                if (data and 0x20 == 0) {
                    // P15
                    output = output or (controller.buttonState shr 4)
                }
                // the high nybble is unused for reading (according to gbcpuman), but Japanese Pokemon
                // seems to require it to be set to f
                registers[0x00] = (0xf0 or (output.inv() and 0x0f)).toByte()
            }

            0x02 -> {
                registers[0x02] = data.toByte()
                if (registers[0x02].toInt() and 0x01 == 1) {
                    registers[0x01] =
                        0xFF.toByte() // when no LAN connection, always receive 0xFF from port.  Simulates empty socket.
                    if (registers[0xff].toInt() and INT_SER != 0) {
                        interruptsArmed = true
                        registers[0x0f] = (registers[0x0f].toInt() or INT_SER).toByte()
                        if (interruptsEnabled) nextTimedInterrupt = instrCount
                    }
                    registers[0x02] = (registers[0x02].toInt() and 0x7F).toByte()
                }
            }

            0x04 -> divReset = instrCount
            0x05 -> if (timaActive) nextTimaOverflow = instrCount + instrsPerTima * (0x100 - (data and 0xff))
            0x07 -> {
                if (data and 0x04 != 0) {
                    if (!timaActive) {
                        timaActive = true
                        nextTimaOverflow = instrCount + instrsPerTima * (0x100 - (registers[0x05].toInt() and 0xff))
                    }
                    instrsPerTima = 4 shl 2 * (data - 1 and 3)
                    // 0-3 -> {256, 4, 16, 64}
                } else {
                    if (timaActive) {
                        timaActive = false
                        registers[0x05] =
                            ((instrCount + instrsPerTima * 0x100 - nextTimaOverflow) / instrsPerTima).toByte()
                    }
                }
                registers[num] = data.toByte()
            }

            0x0f -> {
                registers[num] = data.toByte()
                interruptsArmed = registers[0xff].toInt() and registers[0x0f].toInt() != 0
                if (interruptsArmed && interruptsEnabled) nextTimedInterrupt = instrCount
            }

            0x1a -> {
                registers[num] = data.toByte()
                if (data and 0x80 == 0) {
                    registers[0x26] =
                        (registers[0x26].toInt() and 0xfb).toByte() // clear bit 2 of sound status register
                }
            }

            0x40 -> {
                screen.UpdateLCDCFlags(data)
                registers[num] = data.toByte()
            }

            0x41 -> registers[num] = (data and 0xf8).toByte()
            0x46 -> memory[data shr 5]?.let { arraycopy(it, data shl 8 and 0x1f00, oam, 0, 0xa0) }
            0x47 -> {
                screen.decodePalette(0, data)
                if (registers[num] != data.toByte()) {
                    registers[num] = data.toByte()
                    screen.invalidateAll(0)
                }
            }

            0x48 -> {
                screen.decodePalette(4, data)
                if (registers[num] != data.toByte()) {
                    registers[num] = data.toByte()
                    screen.invalidateAll(1)
                }
            }

            0x49 -> {
                screen.decodePalette(8, data)
                if (registers[num] != data.toByte()) {
                    registers[num] = data.toByte()
                    screen.invalidateAll(2)
                }
            }

            0x4A -> {
                if (data and 0xff >= 144) screen.stopWindowFromLine()
                registers[num] = data.toByte()
            }

            0x4B -> {
                if (data and 0xff >= 167) screen.stopWindowFromLine()
                registers[num] = data.toByte()
            }

            0x4D -> if (gbcFeatures) {
                // high bit is read only
                registers[num] = ((data and 0x7f) + (registers[num].toInt() and 0x80)).toByte()
            } else {
                registers[num] = data.toByte()
            }

            0x4F -> {
                if (gbcFeatures) {
                    screen.setVRamBank(data and 0x01)
                }
                registers[num] = data.toByte()
            }

            0x55 -> if (gbcFeatures) {
                if (!hdmaRunning && data and 0x80 == 0) {
                    val dmaSrc = (registers[0x51].toInt() and 0xff shl 8) + (registers[0x52].toInt() and 0xF0)
                    val dmaDst = (registers[0x53].toInt() and 0x1F shl 8) + (registers[0x54].toInt() and 0xF0)
                    val dmaLen = (data and 0x7F) * 16 + 16
                    var r = 0
                    while (r < dmaLen) {
                        screen.addressWrite(dmaDst + r, addressRead(dmaSrc + r).toByte())
                        r++
                    }
                    // fixme, move instrCount?
                    registers[0x55] = 0xff.toByte()
                } else if (data and 0x80 != 0) {
                    // start hdma
                    hdmaRunning = true
                    registers[0x55] = (data and 0x7F).toByte()
                } else {
                    // stop hdma
                    hdmaRunning = false
                    registers[0x55] = (registers[0x55].toInt() or 0x80).toByte()
                }
            } else {
                registers[num] = data.toByte()
            }

            0x68 -> {
                if (gbcFeatures) {
                    registers[0x69] = screen.getGBCPalette(data and 0x3f).toByte()
                }
                registers[num] = data.toByte()
            }

            0x69 -> if (gbcFeatures) {
                screen.setGBCPalette(registers[0x68].toInt() and 0x3f, data and 0xff)
                if (registers[0x68] < 0) { // high bit = autoincrement
                    val next = registers[0x68] + 1 and 0x3f
                    registers[0x68] = (next + 0x80).toByte()
                    registers[0x69] = screen.getGBCPalette(next).toByte()
                } else {
                    registers[num] = data.toByte()
                }
            } else {
                registers[num] = data.toByte()
            }

            0x6A -> {
                if (gbcFeatures) {
                    registers[0x6B] = screen.getGBCPalette((data and 0x3f) + 0x40).toByte()
                }
                registers[0x6A] = data.toByte()
            }

            0x6B -> if (gbcFeatures) {
                screen.setGBCPalette((registers[0x6A].toInt() and 0x3f) + 0x40, data and 0xff)
                if (registers[0x6A] < 0) { // high bit = autoincrement
                    val next = registers[0x6A] + 1 and 0x3f
                    registers[0x6A] = (next + 0x80).toByte()
                    registers[0x6B] = screen.getGBCPalette(next + 0x40).toByte()
                } else {
                    registers[num] = data.toByte()
                }
            } else {
                registers[num] = data.toByte()
            }

            0x70 -> {
                if (gbcFeatures) {
                    gbcRamBank = if (data and 0x07 < 2) {
                        1
                    } else {
                        data and 0x07
                    }
                    if (globalPC >= 0xC000) {
                        // verify cruising if executing in RAM
                        setPC(globalPC + localPC)
                    }
                }
                registers[num] = data.toByte()
            }

            0xff -> {
                registers[num] = data.toByte()
                interruptsArmed = registers[0xff].toInt() and registers[0x0f].toInt() != 0
                if (interruptsArmed && interruptsEnabled) nextTimedInterrupt = instrCount
            }

            else -> registers[num] = data.toByte()
        }
        if (num in 0x10..0x3f) {
            speaker?.ioWrite(num, data)
        }
    }

    /**
     * Maps a ROM bank into the ProcessingChip address space at 0x4000
     */
    private fun mapRom(bankNo: Int) {
        var bankNo = bankNo
        bankNo = bankNo and (cartridge.rom.size shr 1) - 1
        currentRomBank = bankNo
        memory[2] = cartridge.rom[bankNo * 2]
        memory[3] = cartridge.rom[bankNo * 2 + 1]
        if (globalPC and 0xC000 == 0x4000) {
            setPC(localPC + globalPC)
        }
    }

    private fun mapRam(bankNo: Int) {
        currentRamBank = bankNo
        if (currentRamBank < cartridge.ram.size) memory[5] = cartridge.ram[currentRamBank]
    }

    /**
     * Writes to an address in ProcessingChip address space.  Writes to ROM may cause a mapping change.
     */
    private fun cartridgeWrite(addr: Int, data: Int) {
        val halfbank = addr shr 13
        val subaddr = addr and 0x1fff
        when (cartridge.type) {
            0 -> {}
            1, 2, 3 ->                 // MBC1
                if (halfbank == 0) {
                    cartRamEnabled = data and 0x0F == 0x0A
                } else if (halfbank == 1) {
                    var bankNo = data and 0x1F
                    if (bankNo == 0) bankNo = 1
                    mapRom(currentRomBank and 0x60 or bankNo)
                } else if (halfbank == 2) {
                    if (mbc1LargeRamMode) {
                        mapRam(data and 0x03)
                    } else {
                        mapRom(currentRomBank and 0x1F or (data and 0x03 shl 5))
                    }
                } else if (halfbank == 3) {
                    mbc1LargeRamMode = data and 1 == 1
                } else if (halfbank == 5 && memory[halfbank] != null) {
                    // fixme, we should check cartRamEnabled, but that seems
                    // to break Pokemon yellow... (which uses MBC5, but I'm erring
                    // on the side of caution).
                    memory[halfbank]!![subaddr] = data.toByte()
                }

            5, 6 ->                 // MBC2
                if (halfbank == 1) {
                    if (addr and 0x0100 != 0) {
                        var bankNo = data and 0x0F
                        if (bankNo == 0) bankNo = 1
                        mapRom(bankNo)
                    } else {
                        cartRamEnabled = data and 0x0F == 0x0A
                    }
                } else if (halfbank == 5 && memory[halfbank] != null) {
                    // fixme, we should check cartRamEnabled, but that seems
                    // to break Pokemon yellow... (which uses MBC5, but I'm erring
                    // on the side of caution).
                    memory[halfbank]!![subaddr] = data.toByte()
                }

            0x0F, 0x10, 0x11, 0x12, 0x13 ->                 // MBC3
                if (halfbank == 0) {
                    cartRamEnabled = data and 0x0F == 0x0A
                } else if (halfbank == 1) {
                    // Select ROM bank
                    var bankNo = data and 0x7F
                    if (bankNo == 0) bankNo = 1
                    mapRom(bankNo)
                } else if (halfbank == 2) {
                    // Select RAM bank
                    if (cartridge.ram.isNotEmpty()) mapRam(data and 0x0f) // only 0-3 for ram banks, 8+ for RTC
                } else if (halfbank == 3) {
                    // fixme, rtc latch
                } else if (halfbank == 5) {
                    // memory write
                    if (currentRamBank >= 8) {
                        // rtc register
                        cartridge.rtcSync()
                        cartridge.rtcReg[currentRamBank - 8] = data.toByte()
                    } else if (memory[halfbank] != null) {
                        // normal memory
                        // fixme, we should check cartRamEnabled, but that seems
                        // to break Pokemon yellow... (which uses MBC5, but I'm erring
                        // on the side of caution).
                        memory[halfbank]!![subaddr] = data.toByte()
                    }
                }

            0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E ->                 // MBC5
                if (addr shr 12 == 1) {
                    cartRamEnabled = data and 0x0F == 0x0A
                } else if (addr shr 12 == 2) {
                    val bankNo = currentRomBank and 0xFF00 or data
                    // note: bank 0 can be mapped to 0x4000
                    mapRom(bankNo)
                } else if (addr shr 12 == 3) {
                    val bankNo = currentRomBank and 0x00FF or (data and 0x01 shl 8)
                    // note: bank 0 can be mapped to 0x4000
                    mapRom(bankNo)
                } else if (halfbank == 2) {
                    if (cartridge.ram.isNotEmpty()) mapRam(data and 0x0f)
                } else if (halfbank == 5) {
                    if (memory[halfbank] != null) {
                        // fixme, we should check cartRamEnabled, but that seems
                        // to break Pokemon yellow...
                        memory[halfbank]!![subaddr] = data.toByte()
                    }
                }
        }
    }

    fun terminate() {
        isTerminated = true
    }

    // STATUS
    fun sarm(): ByteArray? {
        return if (cartridge.hasBattery) cartridge.dumpSram() else null
    }

    fun setSarm(sarm: ByteArray?) {
        if (sarm != null) {
            cartridge.setSram(sarm)
        }
    }

    fun unflatten(flatState: ByteArray) {
        var offset = 0
        val version = flatState[offset++].toInt()
        val flatGbcFeatures = flatState[offset++].toInt() != 0
        if (version != 1 || flatGbcFeatures != gbcFeatures) throw RuntimeException("Can't unflatten")
        a = flatState[offset++].toInt() and 0xff
        b = flatState[offset++].toInt() and 0xff
        c = flatState[offset++].toInt() and 0xff
        d = flatState[offset++].toInt() and 0xff
        e = flatState[offset++].toInt() and 0xff
        f = flatState[offset++].toInt() and 0xff
        sp = flatState[offset++].toInt() and 0xff
        sp = (sp shl 8) + (flatState[offset++].toInt() and 0xff)
        hl = flatState[offset++].toInt() and 0xff
        hl = (hl shl 8) + (flatState[offset++].toInt() and 0xff)
        var pc = flatState[offset++].toInt() and 0xff
        pc = (pc shl 8) + (flatState[offset++].toInt() and 0xff)
        // setPC() will be called below to set the fields.
        instrCount = getInt(flatState, offset)
        offset += 4
        nextModeTime = getInt(flatState, offset)
        offset += 4
        nextTimaOverflow = getInt(flatState, offset)
        offset += 4
        nextTimedInterrupt = getInt(flatState, offset)
        offset += 4
        timaActive = flatState[offset++].toInt() != 0
        graphicsChipMode = flatState[offset++].toInt()
        interruptsEnabled = flatState[offset++].toInt() != 0
        interruptsArmed = flatState[offset++].toInt() != 0
        interruptEnableRequested = flatState[offset++].toInt() != 0
        arraycopy(flatState, offset, mainRam, 0, mainRam.size)
        offset += mainRam.size
        arraycopy(flatState, offset, oam, 0, 0x00A0)
        offset += 0x00A0
        arraycopy(flatState, offset, registers, 0, 0x0100)
        offset += 0x0100
        divReset = getInt(flatState, offset)
        offset += 4
        instrsPerTima = getInt(flatState, offset)
        offset += 4

        // cartridge
        for (i in cartridge.ram.indices) {
            arraycopy(flatState, offset, cartridge.ram[i], 0, 0x2000)
            offset += 0x2000
        }
        currentRomBank = getInt(flatState, offset)
        offset += 4
        mapRom(currentRomBank)
        currentRamBank = getInt(flatState, offset)
        offset += 4
        if (currentRamBank != 0) mapRam(currentRamBank)
        mbc1LargeRamMode = flatState[offset++].toInt() != 0
        cartRamEnabled = flatState[offset++].toInt() != 0

        // realtime clock
        arraycopy(flatState, offset, cartridge.rtcReg, 0, cartridge.rtcReg.size)
        offset += cartridge.rtcReg.size
        offset = screen.unflatten(flatState, offset)
        if (gbcFeatures) {
            gbcRamBank = flatState[offset++].toInt() and 0xff
            hdmaRunning = flatState[offset++].toInt() != 0
            if (registers[0x4D].toInt() and 0x80 != 0) {
                // double speed
                INSTRS_IN_MODE_0 = BASE_INSTRS_IN_MODE_0
                INSTRS_IN_MODE_2 = BASE_INSTRS_IN_MODE_2
                INSTRS_IN_MODE_3 = BASE_INSTRS_IN_MODE_3
            } else {
                // instrs_in_mode are set correctly already
            }
        }
        setPC(pc)
        if (offset != flatState.size) throw RuntimeException("Loading the game failed" + ": " + offset + ", " + flatState.size)
    }

    fun flatten(): ByteArray {
        var size = (53 + mainRam.size + 0x01A0 + 0x2000 * cartridge.ram.size + cartridge.rtcReg.size
                + 0x2000 + 48)
        if (gbcFeatures) {
            size += 2 + 129 + 0x2000
        }
        val flatState = ByteArray(size)
        var offset = 0
        flatState[offset++] = 1.toByte() // version
        flatState[offset++] = (if (gbcFeatures) 1 else 0).toByte()
        flatState[offset++] = a.toByte()
        flatState[offset++] = b.toByte()
        flatState[offset++] = c.toByte()
        flatState[offset++] = d.toByte()
        flatState[offset++] = e.toByte()
        flatState[offset++] = f.toByte()
        flatState[offset++] = (sp shr 8).toByte()
        flatState[offset++] = sp.toByte()
        flatState[offset++] = (hl shr 8).toByte()
        flatState[offset++] = hl.toByte()
        val pc = localPC + globalPC
        flatState[offset++] = (pc shr 8).toByte()
        flatState[offset++] = pc.toByte()
        setInt(flatState, offset, instrCount)
        offset += 4
        setInt(flatState, offset, nextModeTime)
        offset += 4
        setInt(flatState, offset, nextTimaOverflow)
        offset += 4
        setInt(flatState, offset, nextTimedInterrupt)
        offset += 4
        flatState[offset++] = (if (timaActive) 1 else 0).toByte()
        flatState[offset++] = graphicsChipMode.toByte()
        flatState[offset++] = (if (interruptsEnabled) 1 else 0).toByte()
        flatState[offset++] = (if (interruptsArmed) 1 else 0).toByte()
        flatState[offset++] = (if (interruptEnableRequested) 1 else 0).toByte()
        arraycopy(mainRam, 0, flatState, offset, mainRam.size)
        offset += mainRam.size
        arraycopy(oam, 0, flatState, offset, 0x00A0)
        offset += 0x00A0
        arraycopy(registers, 0, flatState, offset, 0x0100)
        offset += 0x0100
        setInt(flatState, offset, divReset)
        offset += 4
        setInt(flatState, offset, instrsPerTima)
        offset += 4
        for (j in cartridge.ram.indices) {
            arraycopy(cartridge.ram[j], 0, flatState, offset, 0x2000)
            offset += 0x2000
        }
        setInt(flatState, offset, currentRomBank)
        offset += 4
        setInt(flatState, offset, currentRamBank)
        offset += 4
        flatState[offset++] = (if (mbc1LargeRamMode) 1 else 0).toByte()
        flatState[offset++] = (if (cartRamEnabled) 1 else 0).toByte()
        arraycopy(cartridge.rtcReg, 0, flatState, offset, cartridge.rtcReg.size)
        offset += cartridge.rtcReg.size
        offset = screen.flatten(flatState, offset)
        if (gbcFeatures) {
            flatState[offset++] = gbcRamBank.toByte()
            flatState[offset++] = (if (hdmaRunning) 1 else 0).toByte()
        }
        if (offset != flatState.size) throw RuntimeException("error#21: " + offset + ", " + flatState.size)
        return flatState
    }

    // CHEAT
    // HashMap Multithreading put unsafe, but ...
    private val cheatMap: MutableMap<Int?, Cheat> = mutableMapOf<Int?, Cheat>()
    fun setCheat(cheat: Cheat?) {
        if (cheat == null) return
        cheatMap[cheat.address] = cheat
    }

    fun setCheat(code: String?) {
        setCheat(Cheat.newCheat(code))
    }

    fun remoteAllCheat() {
        cheatMap.clear()
    }

    fun addressRead(addr: Int): Int {
        val result = addressRead1(addr)
        val cheat = cheatMap[addr]
        if (cheat != null) {
            if (cheat.ifIs == null || cheat.ifIs!!.toInt() == result) {
                return cheat.changeTo!!.toInt() and 0xFF
            }
        }
        return result
    }

    // OPTIONS
    fun setSoundEnable(channelEnable: Boolean) {
        speaker?.soundEnabled = channelEnable
    }

    fun setChannelEnable(channel: Int, enable: Boolean) {
        speaker?.setChannelEnable(channel, enable)
    }

    fun setSpeed(i: Int) {
        speaker?.setSpeed(i)
        screen.setSpeed(i)
    }

    fun getScreenSpeed() = screen.getSpeed()

    // THREAD
    private var thread: Job? = null

    init {
        cartridge = Cartridge(cartridgeBin!!)
        speaker = Speaker(registers)
        screen = ScreenImplement(
            registers, memory, oam,
            gbcFeatures, palette ?: Palette.GB, screenListener, 0
        )
        mainRam = if (gbcFeatures) {
            ByteArray(0x8000) // 32 kB
        } else {
            ByteArray(0x2000) // 8 kB
        }
        init()
    }

    fun running(): Boolean {
        return thread?.isActive == true
    }

    fun startup() {
        if (running()) {
            throw RuntimeException("Started")
        } else {
            thread = CoroutineScope(Job() + Dispatchers.IO).launch { run() }
            thread?.start()
        }
    }

    suspend fun shutdown() {
        if (running()) {
            speaker?.stop()
            terminate()
            while (thread?.isActive == true) {
                thread?.join()
            }
        } else {
            throw RuntimeException("")
        }
    }

    companion object {
        private val cyclesPerInstr = intArrayOf(
            1, 3, 2, 2, 1, 1, 2, 1, 5, 2, 2, 2, 1, 1, 2, 1,
            1, 3, 2, 2, 1, 1, 2, 1, 3, 2, 2, 2, 1, 1, 2, 1,
            3, 3, 2, 2, 1, 1, 2, 1, 3, 2, 2, 2, 1, 1, 2, 1,
            3, 3, 2, 2, 3, 3, 3, 1, 3, 2, 2, 2, 1, 1, 2, 1,
            1, 1, 1, 1, 1, 1, 2, 1, 1, 1, 1, 1, 1, 1, 2, 1,
            1, 1, 1, 1, 1, 1, 2, 1, 1, 1, 1, 1, 1, 1, 2, 1,
            1, 1, 1, 1, 1, 1, 2, 1, 1, 1, 1, 1, 1, 1, 2, 1,
            2, 2, 2, 2, 2, 2, 1, 2, 1, 1, 1, 1, 1, 1, 2, 1,
            1, 1, 1, 1, 1, 1, 2, 1, 1, 1, 1, 1, 1, 1, 2, 1,
            1, 1, 1, 1, 1, 1, 2, 1, 1, 1, 1, 1, 1, 1, 2, 1,
            1, 1, 1, 1, 1, 1, 2, 1, 1, 1, 1, 1, 1, 1, 2, 1,
            1, 1, 1, 1, 1, 1, 2, 1, 1, 1, 1, 1, 1, 1, 2, 1,
            5, 3, 4, 4, 6, 4, 2, 4, 5, 4, 4, 0, 6, 6, 2, 4,
            5, 3, 4, 0, 6, 4, 2, 4, 5, 4, 4, 0, 6, 0, 2, 4,
            3, 3, 2, 0, 0, 4, 2, 4, 4, 1, 4, 0, 0, 0, 2, 4,
            3, 3, 2, 1, 0, 4, 2, 4, 3, 2, 4, 1, 0, 0, 2, 4
        )
        private val cyclesPerInstrShift = intArrayOf(
            2, 2, 2, 2, 2, 2, 4, 2, 2, 2, 2, 2, 2, 2, 4, 2,
            2, 2, 2, 2, 2, 2, 4, 2, 2, 2, 2, 2, 2, 2, 4, 2,
            2, 2, 2, 2, 2, 2, 4, 2, 2, 2, 2, 2, 2, 2, 4, 2,
            2, 2, 2, 2, 2, 2, 4, 2, 2, 2, 2, 2, 2, 2, 4, 2,
            2, 2, 2, 2, 2, 2, 3, 2, 2, 2, 2, 2, 2, 2, 3, 2,
            2, 2, 2, 2, 2, 2, 3, 2, 2, 2, 2, 2, 2, 2, 3, 2,
            2, 2, 2, 2, 2, 2, 3, 2, 2, 2, 2, 2, 2, 2, 3, 2,
            2, 2, 2, 2, 2, 2, 3, 2, 2, 2, 2, 2, 2, 2, 3, 2,
            2, 2, 2, 2, 2, 2, 4, 2, 2, 2, 2, 2, 2, 2, 4, 2,
            2, 2, 2, 2, 2, 2, 4, 2, 2, 2, 2, 2, 2, 2, 4, 2,
            2, 2, 2, 2, 2, 2, 4, 2, 2, 2, 2, 2, 2, 2, 4, 2,
            2, 2, 2, 2, 2, 2, 4, 2, 2, 2, 2, 2, 2, 2, 4, 2,
            2, 2, 2, 2, 2, 2, 4, 2, 2, 2, 2, 2, 2, 2, 4, 2,
            2, 2, 2, 2, 2, 2, 4, 2, 2, 2, 2, 2, 2, 2, 4, 2,
            2, 2, 2, 2, 2, 2, 4, 2, 2, 2, 2, 2, 2, 2, 4, 2,
            2, 2, 2, 2, 2, 2, 4, 2, 2, 2, 2, 2, 2, 2, 4, 2
        )
    }
}

fun Number.toHexString(): String {
    return this.toHexString(4)
}

fun Number.toHexString(num: Int): String {
    return "0x%0${num}X$this"
}