package com.programmersbox.common.gbcswing


object Common {
    fun unsign(b: Byte): Short {
        return if (b < 0) {
            (256 + b).toShort()
        } else {
            b.toShort()
        }
    }

    fun setInt(b: ByteArray, i: Int, v: Int) {
        var i = i
        b[i++] = (v shr 24).toByte()
        b[i++] = (v shr 16).toByte()
        b[i++] = (v shr 8).toByte()
        b[i++] = v.toByte()
    }

    fun getInt(b: ByteArray, i: Int): Int {
        var i = i
        var r = b[i++].toInt() and 0xFF
        r = (r shl 8) + (b[i++].toInt() and 0xFF)
        r = (r shl 8) + (b[i++].toInt() and 0xFF)
        return (r shl 8) + (b[i++].toInt() and 0xFF)
    }

    fun showError(message: String?, number: String, ex: Exception?) {
        Exception(message, ex).printStackTrace()
    }
}