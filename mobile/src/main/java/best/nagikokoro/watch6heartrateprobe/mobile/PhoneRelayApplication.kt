package best.nagikokoro.watch6heartrateprobe.mobile

import android.app.Application
import android.util.Log
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable

class PhoneRelayApplication : Application(), MessageClient.OnMessageReceivedListener {
    override fun onCreate() {
        super.onCreate()
        PhoneRelayRepository.initialize(this)
        Wearable.getMessageClient(this).addListener(this)
            .addOnSuccessListener { Log.i(TAG, "Runtime Data Layer listener registered") }
            .addOnFailureListener { Log.e(TAG, "Runtime Data Layer listener registration failed", it) }
    }

    override fun onMessageReceived(event: MessageEvent) {
        Log.i(TAG, "Runtime Data Layer message path=${event.path} source=${event.sourceNodeId} bytes=${event.data.size}")
        if (event.path == RelayProtocol.SAMPLE_PATH) {
            PhoneRelayRepository.handleWatchSample(event.sourceNodeId, event.data)
        }
    }

    companion object {
        private const val TAG = "HR_RELAY"
    }
}
