package best.nagikokoro.watch6heartrateprobe.mobile

object RelayProtocol {
    const val WATCH_CAPABILITY = "heart_rate_watch_relay"
    const val SAMPLE_PATH = "/hr/sample/v1"
    const val ACK_PATH = "/hr/ack/v1"
    const val CONTROL_PATH = "/hr/control/v1"
    const val DEFAULT_PC_PORT = 9123
    const val SIMULATED_SOURCE = "watch_diagnostic_simulator"
}
