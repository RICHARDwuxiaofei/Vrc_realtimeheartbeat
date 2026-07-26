import sys

from vrc_heartbeat.__main__ import ble_scan_self_test, main, self_test


if __name__ == "__main__":
    if "--ble-scan-self-test" in sys.argv:
        ble_scan_self_test()
    elif "--self-test" in sys.argv:
        self_test()
    else:
        main()
