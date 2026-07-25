package best.nagikokoro.watch6heartrateprobe.mobile

/**
 * Parses Bluetooth SIG Heart Rate Measurement (0x2A37).
 *
 * Bit 0 of the flags byte selects an unsigned 8-bit or unsigned 16-bit
 * heart-rate value. Other optional fields are intentionally ignored.
 */
object HeartRateMeasurementParser {
    fun parseBpm(value: ByteArray): Int? {
        if (value.size < 2) return null
        val isUInt16 = value[0].toInt() and 0x01 != 0
        val bpm = if (isUInt16) {
            if (value.size < 3) return null
            (value[1].toInt() and 0xff) or ((value[2].toInt() and 0xff) shl 8)
        } else {
            value[1].toInt() and 0xff
        }
        return bpm.takeIf { it in 1..300 }
    }
}
