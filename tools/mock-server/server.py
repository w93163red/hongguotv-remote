#!/usr/bin/env python3
"""Mock hongguo-web server for the 红果TV client. Stdlib only, plus ffmpeg for clips.

Serves the same JSON shapes as hongguo-web's server.py with 24 placeholder series.
Covers and the QR code are drawn at startup; each episode clip is encoded with
ffmpeg on its first /stream or /warm hit (so the first play is slow, like the real
server) and cached under .cache/.
"""
import argparse
import base64
import colorsys
import http.cookies
import json
import os
import random
import re
import secrets
import shutil
import struct
import subprocess
import threading
import time
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, ".cache")
FFMPEG = shutil.which("ffmpeg") or "/opt/homebrew/bin/ffmpeg"
CLIP_SECONDS = 20

GENRES = {"short_play": "短剧", "comic_series": "漫剧", "ai_series": "AI短剧"}
THEMES = ["都市", "古装", "玄幻", "甜宠", "悬疑", "逆袭"]
N_SERIES = 24


def series_id(i):
    return "74000000000000000%02d" % i


SERIES = []
for i in range(1, N_SERIES + 1):
    SERIES.append({
        "i": i,
        "series_id": series_id(i),
        "title": "示例短剧 %02d" % i,
        "genre": list(GENRES)[i % 3],
        "theme": THEMES[i % len(THEMES)],
        "episode_cnt": 20 + (i * 17) % 61,
        "status": "完结" if i % 2 else "连载中",
        "play_cnt": 100000 + ((i * 7919) % 97) * 371000,
        "score": "%.1f" % (8 + (i % 20) / 10),
        "cover": "https://p3-novel.byteimg.com/mock/cover_%02d.heic" % i,
        "intro": "示例短剧 %02d 的剧情简介：这是一段用于界面测试的占位文字，主角在第一集就遇到了改变命运的转折。" % i,
    })
BY_ID = {s["series_id"]: s for s in SERIES}


def color(i):
    r, g, b = colorsys.hsv_to_rgb((i * 0.137) % 1, 0.55, 0.62)
    return int(r * 255), int(g * 255), int(b * 255)


def card(s, rank=None):
    out = {"series_id": s["series_id"], "title": s["title"], "episode_cnt": s["episode_cnt"],
           "score": s["score"], "play_cnt": s["play_cnt"], "cover": s["cover"], "intro": s["intro"][:50],
           "hot": "%.1f万热度" % (s["play_cnt"] / 1e5), "copyright": "示例出品", "category": s["theme"]}
    if rank is not None:
        out["rank"] = rank
    return out


def episodes(s):
    return [{"index": n, "vid": "v%02de%03d" % (s["i"], n), "title": "第%d集" % n,
             "duration": CLIP_SECONDS, "cover": "", "comment_count": 0, "digged_count": 0}
            for n in range(1, s["episode_cnt"] + 1)]


# ---------------- tiny raster + PNG writer ----------------
FONT = {  # 5x7 glyphs
    "0": "01110 10001 10011 10101 11001 10001 01110", "1": "00100 01100 00100 00100 00100 00100 01110",
    "2": "01110 10001 00001 00010 00100 01000 11111", "3": "11110 00001 00001 01110 00001 00001 11110",
    "4": "00010 00110 01010 10010 11111 00010 00010", "5": "11111 10000 11110 00001 00001 10001 01110",
    "6": "00110 01000 10000 11110 10001 10001 01110", "7": "11111 00001 00010 00100 01000 01000 01000",
    "8": "01110 10001 10001 01110 10001 10001 01110", "9": "01110 10001 10001 01111 00001 00010 01100",
    "S": "01111 10000 10000 01110 00001 00001 11110", "E": "11111 10000 10000 11110 10000 10000 11111",
    "P": "11110 10001 10001 11110 10000 10000 10000", "T": "11111 00100 00100 00100 00100 00100 00100",
    "s": "00000 00000 01111 10000 01110 00001 11110", ":": "00000 01100 01100 00000 01100 01100 00000",
    " ": "00000 00000 00000 00000 00000 00000 00000",
}


class Canvas:
    def __init__(self, w, h, rgba):
        self.w, self.h = w, h
        self.px = bytearray(bytes(rgba) * (w * h))

    def rect(self, x, y, w, h, rgba):
        x0, y0, x1, y1 = max(0, x), max(0, y), min(self.w, x + w), min(self.h, y + h)
        if x1 <= x0 or y1 <= y0:
            return
        row = bytes(rgba) * (x1 - x0)
        for yy in range(y0, y1):
            o = (yy * self.w + x0) * 4
            self.px[o:o + len(row)] = row

    def text(self, x, y, s, scale, rgba):
        for ch in s:
            rows = FONT[ch].split()
            for ry, bits in enumerate(rows):
                for rx, b in enumerate(bits):
                    if b == "1":
                        self.rect(x + rx * scale, y + ry * scale, scale, scale, rgba)
            x += 6 * scale

    @staticmethod
    def text_width(s, scale):
        return len(s) * 6 * scale - scale

    def png(self):
        raw = b"".join(b"\x00" + bytes(self.px[y * self.w * 4:(y + 1) * self.w * 4]) for y in range(self.h))

        def chunk(t, d):
            return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d))
        return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", self.w, self.h, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b""))


def make_cover(s):
    r, g, b = color(s["i"])
    c = Canvas(360, 480, (r, g, b, 255))
    c.rect(0, 340, 360, 140, (r // 2, g // 2, b // 2, 255))
    num = "%02d" % s["i"]
    c.text((360 - Canvas.text_width(num, 22)) // 2, 110, num, 22, (255, 255, 255, 255))
    c.text((360 - Canvas.text_width("SP" + num, 8)) // 2, 384, "SP" + num, 8, (255, 255, 255, 200))
    return c.png()


def make_qr(seed):
    rnd = random.Random(seed)
    n, scale, quiet = 29, 8, 4
    size = (n + 2 * quiet) * scale
    c = Canvas(size, size, (255, 255, 255, 255))
    finder = {(x, y) for ox, oy in ((0, 0), (n - 7, 0), (0, n - 7)) for x in range(ox, ox + 7) for y in range(oy, oy + 7)}
    for y in range(n):
        for x in range(n):
            if (x, y) in finder:
                fx, fy = x % (n - 7) if x >= n - 7 else x, y % (n - 7) if y >= n - 7 else y
                on = fx in (0, 6) or fy in (0, 6) or (2 <= fx <= 4 and 2 <= fy <= 4)
            else:
                on = rnd.random() < 0.5
            if on:
                c.rect((x + quiet) * scale, (y + quiet) * scale, scale, scale, (0, 0, 0, 255))
    return c.png()


def label_png(s, ep):
    c = Canvas(1000, 220, (0, 0, 0, 0))
    t = "S%02d EP%02d" % (s["i"], ep)
    c.text((1000 - Canvas.text_width(t, 20)) // 2, 40, t, 20, (255, 255, 255, 255))
    return c.png()


def timer_strip():
    """Seconds 00s..NNs stacked vertically; ffmpeg crops one cell per second."""
    cell_w, cell_h = 360, 120
    c = Canvas(cell_w, cell_h * (CLIP_SECONDS + 1), (0, 0, 0, 0))
    for k in range(CLIP_SECONDS + 1):
        t = "%02ds" % k
        c.text((cell_w - Canvas.text_width(t, 14)) // 2, k * cell_h + 11, t, 14, (255, 255, 255, 255))
    return c.png()


# ---------------- fixtures ----------------
_clip_locks = {}
_clip_guard = threading.Lock()


def write(path, data):
    with open(path, "wb") as f:
        f.write(data)


def prepare_fixtures():
    os.makedirs(os.path.join(CACHE, "clips"), exist_ok=True)
    for s in SERIES:
        p = os.path.join(CACHE, "cover_%02d.png" % s["i"])
        if not os.path.exists(p):
            write(p, make_cover(s))
    p = os.path.join(CACHE, "timer.png")
    if not os.path.exists(p):
        write(p, timer_strip())


def clip_path(vid):
    m = re.fullmatch(r"v(\d{2})e(\d{3})", vid or "")
    if not m:
        return None
    i, ep = int(m.group(1)), int(m.group(2))
    s = next((x for x in SERIES if x["i"] == i), None)
    if not s or not 1 <= ep <= s["episode_cnt"]:
        return None
    out = os.path.join(CACHE, "clips", vid + ".mp4")
    with _clip_guard:
        lock = _clip_locks.setdefault(vid, threading.Lock())
    with lock:
        if os.path.exists(out):
            return out
        lab = os.path.join(CACHE, "clips", vid + ".label.png")
        write(lab, label_png(s, ep))
        r, g, b = color(i)
        fc = ("[1:v]format=rgba[lab];[2:v]format=rgba,crop=360:120:0:'floor(t)*120'[tm];"
              "[0:v][lab]overlay=40:700[a];[a][tm]overlay=360:980[b];"
              "[b][3:v]overlay=x='-w+w*t/%d':y=1880:eval=frame,format=yuv420p" % CLIP_SECONDS)
        tmp = out + ".tmp.mp4"
        subprocess.run([FFMPEG, "-v", "error", "-y",
                        "-f", "lavfi", "-i", "color=c=0x%02x%02x%02x:s=1080x1920:r=25:d=%d" % (r, g, b, CLIP_SECONDS),
                        "-loop", "1", "-i", lab, "-loop", "1", "-i", os.path.join(CACHE, "timer.png"),
                        "-f", "lavfi", "-i", "color=c=white:s=1080x40:r=25:d=%d" % CLIP_SECONDS,
                        "-filter_complex", fc, "-t", str(CLIP_SECONDS),
                        "-c:v", "libx264", "-preset", "veryfast", "-crf", "30", "-g", "25",
                        "-profile:v", "high", "-level", "4.0", "-movflags", "+faststart", tmp], check=True)
        os.replace(tmp, out)
        os.remove(lab)
        return out


# ---------------- per-visitor state (keyed by hg_sid like the real server) ----------------
STATE_LOCK = threading.Lock()
search_hist = {}            # sid -> [q]
hot_counts = {"示例短剧 01": 5, "甜宠": 4, "逆袭": 3, "悬疑": 2}
qr_tokens = {}              # token -> {"sid", "polls"}
accounts = {}               # sid -> {"user", "saved_at"}
cloud_hist = {}             # sid -> {series_id: {ep, pos_ms, ts}}


def seed_cloud(sid):
    now = int(time.time() * 1000)
    cloud_hist[sid] = {series_id(5): {"ep": 7, "pos_ms": 8000, "ts": now - 3600_000},
                       series_id(9): {"ep": 3, "pos_ms": 5000, "ts": now - 7200_000}}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        print("[mock] %s %s" % (self.command, self.path), flush=True)

    # ---- helpers ----
    def sid(self):
        c = http.cookies.SimpleCookie(self.headers.get("Cookie", ""))
        v = c["hg_sid"].value if "hg_sid" in c else ""
        return v if re.fullmatch(r"[0-9a-f]{32}", v) else None

    def send_json(self, obj, status=200, cookie=None):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        if cookie:
            self.send_header("Set-Cookie", "hg_sid=%s; Max-Age=31536000; Path=/; SameSite=lax" % cookie)
        self.end_headers()
        self.wfile.write(body)

    def send_bytes(self, data, ctype):
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "max-age=86400")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)

    def body_json(self):
        n = int(self.headers.get("Content-Length") or 0)
        try:
            return json.loads(self.rfile.read(n) or b"{}")
        except ValueError:
            return {}

    def send_file_range(self, path):
        size = os.path.getsize(path)
        start, end, status = 0, size - 1, 200
        m = re.match(r"bytes=(\d*)-(\d*)", self.headers.get("Range", ""))
        if m and (m.group(1) or m.group(2)):
            if m.group(1):
                start = int(m.group(1))
                end = int(m.group(2)) if m.group(2) else size - 1
            else:
                start = max(0, size - int(m.group(2)))
            end = min(end, size - 1)
            if start > end:
                self.send_response(416)
                self.send_header("Content-Range", "bytes */%d" % size)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            status = 206
        self.send_response(status)
        self.send_header("Content-Type", "video/mp4")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        if status == 206:
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
        self.end_headers()
        if self.command == "HEAD":
            return
        with open(path, "rb") as f:
            f.seek(start)
            left = end - start + 1
            try:
                while left > 0:
                    chunk = f.read(min(262144, left))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    left -= len(chunk)
            except (BrokenPipeError, ConnectionResetError):
                pass

    # ---- routing ----
    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        p = u.path
        if p == "/rank":
            board = q.get("board", "recommend")
            if board == "hot":
                order = sorted(SERIES, key=lambda s: -s["play_cnt"])
            elif board == "new":
                order = list(reversed(SERIES))
            elif board == "recommend":
                order = sorted(SERIES, key=lambda s: (s["i"] * 7) % N_SERIES)
            else:
                return self.send_json({"detail": "board必须是 ['recommend', 'hot', 'new']"}, 400)
            lim = int(q.get("limit", 30))
            names = {"recommend": "漫剧推荐榜", "hot": "漫剧热播榜", "new": "漫剧新剧榜"}
            return self.send_json({"board": board, "name": names[board],
                                   "items": [card(s, k + 1) for k, s in enumerate(order[:lim])]})
        if p == "/latest":
            genre = q.get("genre", "short_play")
            items = [card(s) for s in reversed(SERIES) if s["genre"] == genre]
            return self.send_json({"genre": genre, "name": GENRES.get(genre), "mode": "最新上架",
                                   "only_today": q.get("only_today") == "true", "count": len(items), "items": items})
        if p == "/filters":
            genre = q.get("genre", "short_play")
            return self.send_json({"genre": genre, "name": GENRES.get(genre), "rows": [
                {"type": "genre", "row_name": "体裁", "selection_type": 0,
                 "items": [{"id": k, "name": v} for k, v in GENRES.items()]},
                {"type": "category_dim_theme", "row_name": "主题", "selection_type": 1,
                 "items": [{"id": "cate_%d" % k, "name": t} for k, t in enumerate(THEMES)]},
                {"type": "sort", "row_name": "排序", "selection_type": 0,
                 "items": [{"id": "hot_score", "name": "最高热度"}, {"id": "online_time", "name": "最新上架"}]}]})
        if p == "/browse":
            genre = q.get("genre", "ai_series")
            themes = [t for t in q.get("theme", "").split(",") if t]
            rows = [s for s in SERIES if s["genre"] == genre and (not themes or s["theme"] in themes)]
            if q.get("sort") == "hot_score":
                rows.sort(key=lambda s: -s["play_cnt"])
            items = []
            for s in rows:
                c = card(s)
                c.update({"vid": "v%02de001" % s["i"], "stream_url": "/stream?vid=v%02de001" % s["i"],
                          "episodes_url": "/episodes?series_id=" + s["series_id"]})
                items.append(c)
            return self.send_json({"genre": genre, "name": GENRES.get(genre), "count": len(items), "items": items})
        if p == "/search/page":
            kw = q.get("q", "").strip().lower()
            if not kw:
                return self.send_json({"detail": "q 不能为空"}, 422)
            hits = [s for s in SERIES if kw in s["title"].lower() or kw in s["theme"] or kw in "%02d" % s["i"]]
            items = [{"series_id": s["series_id"], "title": s["title"], "score": "%.1f万热度" % (s["play_cnt"] / 1e5),
                      "episode_cnt": s["episode_cnt"], "play_cnt": 0, "cover": s["cover"], "intro": s["intro"],
                      "categories": [s["theme"]]} for s in hits]
            return self.send_json({"query": q.get("q"), "items": items, "next_cursor": None})
        if p == "/search/history":
            sid = self.sid()
            with STATE_LOCK:
                return self.send_json({"history": search_hist.get(sid, [])[:10] if sid else []})
        if p == "/search/hot":
            with STATE_LOCK:
                return self.send_json({"hot": [k for k, _ in sorted(hot_counts.items(), key=lambda kv: -kv[1])[:10]]})
        if p == "/episodes":
            s = BY_ID.get(q.get("series_id", ""))
            if not s:
                return self.send_json({"detail": "episodes失败: not found"}, 500)
            meta = {"series_id": s["series_id"], "title": s["title"], "intro": s["intro"],
                    "episode_cnt": s["episode_cnt"], "status": s["status"], "play_cnt": s["play_cnt"],
                    "followed_cnt": 0, "create_time": 0, "cover": s["cover"], "category": [s["theme"]],
                    "celebrities": []}
            return self.send_json({"meta": meta, "episodes": episodes(s)})
        if p == "/warm":
            s = BY_ID.get(q.get("series_id", ""))
            ep = q.get("ep", "1")
            if s and ep.isdigit():
                threading.Thread(target=clip_path, args=("v%02de%03d" % (s["i"], int(ep)),), daemon=True).start()
            return self.send_json({"ok": True})
        if p == "/stream":
            vid = q.get("vid")
            if not vid and q.get("series_id") in BY_ID:
                vid = "v%02de%03d" % (BY_ID[q["series_id"]]["i"], int(q.get("ep", "1")))
            path = clip_path(vid)
            if not path:
                return self.send_json({"detail": "无直链/video_model"}, 404)
            return self.send_file_range(path)
        if p == "/img":
            m = re.search(r"cover_(\d{2})", q.get("url", ""))
            f = os.path.join(CACHE, "cover_%s.png" % m.group(1)) if m else ""
            if not os.path.exists(f):
                return self.send_json({"detail": "图片读取失败"}, 404)
            with open(f, "rb") as fh:
                return self.send_bytes(fh.read(), "image/png")
        if p == "/auth/qrcode":
            sid = self.sid()
            new_sid = sid or secrets.token_hex(16)
            token = secrets.token_hex(12)
            with STATE_LOCK:
                qr_tokens[token] = {"sid": new_sid, "polls": 0}
            data = {"token": token, "qrcode": base64.b64encode(make_qr(token)).decode(), "expire_time": int(time.time()) + 120}
            return self.send_json(data, cookie=None if sid else new_sid)
        if p == "/auth/status":
            with STATE_LOCK:
                t = qr_tokens.get(q.get("token", ""))
                if not t:
                    return self.send_json({"status": "expired"})
                t["polls"] += 1
                if t["polls"] <= 2:
                    return self.send_json({"status": "new"})
                if t["polls"] == 3:
                    return self.send_json({"status": "scanned"})
                user = {"name": "示例用户", "avatar": ""}
                accounts[t["sid"]] = {"user": user, "saved_at": int(time.time())}
                seed_cloud(t["sid"])
                qr_tokens.pop(q["token"], None)
                return self.send_json({"status": "confirmed", "user": user})
        if p == "/auth/me":
            with STATE_LOCK:
                a = accounts.get(self.sid())
            if not a:
                return self.send_json({"logged_in": False})
            return self.send_json({"logged_in": True, "user": a["user"], "saved_at": a["saved_at"]})
        if p == "/auth/history":
            sid = self.sid()
            with STATE_LOCK:
                if sid not in accounts:
                    return self.send_json({"logged_in": False, "items": []})
                rows = sorted(cloud_hist.get(sid, {}).items(), key=lambda kv: -kv[1]["ts"])
            items = [{"sid": k, "ep": v["ep"], "pos_ms": v["pos_ms"], "ts": v["ts"],
                      "title": BY_ID[k]["title"], "cover": BY_ID[k]["cover"]} for k, v in rows if k in BY_ID]
            return self.send_json({"logged_in": True, "total": len(items), "items": items})
        if p in ("/", "/ui"):
            return self.send_json({"mock": "hongguo-web", "series": N_SERIES})
        return self.send_json({"detail": "Not Found"}, 404)

    def do_POST(self):
        p = urlparse(self.path).path
        body = self.body_json()
        sid = self.sid()
        if p == "/search/history":
            q = str(body.get("q") or "").strip()[:40]
            if not q:
                return self.send_json({"ok": False})
            with STATE_LOCK:
                hot_counts[q] = hot_counts.get(q, 0) + 1
                if sid:
                    search_hist[sid] = [q] + [x for x in search_hist.get(sid, []) if x != q][:9]
            return self.send_json({"ok": True})
        if p == "/auth/logout":
            with STATE_LOCK:
                accounts.pop(sid, None)
            return self.send_json({"ok": True})
        if p == "/auth/sync_history":
            with STATE_LOCK:
                if sid not in accounts:
                    return self.send_json({"ok": False, "msg": "未登录"})
                cloud_hist.setdefault(sid, {})[str(body.get("series_id"))] = {
                    "ep": int(body.get("ep") or 0), "pos_ms": int(body.get("position_ms") or 0),
                    "ts": int(time.time() * 1000)}
            return self.send_json({"ok": True})
        return self.send_json({"detail": "Not Found"}, 404)

    def do_DELETE(self):
        if urlparse(self.path).path == "/search/history":
            sid = self.sid()
            with STATE_LOCK:
                if sid:
                    search_hist[sid] = []
            return self.send_json({"ok": True})
        return self.send_json({"detail": "Not Found"}, 404)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8077)
    a = ap.parse_args()
    prepare_fixtures()
    print("[mock] serving %d series on http://%s:%d (fixtures in %s)" % (N_SERIES, a.host, a.port, CACHE), flush=True)
    ThreadingHTTPServer((a.host, a.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
