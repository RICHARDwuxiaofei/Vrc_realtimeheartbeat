from vrc_heartbeat.engine import BridgeEngine
from vrc_heartbeat.protocol import HeartRatePacket


def make_packet(packet_type="heart_rate", bpm=84, interval=5):
    return HeartRatePacket(packet_type, 1, 1_000, bpm, interval, {})


def test_real_heart_rate_sends_new_and_legacy_parameters():
    sent = []
    engine = BridgeEngine(lambda address, value: sent.append((address, value)))
    engine.start()
    sent.clear()
    result = engine.accept(make_packet(bpm=84), 2_000)
    assert result.kind == "heart_rate"
    assert ("/avatar/parameters/HeartRate", 84) in sent
    assert ("/avatar/parameters/HR_Tens", 8) in sent
    assert ("/avatar/parameters/HR_Ones", 4) in sent
    assert ("/avatar/parameters/HRValid", True) in sent


def test_over_99_keeps_full_legacy_bpm_but_clamps_two_digits():
    sent = []
    engine = BridgeEngine(lambda address, value: sent.append((address, value)))
    engine.accept(make_packet(bpm=136), 2_000)
    assert ("/avatar/parameters/HeartRate", 136) in sent
    assert ("/avatar/parameters/HR_Tens", 9) in sent
    assert ("/avatar/parameters/HR_Ones", 9) in sent


def test_diagnostic_packet_does_not_enter_avatar_parameters():
    sent = []
    engine = BridgeEngine(lambda address, value: sent.append((address, value)))
    result = engine.accept(make_packet(packet_type="relay_test", bpm=72), 2_000)
    assert result.kind == "diagnostic"
    assert sent == []


def test_stale_signal_sends_invalid_once():
    sent = []
    engine = BridgeEngine(lambda address, value: sent.append((address, value)))
    engine.accept(make_packet(interval=5), 1_000)
    sent.clear()
    assert engine.tick(13_499) is None
    result = engine.tick(13_500)
    assert result and result.kind == "stale"
    assert ("/avatar/parameters/HRValid", False) in sent
    count = len(sent)
    assert engine.tick(20_000) is None
    assert len(sent) == count


def test_pulse_width_and_period_follow_bpm():
    sent = []
    engine = BridgeEngine(lambda address, value: sent.append((address, value)))
    engine.accept(make_packet(bpm=60), 1_000)
    sent.clear()
    engine.tick(1_000)
    assert sent[-1] == ("/avatar/parameters/HRPulse", True)
    engine.tick(1_119)
    assert sent[-1] == ("/avatar/parameters/HRPulse", True)
    engine.tick(1_120)
    assert sent[-1] == ("/avatar/parameters/HRPulse", False)
    engine.tick(2_000)
    assert sent[-1] == ("/avatar/parameters/HRPulse", True)
