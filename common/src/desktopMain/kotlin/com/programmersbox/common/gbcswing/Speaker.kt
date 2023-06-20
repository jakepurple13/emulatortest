package com.programmersbox.common.gbcswing

import com.programmersbox.common.gbcswing.Common.unsign
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.math.roundToInt
import kotlin.random.Random


/**
 * This is the central controlling class for the sound.
 * It interfaces with the Java Sound API, and handles the
 * calsses for each sound channel.
 */
internal class Speaker(private val registers: ByteArray) {
    private val speed: Speed = Speed()

    /**
     * Current sampling rate that sound is output at
     */
    var sampleRate = 44100
        set(value) {
            field = value
            soundLine!!.flush()
            soundLine!!.close()
            soundLine = initSoundHardware()
            channel1.sampleRate = value
            channel2.sampleRate = value
            channel3.sampleRate = value
            channel4.sampleRate = value
        }

    /**
     * Amount of sound data to buffer before playback
     */
    var bufferLengthMsec = 200

    /**
     * The DataLine for outputting the sound
     */
    var soundLine: SourceDataLine? = initSoundHardware()
    private val channel1: SquareWaveGenerator by lazy { SquareWaveGenerator(sampleRate) }
    private val channel2: SquareWaveGenerator by lazy { SquareWaveGenerator(sampleRate) }
    private val channel3: VoluntaryWaveGenerator by lazy { VoluntaryWaveGenerator(sampleRate) }
    private val channel4: NoiseGenerator by lazy { NoiseGenerator(sampleRate) }
    var soundEnabled = false

    /**
     * If true, channel is enabled
     */
    var channel1Enable = true
    var channel2Enable = true
    var channel3Enable = true
    var channel4Enable = true

    fun setChannelEnable(channel: Int, enable: Boolean) {
        when (channel) {
            1 -> channel1Enable = enable
            2 -> channel2Enable = enable
            3 -> channel3Enable = enable
            4 -> channel4Enable = enable
        }
    }

    fun ioWrite(num: Int, data: Int) {
        when (num) {
            0x10 -> channel1.setSweep(
                unsign(data.toByte()).toInt() and 0x70 shr 4,
                unsign(data.toByte()).toInt() and 0x07,
                unsign(data.toByte()).toInt() and 0x08 == 1
            )

            0x11 -> {
                channel1.dutyCycle = unsign(data.toByte()).toInt() and 0xC0 shr 6
                channel1.setLength(unsign(data.toByte()).toInt() and 0x3F)
            }

            0x12 -> channel1.setEnvelope(
                unsign(data.toByte()).toInt() and 0xF0 shr 4,
                unsign(data.toByte()).toInt() and 0x07,
                unsign(data.toByte()).toInt() and 0x08 == 8
            )

            0x13 -> channel1.setFrequency(
                ((unsign(registers[0x14]).toInt() and 0x07) shl 8) + unsign(
                    registers[0x13]
                )
            )

            0x14 -> {
                if (registers[0x14].toInt() and 0x80 != 0) {
                    channel1.setLength(unsign(registers[0x11]).toInt() and 0x3F)
                    channel1.setEnvelope(
                        unsign(registers[0x12]).toInt() and 0xF0 shr 4,
                        unsign(registers[0x12]).toInt() and 0x07,
                        unsign(registers[0x12]).toInt() and 0x08 == 8
                    )
                }
                if (registers[0x14].toInt() and 0x40 == 0) {
                    channel1.setLength(-1)
                }
                channel1.setFrequency(
                    ((unsign(registers[0x14]).toInt() and 0x07) shl 8) + unsign(
                        registers[0x13]
                    )
                )
            }

            0x16 -> {
                channel2.dutyCycle = unsign(data.toByte()).toInt() and 0xC0 shr 6
                channel2.setLength(unsign(data.toByte()).toInt() and 0x3F)
            }

            0x17 -> channel2.setEnvelope(
                unsign(data.toByte()).toInt() and 0xF0 shr 4,
                unsign(data.toByte()).toInt() and 0x07,
                unsign(data.toByte()).toInt() and 0x08 == 8
            )

            0x18 -> channel2.setFrequency(
                ((unsign(registers[0x19]).toInt() and 0x07) shl 8) + unsign(
                    registers[0x18]
                )
            )

            0x19 -> {
                if (registers[0x19].toInt() and 0x80 != 0) {
                    channel2.setLength(unsign(registers[0x21]).toInt() and 0x3F)
                    channel2.setEnvelope(
                        unsign(registers[0x17]).toInt() and 0xF0 shr 4,
                        unsign(registers[0x17]).toInt() and 0x07,
                        unsign(registers[0x17]).toInt() and 0x08 == 8
                    )
                }
                if (registers[0x19].toInt() and 0x40 == 0) {
                    channel2.setLength(-1)
                }
                channel2.setFrequency(
                    ((unsign(registers[0x19]).toInt() and 0x07) shl 8) + unsign(
                        registers[0x18]
                    )
                )
            }

            0x1A -> if (unsign(data.toByte()).toInt() and 0x80 != 0) {
                channel3.setVolume(unsign(registers[0x1C]).toInt() and 0x60 shr 5)
            } else {
                channel3.setVolume(0)
            }

            0x1B -> channel3.setLength(unsign(data.toByte()).toInt())
            0x1C -> channel3.setVolume(unsign(registers[0x1C]).toInt() and 0x60 shr 5)
            0x1D -> channel3.setFrequency(
                ((unsign(registers[0x1E]).toInt() and 0x07) shl 8) + unsign(
                    registers[0x1D]
                )
            )

            0x1E -> {
                if (registers[0x19].toInt() and 0x80 != 0) {
                    channel3.setLength(unsign(registers[0x1B]).toInt())
                }
                channel3.setFrequency(
                    ((unsign(registers[0x1E]).toInt() and 0x07) shl 8) + unsign(
                        registers[0x1D]
                    )
                )
            }

            0x20 -> channel4.setLength(unsign(data.toByte()).toInt() and 0x3F)
            0x21 -> channel4.setEnvelope(
                unsign(data.toByte()).toInt() and 0xF0 shr 4,
                unsign(data.toByte()).toInt() and 0x07,
                unsign(data.toByte()).toInt() and 0x08 == 8
            )

            0x22 -> channel4.setParameters(
                (unsign(data.toByte()).toInt() and 0x07).toFloat(),
                unsign(data.toByte()).toInt() and 0x08 == 8,
                unsign(data.toByte()).toInt() and 0xF0 shr 4
            )

            0x23 -> {
                if (registers[0x23].toInt() and 0x80 != 0) {
                    channel4.setLength(unsign(registers[0x20]).toInt() and 0x3F)
                }
                if (registers[0x23].toInt() and 0x40 == 0) {
                    channel4.setLength(-1)
                }
            }

            0x25 -> {
                var chanData: Int
                run {
                    chanData = 0
                    if (unsign(data.toByte()).toInt() and 0x01 != 0) {
                        chanData = chanData or CHAN_LEFT
                    }
                    if (unsign(data.toByte()).toInt() and 0x10 != 0) {
                        chanData = chanData or CHAN_RIGHT
                    }
                    this.channel1.channel = chanData
                    chanData = 0
                    if (unsign(data.toByte()).toInt() and 0x02 != 0) {
                        chanData = chanData or CHAN_LEFT
                    }
                    if (unsign(data.toByte()).toInt() and 0x20 != 0) {
                        chanData = chanData or CHAN_RIGHT
                    }
                    this.channel2.channel = chanData
                    chanData = 0
                    if (unsign(data.toByte()).toInt() and 0x04 != 0) {
                        chanData = chanData or CHAN_LEFT
                    }
                    if (unsign(data.toByte()).toInt() and 0x40 != 0) {
                        chanData = chanData or CHAN_RIGHT
                    }
                    this.channel3.channel = chanData
                }
            }
        }
    }

    /**
     * Adds a single frame of sound data to the buffer
     */
    fun outputSound() {
        if (soundEnabled && speed.output()) {
            val numSamples: Int = if (sampleRate / 28 >= soundLine!!.available() * 2) {
                soundLine!!.available() * 2
            } else {
                sampleRate / 28 and 0xFFFE
            }
            val b = ByteArray(numSamples)
            if (channel1Enable) channel1.play(b, numSamples / 2, 0)
            if (channel2Enable) channel2.play(b, numSamples / 2, 0)
            if (channel3Enable) channel3.play(b, numSamples / 2, 0)
            if (channel4Enable) channel4.play(b, numSamples / 2, 0)
            soundLine!!.write(b, 0, numSamples)
        }
    }

    /**
     * Initialize sound hardware if available
     */
    fun initSoundHardware(): SourceDataLine? {
        try {
            val format = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate.toFloat(), 8, 2, 2, sampleRate.toFloat(), true
            )
            val lineInfo = DataLine.Info(SourceDataLine::class.java, format)
            if (!AudioSystem.isLineSupported(lineInfo)) {
                println("Error: Can't find audio output system!")
                soundEnabled = false
            } else {
                val line = AudioSystem.getLine(lineInfo) as SourceDataLine
                val bufferLength = sampleRate / 1000 * bufferLengthMsec
                line.open(format, bufferLength)
                line.start()
                //    System.out.println("Initialized audio successfully.");
                soundEnabled = true
                return line
            }
        } catch (e: Exception) {
            println("Error: Audio system busy!")
            soundEnabled = false
        }
        return null
    }

    /**
     * Change the sound buffer length
     */
    fun setBufferLength(time: Int) {
        bufferLengthMsec = time
        soundLine!!.flush()
        soundLine!!.close()
        soundLine = initSoundHardware()
    }

    /**
     * This class can mix a square wave signal with a sound buffer.
     * It supports all features of the Gameboys sound channels 1 and 2.
     */
    private inner class SquareWaveGenerator {
        /**
         * Length of the sound (in frames)
         */
        var totalLength = 0

        /**
         * Current position in the waveform (in samples)
         */
        var cyclePos: Int

        /**
         * Length of the waveform (in samples)
         */
        var cycleLength: Int

        /**
         * Amplitude of the waveform
         */
        var amplitude: Int

        /**
         * Amount of time the sample stays high in a single waveform (in eighths)
         */
        var dutyCycle: Int
            set(value) {
                when (value) {
                    0 -> field = 1
                    1 -> field = 2
                    2 -> field = 4
                    3 -> field = 6
                }
            }

        /**
         * The channel that the sound is to be played back on
         */
        var channel: Int

        /**
         * Sample rate of the sound buffer
         */
        var sampleRate: Int

        /**
         * Initial amplitude
         */
        var initialEnvelope = 0

        /**
         * Number of envelope steps
         */
        var numStepsEnvelope = 0

        /**
         * If true, envelope will increase amplitude of sound, false indicates decrease
         */
        var increaseEnvelope = false

        /**
         * Current position in the envelope
         */
        var counterEnvelope = 0

        /**
         * Frequency of the sound in internal GB format
         */
        var gbFrequency = 0

        /**
         * Amount of time between sweep steps.
         */
        var timeSweep = 0

        /**
         * Number of sweep steps
         */
        var numSweep = 0

        /**
         * If true, sweep will decrease the sound frequency, otherwise, it will increase
         */
        var decreaseSweep = false

        /**
         * Current position in the sweep
         */
        var counterSweep = 0

        /**
         * Create a square wave generator with the supplied parameters
         */
        internal constructor(waveLength: Int, ampl: Int, duty: Int, chan: Int, rate: Int) {
            cycleLength = waveLength
            amplitude = ampl
            cyclePos = 0
            dutyCycle = duty
            channel = chan
            sampleRate = rate
        }

        /**
         * Create a square wave generator at the specified sample rate
         */
        internal constructor(rate: Int) {
            dutyCycle = 4
            cyclePos = 0
            channel = CHAN_LEFT or CHAN_RIGHT
            cycleLength = 2
            totalLength = 0
            sampleRate = rate
            amplitude = 32
            counterSweep = 0
        }

        /**
         * Set the sound frequency, in internal GB format
         */
        fun setFrequency(gbFrequency: Int) {
            try {
                var frequency = (131072 / 2048).toFloat()
                if (gbFrequency != 2048) {
                    frequency = 131072f / (2048 - gbFrequency).toFloat()
                }
                //  System.out.println("gbFrequency: " + gbFrequency + "");
                this.gbFrequency = gbFrequency
                cycleLength = if (frequency != 0f) {
                    256 * sampleRate / frequency.toInt()
                } else {
                    65535
                }
                if (cycleLength == 0) cycleLength = 1
                //  System.out.println("Cycle length : " + cycleLength + " samples");
            } catch (e: ArithmeticException) {
                // Skip ip
            }
        }

        /**
         * Set the envelope parameters
         */
        fun setEnvelope(initialValue: Int, numSteps: Int, increase: Boolean) {
            initialEnvelope = initialValue
            numStepsEnvelope = numSteps
            increaseEnvelope = increase
            amplitude = initialValue * 2
        }

        /**
         * Set the frequency sweep parameters
         */
        fun setSweep(time: Int, num: Int, decrease: Boolean) {
            timeSweep = (time + 1) / 2
            numSweep = num
            decreaseSweep = decrease
            counterSweep = 0
            //  System.out.println("Sweep: " + time + ", " + num + ", " + decrease);
        }

        fun setLength(gbLength: Int) {
            totalLength = if (gbLength == -1) {
                -1
            } else {
                (64 - gbLength) / 4
            }
        }

        fun setLength3(gbLength: Int) {
            totalLength = if (gbLength == -1) {
                -1
            } else {
                (256 - gbLength) / 4
            }
        }

        fun setVolume3(volume: Int) {
            when (volume) {
                0 -> amplitude = 0
                1 -> amplitude = 32
                2 -> amplitude = 16
                3 -> amplitude = 8
            }
            //  System.out.println("A:"+volume);
        }

        /**
         * Output a frame of sound data into the buffer using the supplied frame length and array offset.
         */
        fun play(b: ByteArray, length: Int, offset: Int) {
            var `val` = 0
            if (totalLength != 0) {
                totalLength--
                if (timeSweep != 0) {
                    counterSweep++
                    if (counterSweep > timeSweep) {
                        if (decreaseSweep) {
                            setFrequency(gbFrequency - (gbFrequency shr numSweep))
                        } else {
                            setFrequency(gbFrequency + (gbFrequency shr numSweep))
                        }
                        counterSweep = 0
                    }
                }
                counterEnvelope++
                if (numStepsEnvelope != 0) {
                    if (counterEnvelope % numStepsEnvelope == 0 && amplitude > 0) {
                        if (!increaseEnvelope) {
                            if (amplitude > 0) amplitude -= 2
                        } else {
                            if (amplitude < 16) amplitude += 2
                        }
                    }
                }
                for (r in offset until offset + length) {
                    if (cycleLength != 0) {
                        `val` = if (8 * cyclePos / cycleLength >= dutyCycle) {
                            amplitude
                        } else {
                            -amplitude
                        }
                    }

                    /*    if (cyclePos >= (cycleLength / 2)) {
                         val = amplitude;
                        } else {
                         val = -amplitude;
                        }*/
                    if (channel and CHAN_LEFT != 0) {
                        val c = b[r * 2]
                        b[r * 2] = (c + `val`.toByte()).toByte()
                    }
                    if (channel and CHAN_RIGHT != 0) {
                        val c = b[r * 2 + 1]
                        b[r * 2 + 1] = (c + `val`.toByte()).toByte()
                    }
                    if (channel and CHAN_MONO != 0) {
                        val c = b[r]
                        b[r] = (c + `val`.toByte()).toByte()
                    }

                    //   System.out.print(val + " ");
                    cyclePos = (cyclePos + 256) % cycleLength
                }
            }
        }

        /*companion object {
            */
        /**
         * Sound is to be played on the left channel of a stereo sound
         *//*
            const val CHAN_LEFT = 1

            */
        /**
         * Sound is to be played on the right channel of a stereo sound
         *//*
            const val CHAN_RIGHT = 2

            */
        /**
         * Sound is to be played back in mono
         *//*
            const val CHAN_MONO = 4
        }*/
    }

    private inner class VoluntaryWaveGenerator {
        var totalLength = 0
        var cyclePos: Int
        var cycleLength: Int
        var amplitude: Int
        var channel: Int
        var sampleRate: Int
        var volumeShift = 0
        var waveform = ByteArray(32)

        internal constructor(waveLength: Int, ampl: Int, duty: Int, chan: Int, rate: Int) {
            cycleLength = waveLength
            amplitude = ampl
            cyclePos = 0
            channel = chan
            sampleRate = rate
        }

        internal constructor(rate: Int) {
            cyclePos = 0
            channel = CHAN_LEFT or CHAN_RIGHT
            cycleLength = 2
            totalLength = 0
            sampleRate = rate
            amplitude = 32
        }

        fun setFrequency(gbFrequency: Int) {
//  cyclePos = 0;
            val frequency = (65536f / (2048 - gbFrequency).toFloat()).toInt().toFloat()
            //  System.out.println("gbFrequency: " + gbFrequency + "");
            cycleLength = ((256f * sampleRate) / frequency).toInt()
            if (cycleLength == 0) cycleLength = 1
            //  System.out.println("Cycle length : " + cycleLength + " samples");
        }

        fun setLength(gbLength: Int) {
            totalLength = if (gbLength == -1) {
                -1
            } else {
                (256 - gbLength) / 4
            }
        }

        fun setSamplePair(address: Int, value: Int) {
            waveform[address * 2] = (value and 0xF0 shr 4).toByte()
            waveform[address * 2 + 1] = (value and 0x0F).toByte()
        }

        fun setVolume(volume: Int) {
            when (volume) {
                0 -> volumeShift = 5
                1 -> volumeShift = 0
                2 -> volumeShift = 1
                3 -> volumeShift = 2
            }
            //  System.out.println("A:"+volume);
        }

        fun play(b: ByteArray, length: Int, offset: Int) {
            var `val`: Int
            if (totalLength != 0) {
                totalLength--
                for (r in offset until offset + length) {
                    val samplePos = 31 * cyclePos / cycleLength
                    `val` = unsign(waveform[samplePos % 32]).toInt() shr volumeShift shl 1
                    //    System.out.print(" " + val);

                    if (channel and CHAN_LEFT != 0) {
                        val c = b[r * 2]
                        b[r * 2] = (c + `val`.toByte()).toByte()
                    }
                    if (channel and CHAN_RIGHT != 0) {
                        val c = b[r * 2 + 1]
                        b[r * 2 + 1] = (c + `val`.toByte()).toByte()
                    }
                    if (channel and CHAN_MONO != 0) {
                        val c = b[r]
                        b[r] = (c + `val`.toByte()).toByte()
                    }

                    //   System.out.print(val + " ");
                    cyclePos = (cyclePos + 256) % cycleLength
                }
            }
        }

    }

    /**
     * This is a white noise generator.  It is used to emulate
     * channel 4.
     */
    private inner class NoiseGenerator {
        /**
         * Indicates the length of the sound in frames
         */
        var totalLength = 0
        var cyclePos: Int

        /**
         * The length of one cycle, in samples
         */
        var cycleLength: Int

        /**
         * Amplitude of the wave function
         */
        var amplitude: Int

        /**
         * Channel being played on.  Combination of CHAN_LEFT and CHAN_RIGHT, or CHAN_MONO
         */
        var channel: Int

        /**
         * Sampling rate of the output channel
         */
        var sampleRate: Int

        /**
         * Initial value of the envelope
         */
        var initialEnvelope = 0
        var numStepsEnvelope = 0

        /**
         * Whether the envelope is an increase/decrease in amplitude
         */
        var increaseEnvelope = false
        var counterEnvelope = 0

        /**
         * Stores the random values emulating the polynomial generator (badly!)
         */
        var randomValues: BooleanArray
        var dividingRatio = 0
        var polynomialSteps = 0
        var shiftClockFreq = 0
        var finalFreq = 0
        var cycleOffset: Int

        /**
         * Creates a white noise generator with the specified wavelength, amplitude, channel, and sample rate
         */
        internal constructor(waveLength: Int, ampl: Int, chan: Int, rate: Int) {
            cycleLength = waveLength
            amplitude = ampl
            cyclePos = 0
            channel = chan
            sampleRate = rate
            cycleOffset = 0
            randomValues = BooleanArray(32767)
            for (r in 0..32766) {
                randomValues[r] = Random.nextBoolean()
            }
            cycleOffset = 0
        }

        /**
         * Creates a white noise generator with the specified sample rate
         */
        internal constructor(rate: Int) {
            cyclePos = 0
            channel = CHAN_LEFT or CHAN_RIGHT
            cycleLength = 2
            totalLength = 0
            sampleRate = rate
            amplitude = 32
            randomValues = BooleanArray(32767)
            for (r in 0..32766) {
                randomValues[r] = Random.nextBoolean()
            }
            cycleOffset = 0
        }

        /**
         * Setup the envelope, and restart it from the beginning
         */
        fun setEnvelope(initialValue: Int, numSteps: Int, increase: Boolean) {
            initialEnvelope = initialValue
            numStepsEnvelope = numSteps
            increaseEnvelope = increase
            amplitude = initialValue * 2
        }

        /**
         * Set the length of the sound
         */
        fun setLength(gbLength: Int) {
            totalLength = if (gbLength == -1) {
                -1
            } else {
                (64 - gbLength) / 4
            }
        }

        fun setParameters(dividingRatio: Float, polynomialSteps: Boolean, shiftClockFreq: Int) {
            var dividingRatio = dividingRatio
            this.dividingRatio = dividingRatio.toInt()
            if (!polynomialSteps) {
                this.polynomialSteps = 32767
                cycleLength = 32767 shl 8
                cycleOffset = 0
            } else {
                this.polynomialSteps = 63
                cycleLength = 63 shl 8
                cycleOffset = (Random.nextFloat() * 1000f).roundToInt()
            }
            this.shiftClockFreq = shiftClockFreq
            if (dividingRatio == 0f) dividingRatio = 0.5f
            finalFreq = (4194304 / 8 / dividingRatio).toInt() shr shiftClockFreq + 1
            //  System.out.println("dr:" + dividingRatio + "  steps: " + this.polynomialSteps + "  shift:" + shiftClockFreq + "  = Freq:" + finalFreq);
        }

        /**
         * Output a single frame of samples, of specified length.  Start at position indicated in the
         * output array.
         */
        fun play(b: ByteArray, length: Int, offset: Int) {
            var `val`: Int
            if (totalLength != 0) {
                totalLength--
                counterEnvelope++
                if (numStepsEnvelope != 0) {
                    if (counterEnvelope % numStepsEnvelope == 0 && amplitude > 0) {
                        if (!increaseEnvelope) {
                            if (amplitude > 0) amplitude -= 2
                        } else {
                            if (amplitude < 16) amplitude += 2
                        }
                    }
                }
                val step = finalFreq / (sampleRate shr 8)
                // System.out.println("Step=" + step);
                for (r in offset until offset + length) {
                    val value = randomValues[cycleOffset + (cyclePos shr 8) and 0x7FFF]
                    val v = if (value) amplitude / 2 else -amplitude / 2
                    if (channel and CHAN_LEFT != 0) {
                        val c = b[r * 2]
                        b[r * 2] = (c + v.toByte()).toByte()
                    }
                    if (channel and CHAN_RIGHT != 0) {
                        val c = b[r * 2 + 1]
                        b[r * 2 + 1] = (c + v.toByte()).toByte()
                    }
                    if (channel and CHAN_MONO != 0) {
                        val c = b[r]
                        b[r] = (c + v.toByte()).toByte()
                    }
                    cyclePos = (cyclePos + step) % cycleLength
                }
            }
        }
    }

    // SPEED
    fun setSpeed(i: Int) {
        speed.setSpeed(i)
    }

    companion object {
        /**
         * Indicates sound is to be played on the left channel of a stereo sound
         */
        const val CHAN_LEFT = 1

        /**
         * Indictaes sound is to be played on the right channel of a stereo sound
         */
        const val CHAN_RIGHT = 2

        /**
         * Indicates that sound is mono
         */
        const val CHAN_MONO = 4

        /*const val CHAN_LEFT = 1
        const val CHAN_RIGHT = 2
        const val CHAN_MONO = 4*/
    }
}