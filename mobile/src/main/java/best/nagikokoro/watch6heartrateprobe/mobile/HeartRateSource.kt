package best.nagikokoro.watch6heartrateprobe.mobile

enum class HeartRateSource(val wireName: String) {
    GALAXY_WATCH("galaxy_watch"),
    XIAOMI_BAND_BLE("xiaomi_band_ble");

    companion object {
        fun fromPreference(value: String?): HeartRateSource =
            entries.firstOrNull { it.wireName == value } ?: GALAXY_WATCH
    }
}

data class XiaomiBandCandidate(
    val address: String,
    val name: String,
    val signalStrength: Int,
)
