package best.nagikokoro.watch6heartrateprobe.mobile

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import android.util.Log

class PhoneRelayListenerService : WearableListenerService() {
    override fun onCreate() {
        super.onCreate()
        PhoneRelayRepository.initialize(this)
        Log.i(TAG, "PhoneRelayListenerService created")
    }

    override fun onMessageReceived(event: MessageEvent) {
        Log.i(TAG, "Data Layer message path=${event.path} source=${event.sourceNodeId} bytes=${event.data.size}")
        if (event.path == RelayProtocol.SAMPLE_PATH) {
            PhoneRelayRepository.handleWatchSample(event.sourceNodeId, event.data)
        }
    }

    companion object {
        private const val TAG = "HR_RELAY"
    }
}
