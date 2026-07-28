package best.nagikokoro.watch6heartrateprobe.mobile

import com.journeyapps.barcodescanner.CaptureActivity

/** Keeps the pairing scanner upright instead of inheriting ZXing's landscape activity. */
class PortraitCaptureActivity : CaptureActivity()
