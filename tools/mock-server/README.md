# Mock hongguo-web server

Stand-in for a hongguo-web server so the 红果TV client (`:tv`) can be run without upstream APIs.
Same endpoints and JSON shapes, 24 placeholder series (示例短剧 01-24) with 20-80 episodes each.
Covers and the QR code are drawn at startup; each episode clip (20 s, 1080x1920 H.264, series/episode and
seconds burned in) is encoded with ffmpeg on its first `/stream` or `/warm` hit and cached in `.cache/`.
QR login answers `new`, `new`, `scanned`, then `confirmed`.

Run (Python 3.9+, ffmpeg on PATH or at /opt/homebrew/bin/ffmpeg):

    python3 tools/mock-server/server.py --port 8077

Point the app at it from the emulator (the host is 10.0.2.2):

    adb shell am start -n dev.tomato.hongguo.tv/.MainActivity --es server http://10.0.2.2:8077
