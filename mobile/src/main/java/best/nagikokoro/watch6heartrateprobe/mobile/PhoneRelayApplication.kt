package best.nagikokoro.watch6heartrateprobe.mobile

import android.app.Application

class PhoneRelayApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PhoneRelayRepository.initialize(this)
    }
}
