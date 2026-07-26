package best.nagikokoro.watch6heartrateprobe.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateMeasurementParserTest {
    @Test
    fun parsesUnsignedEightBitMeasurement() {
        assertEquals(88, HeartRateMeasurementParser.parseBpm(byteArrayOf(0x00, 88)))
    }

    @Test
    fun parsesUnsignedSixteenBitMeasurement() {
        assertEquals(260, HeartRateMeasurementParser.parseBpm(byteArrayOf(0x01, 0x04, 0x01)))
    }

    @Test
    fun rejectsTruncatedOrUnsafeMeasurements() {
        assertNull(HeartRateMeasurementParser.parseBpm(byteArrayOf()))
        assertNull(HeartRateMeasurementParser.parseBpm(byteArrayOf(0x01, 72)))
        assertNull(HeartRateMeasurementParser.parseBpm(byteArrayOf(0x00, 0)))
        assertNull(HeartRateMeasurementParser.parseBpm(byteArrayOf(0x01, 0x2d, 0x01)))
    }
}
