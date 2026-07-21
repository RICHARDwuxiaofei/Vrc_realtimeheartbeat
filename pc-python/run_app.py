import sys

from vrc_heartbeat.__main__ import main, self_test


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        self_test()
    else:
        main()
