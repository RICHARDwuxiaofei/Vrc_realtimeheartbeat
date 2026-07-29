package best.nagikokoro.watch6heartrateprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppLocaleTest {
    @Test
    fun `watch UI and dynamic diagnostic text translate to English`() {
        assertEquals(
            "Heart Rate Relay",
            WatchTranslations.translate("心率传输", AppLanguage.ENGLISH),
        )
        val dynamic = WatchTranslations.translate(
            "已连接 Galaxy S24 Ultra",
            AppLanguage.ENGLISH,
        )
        assertEquals("Connected to Galaxy S24 Ultra", dynamic)
        assertFalse(dynamic.contains("手机"))
    }

    @Test
    fun `technical identifiers stay unchanged`() {
        val source = "SM-R960 · HEART_RATE_BPM · 192.0.2.10:40999"
        assertEquals(source, WatchTranslations.translate(source, AppLanguage.JAPANESE))
    }
}
