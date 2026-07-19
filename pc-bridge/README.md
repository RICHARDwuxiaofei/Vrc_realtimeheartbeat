# Heart Rate Bridge

1. Double-click `Start Heart Rate Bridge.cmd`.
2. Leave the UDP listen port at `9123` and start the receiver.
3. Enter the displayed PC IPv4 address and port `9123` in the Android phone app.
4. Use **Send test packet** on the phone. Both apps should show a successful acknowledgement.
5. In VRChat, enable OSC in the Action Menu. The bridge sends to local UDP port `9000`.

Avatar inputs:

- `/avatar/parameters/HeartRate` — Int BPM
- `/avatar/parameters/HeartRateNormalized` — Float, maps 40–200 BPM to 0–1
- `/avatar/parameters/HeartRateValid` — Bool, becomes false after 10 seconds without data

The phone sends JSON UTF-8 UDP packets. The bridge replies to the packet's source address, so the PC does not need the phone IP configured.
