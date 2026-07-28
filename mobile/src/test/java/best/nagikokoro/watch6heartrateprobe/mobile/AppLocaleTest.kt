package best.nagikokoro.watch6heartrateprobe.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppLocaleTest {
    @Test
    fun `phone UI and dynamic relay text translate to English`() {
        assertEquals(
            "Heart Rate Relay",
            PhoneTranslations.translate("心率中转站", AppLanguage.ENGLISH),
        )
        val dynamic = PhoneTranslations.translate(
            "配对成功：192.168.1.20:9123",
            AppLanguage.ENGLISH,
        )
        assertEquals("Paired: 192.168.1.20:9123", dynamic)
        assertFalse(dynamic.contains("配对"))
    }

    @Test
    fun `device name and address stay unchanged`() {
        val source = "Xiaomi Smart Band 10 · AA:BB:CC:DD:EE:FF"
        assertEquals(source, PhoneTranslations.translate(source, AppLanguage.JAPANESE))
    }
}
