package com.programmersbox.common.gbcswing


open class Controller {
    /**
     * Current state of the buttons, bit set = pressed.
     */
    var buttonState = 0
    var p10Requested = false

    // RIGHT LEFT UP DOWN A B SELECT START (0-7)
    fun buttonDown(buttonIndex: Int) {
        buttonState = buttonState or (1 shl buttonIndex)
        p10Requested = true
    }

    fun buttonUp(buttonIndex: Int) {
        buttonState = buttonState and 0xff - (1 shl buttonIndex)
        p10Requested = true
    }

    fun buttonDown(button: GameBoyButton) {
        buttonState = buttonState or (1 shl button.ordinal)
        p10Requested = true
    }

    fun buttonUp(button: GameBoyButton) {
        buttonState = buttonState and 0xff - (1 shl button.ordinal)
        p10Requested = true
    }
}

enum class GameBoyButton {
    Right,
    Left,
    Up,
    Down,
    A,
    B,
    Select,
    Start
}