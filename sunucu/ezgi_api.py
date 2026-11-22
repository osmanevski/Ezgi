#!/usr/bin/env python3
"""Ezgi sunucusu: dinleme ve indirme olaylarını toplar (yıl sonu özeti, akıllı mix, cihazlar arası eşitleme).

Yalnız Python standart kütüphanesi + SQLite. Caddy: osmanevski.com/ezgi/* → 127.0.0.1:8796 (handle_path).
Kimlik: her telefon `Authorization: Bearer <kurulum>.<anahtar>` gönderir; kurulum ilk görüldüğünde
anahtarın SHA-256'sı kaydedilir (ilk kullanımda güven), sonra eşleşmeyen istek 403.
IP adresi saklanmaz. Uygulama yalnız kullanıcı "paylaş" dediyse gönderir.

  POST /v1/olaylar      {"surum": "1.6", "olaylar": [ {...}, ... ]}  → {"alinan": n}
  GET  /v1/olaylar?sonra=SEQ  bu kurulumun (ileride hesabın) olayları, eşitleme için
  GET  /v1/saglik
"""
import hashlib, hmac, json, os, re, sqlite3, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

DB = os.environ.get("EZGI_DB", "/var/lib/ezgi/ezgi.db")
PORT = int(os.environ.get("EZGI_PORT", "8796"))
MAX_BODY = 512 * 1024
MAX_EVENTS = 500
TURLER = {"dinleme", "indirme"}
ID_RE = re.compile(r"^[A-Za-z0-9-]{8,64}$")

SCHEMA = """
CREATE TABLE IF NOT EXISTS kurulumlar (
  id TEXT PRIMARY KEY,
  anahtar_hash TEXT NOT NULL,
  hesap TEXT,                 -- ileride: aynı kişinin cihazları
  ilk INTEGER NOT NULL,
  son INTEGER NOT NULL,
  surum TEXT
);
CREATE TABLE IF NOT EXISTS olaylar (
  seq INTEGER PRIMARY KEY AUTOINCREMENT,
  id TEXT NOT NULL UNIQUE,    -- telefonun ürettiği; tekrar gönderim yinelenmez
  kurulum TEXT NOT NULL,
  tur TEXT NOT NULL,
  anahtar TEXT,               -- telefondaki şarkı kimliği ya da yt:<video>
  video TEXT,                 -- YouTube video kimliği, biliniyorsa
  baslik TEXT, sanatci TEXT, album TEXT,
  sure_ms INTEGER, dinlenen_ms INTEGER,
  bitti INTEGER, atlandi INTEGER,
  zaman INTEGER NOT NULL,     -- telefondaki an, ms
  alindi INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS olaylar_kurulum ON olaylar(kurulum, seq);
CREATE INDEX IF NOT EXISTS olaylar_zaman ON olaylar(zaman);
"""
PANEL_HTML = os.path.join(os.path.dirname(os.path.abspath(__file__)), "panel.html")
GH = "https://api.github.com/repos/osmanevski/Ezgi/releases"
_gh_cache = {"t": 0, "v": None}

_local = threading.local()
_rate = {}
_rate_lock = threading.Lock()


def db():
    c = getattr(_local, "c", None)
    if c is None:
        c = sqlite3.connect(DB, timeout=10)
        c.execute("PRAGMA journal_mode=WAL")
        c.execute("PRAGMA busy_timeout=5000")
        _local.c = c
    return c


def s(v, n=300):
    return v[:n] if isinstance(v, str) else None


def i(v):
    return int(v) if isinstance(v, (int, float)) and not isinstance(v, bool) else None


def limited(key, per_min=30):
    now = time.time()
    with _rate_lock:
        hits = [t for t in _rate.get(key, []) if now - t < 60]
        hits.append(now)
        _rate[key] = hits
        return len(hits) > per_min


class H(BaseHTTPRequestHandler):
    server_version = "Ezgi"
    sys_version = ""

    def log_message(self, *a):  # IP günlüğe yazılmasın
        pass

    def send(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def send_file(self, path, ctype):
        try:
            body = open(path, "rb").read()
        except OSError:
            return self.send(404, {"hata": "yok"})
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def auth(self):
        h = self.headers.get("Authorization", "")
        if not h.startswith("Bearer ") or "." not in h:
            return None
        kur, _, anahtar = h[7:].strip().partition(".")
        if not ID_RE.match(kur) or len(anahtar) < 32 or len(anahtar) > 128:
            return None
        hs = hashlib.sha256(anahtar.encode()).hexdigest()
        c = db()
        row = c.execute("SELECT anahtar_hash FROM kurulumlar WHERE id=?", (kur,)).fetchone()
        now = int(time.time() * 1000)
        if row is None:
            c.execute("INSERT INTO kurulumlar(id, anahtar_hash, ilk, son) VALUES(?,?,?,?)", (kur, hs, now, now))
            c.commit()
        elif not hmac.compare_digest(row[0], hs):
            return None
        return kur

    def do_GET(self):
        u = urlparse(self.path)
        # /panel*: Caddy'de basic_auth arkasında; servis yalnız 127.0.0.1'de dinliyor.
        if u.path == "/panel":  # göreli adresler (veri.json, ad) /panel/ altında çözülsün
            self.send_response(308); self.send_header("Location", "panel/"); self.send_header("Content-Length", "0"); self.end_headers()
            return
        if u.path == "/panel/":
            return self.send_file(PANEL_HTML, "text/html; charset=utf-8")
        if u.path == "/panel/veri.json":
            return self.send(200, panel_data(parse_qs(u.query).get("kurulum", [None])[0]))
        if u.path == "/v1/saglik":
            return self.send(200, {"durum": "iyi"})
        if u.path == "/v1/olaylar":
            kur = self.auth()
            if not kur:
                return self.send(403, {"hata": "kimlik"})
            if limited("g:" + kur):
                return self.send(429, {"hata": "yavaş"})
            try:
                sonra = int(parse_qs(u.query).get("sonra", ["0"])[0])
            except ValueError:
                sonra = 0
            rows = db().execute(
                "SELECT seq,id,tur,anahtar,video,baslik,sanatci,album,sure_ms,dinlenen_ms,bitti,atlandi,zaman "
                "FROM olaylar WHERE kurulum=? AND seq>? ORDER BY seq LIMIT 1000", (kur, sonra)).fetchall()
            keys = ["seq", "id", "tur", "anahtar", "video", "baslik", "sanatci", "album", "sure_ms", "dinlenen_ms", "bitti", "atlandi", "zaman"]
            return self.send(200, {"olaylar": [dict(zip(keys, r)) for r in rows]})
        self.send(404, {"hata": "yok"})

    def do_POST(self):
        if urlparse(self.path).path == "/panel/ad":
            n = int(self.headers.get("Content-Length") or 0)
            try:
                d = json.loads(self.rfile.read(min(n, 4096)))
                kur, ad = d["kurulum"], (s(d.get("ad"), 60) or "").strip() or None
            except Exception:
                return self.send(400, {"hata": "biçim"})
            c = db(); c.execute("UPDATE kurulumlar SET ad=? WHERE id=?", (ad, kur)); c.commit()
            return self.send(200, {"tamam": True})
        if urlparse(self.path).path != "/v1/olaylar":
            return self.send(404, {"hata": "yok"})
        kur = self.auth()
        if not kur:
            return self.send(403, {"hata": "kimlik"})
        if limited("p:" + kur):
            return self.send(429, {"hata": "yavaş"})
        n = int(self.headers.get("Content-Length") or 0)
        if n <= 0 or n > MAX_BODY:
            return self.send(413, {"hata": "boyut"})
        try:
            data = json.loads(self.rfile.read(n))
            evs = data["olaylar"]
            assert isinstance(evs, list) and len(evs) <= MAX_EVENTS
        except Exception:
            return self.send(400, {"hata": "biçim"})
        now = int(time.time() * 1000)
        rows = []
        for e in evs:
            if not isinstance(e, dict):
                continue
            eid, tur, zaman = s(e.get("id"), 64), e.get("tur"), i(e.get("zaman"))
            if not eid or not ID_RE.match(eid) or tur not in TURLER or zaman is None:
                continue
            rows.append((eid, kur, tur, s(e.get("anahtar"), 80), s(e.get("video"), 20), s(e.get("baslik")),
                         s(e.get("sanatci")), s(e.get("album")), i(e.get("sure_ms")), i(e.get("dinlenen_ms")),
                         1 if e.get("bitti") else 0, 1 if e.get("atlandi") else 0, zaman, now))
        c = db()
        before = c.total_changes
        c.executemany("INSERT OR IGNORE INTO olaylar(id,kurulum,tur,anahtar,video,baslik,sanatci,album,sure_ms,"
                      "dinlenen_ms,bitti,atlandi,zaman,alindi) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)", rows)
        c.execute("UPDATE kurulumlar SET son=?, surum=? WHERE id=?", (now, s(data.get("surum"), 20), kur))
        c.commit()
        self.send(200, {"alinan": c.total_changes - before - 1, "gecerli": len(rows)})


def github_downloads():
    """Sürüm başına indirme sayısı; GitHub'a en çok 10 dakikada bir sorulur."""
    if time.time() - _gh_cache["t"] < 600 and _gh_cache["v"] is not None:
        return _gh_cache["v"]
    import urllib.request
    try:
        req = urllib.request.Request(GH, headers={"User-Agent": "Ezgi-panel", "Accept": "application/vnd.github+json"})
        rel = json.loads(urllib.request.urlopen(req, timeout=10).read())
        v = [{"surum": r["tag_name"], "tarih": r["published_at"],
              "indirme": sum(a["download_count"] for a in r["assets"] if a["name"].endswith(".apk"))} for r in rel]
    except Exception:
        v = _gh_cache["v"] or []
    _gh_cache.update(t=time.time(), v=v)
    return v


def panel_data(kurulum=None):
    c = db()
    where, args = ("WHERE o.kurulum=?", [kurulum]) if kurulum else ("", [])
    and_ = "AND" if kurulum else "WHERE"
    kur = [dict(zip(["id", "ad", "surum", "ilk", "son", "dinleme", "dakika", "indirme"], r)) for r in c.execute(
        "SELECT k.id, k.ad, k.surum, k.ilk, k.son,"
        " (SELECT COUNT(*) FROM olaylar WHERE kurulum=k.id AND tur='dinleme'),"
        " (SELECT COALESCE(SUM(dinlenen_ms),0)/60000 FROM olaylar WHERE kurulum=k.id AND tur='dinleme'),"
        " (SELECT COUNT(*) FROM olaylar WHERE kurulum=k.id AND tur='indirme')"
        " FROM kurulumlar k ORDER BY k.son DESC")]
    son = [dict(zip(["zaman", "kurulum", "ad", "tur", "baslik", "sanatci", "dinlenen_ms", "sure_ms", "bitti", "atlandi", "video"], r))
           for r in c.execute(
        "SELECT o.zaman, o.kurulum, k.ad, o.tur, o.baslik, o.sanatci, o.dinlenen_ms, o.sure_ms, o.bitti, o.atlandi, o.video"
        f" FROM olaylar o LEFT JOIN kurulumlar k ON k.id=o.kurulum {where} ORDER BY o.zaman DESC LIMIT 150", args)]
    otuz = int(time.time() * 1000) - 30 * 86400_000
    def top(group, days):
        q = (f"SELECT {group}, COUNT(*), COALESCE(SUM(dinlenen_ms),0)/60000 FROM olaylar o {where} {and_} tur='dinleme'"
             f" AND dinlenen_ms>=30000 {'AND zaman>=' + str(otuz) if days else ''} GROUP BY {group} ORDER BY 2 DESC, 3 DESC LIMIT 10")
        return [list(r) for r in c.execute(q, args)]
    gunluk = [list(r) for r in c.execute(
        f"SELECT date(zaman/1000,'unixepoch','+3 hours') g, COALESCE(SUM(dinlenen_ms),0)/60000 FROM olaylar o {where} {and_} tur='dinleme'"
        f" AND zaman>=? GROUP BY g ORDER BY g", args + [otuz])]
    return {"kurulumlar": kur, "son": son, "sarki_30": top("baslik || ' — ' || sanatci", True),
            "sarki_tum": top("baslik || ' — ' || sanatci", False), "sanatci_30": top("sanatci", True),
            "gunluk": gunluk, "indirmeler": github_downloads(), "secili": kurulum}


def main():
    os.makedirs(os.path.dirname(DB), exist_ok=True)
    c = sqlite3.connect(DB)
    c.executescript(SCHEMA)
    c.close()
    c = sqlite3.connect(DB)
    cols = [r[1] for r in c.execute("PRAGMA table_info(kurulumlar)")]
    if "ad" not in cols:
        c.execute("ALTER TABLE kurulumlar ADD COLUMN ad TEXT")  # panelde verilen ad, ör. "Mehmet Emin"
        c.commit()
    c.close()
    ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()


if __name__ == "__main__":
    main()
