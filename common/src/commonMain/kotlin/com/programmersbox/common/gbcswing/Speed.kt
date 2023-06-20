package com.programmersbox.common.gbcswing


class Speed(private var speed: Int = 1) {
    private var speedCounter = 0
    fun output(): Boolean {
        speedCounter = (speedCounter + 1) % speed
        return speedCounter == 0
    }

    fun setSpeed(i: Int) {
        if (i > 0) {
            speed = i
        } else {
            throw RuntimeException("Can't <= 0")
        }
    }

    fun getSpeed() = speed
}