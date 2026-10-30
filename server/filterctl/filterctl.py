#!/usr/bin/env python3
"""Мініатюрний API для додатку: список блоклістів AdGuard Home і їх вмикання/вимикання.
Слухає лише у docker-мережі (10.8.1.4:8080), назовні не публікується: доступний тільки через VPN."""
import base64, json, os, threading, queue, urllib.request, urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

AGH = os.environ.get("AGH_URL", "http://10.8.1.2:3000")
REG = "https://adguardteam.github.io/HostlistsRegistry/assets/"
HZ = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/"

# (назва, опис, url)
CATALOG = [
    ("AdGuard DNS filter", "Реклама й трекери", REG + "filter_1.txt"),
    ("OISD Big", "Збалансований великий список", REG + "filter_27.txt"),
    ("OISD Small", "Легка версія OISD", "https://small.oisd.nl"),
    ("HaGeZi Light", "М'яке блокування", HZ + "light.txt"),
    ("HaGeZi Multi Normal", "Реклама, трекери, шкідливі", HZ + "multi.txt"),
    ("HaGeZi Pro", "Суворіше, мало хибних спрацювань", HZ + "pro.txt"),
    ("HaGeZi Pro++", "Дуже суворо", HZ + "pro.plus.txt"),
    ("HaGeZi Ultimate", "Максимально суворо", HZ + "ultimate.txt"),
    ("HaGeZi Threat Intelligence", "Загрози: фішинг, шкідливе ПЗ", HZ + "tif.txt"),
    ("Peter Lowe's list", "Класичний список реклами", REG + "filter_3.txt"),
    ("Phishing Army", "Фішингові сайти", REG + "filter_30.txt"),
    ("Hacked Malware Sites", "Зламані сайти з вірусами", REG + "filter_9.txt"),
    ("URLhaus Malware", "Домени з шкідливим ПЗ", "https://urlhaus.abuse.ch/downloads/hostfile/"),
    ("StevenBlack Unified", "Реклама й шкідливі сайти", "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"),
    ("1Hosts Lite", "Легкий список реклами", "https://o0.pages.dev/Lite/adblock.txt"),
]
BY_URL = {u: (n, d) for n, d, u in CATALOG}


def password():
    for line in open("/run/adguard-password.txt"):
        if line.startswith("password:"):
            return line.split(":", 1)[1].strip()
    raise RuntimeError("no password")


def agh(path, body=None, timeout=600):
    auth = base64.b64encode(f"admin:{password()}".encode()).decode()
    req = urllib.request.Request(AGH + path, method="POST" if body is not None else "GET",
                                 data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Authorization": "Basic " + auth, "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        raw = r.read().decode()
        return json.loads(raw) if raw.strip().startswith(("{", "[")) else raw


PENDING = {}   # url -> бажаний стан, поки зміна не застосована
ERRORS = {}    # url -> текст останньої помилки
Q = queue.Queue()


def worker():
    while True:
        url = Q.get()
        want = PENDING.get(url)
        try:
            if want is not None:
                set_filter(url, want)
                ERRORS.pop(url, None)
        except urllib.error.HTTPError as e:
            ERRORS[url] = e.read().decode(errors="replace").strip() or str(e)
        except Exception as e:
            ERRORS[url] = str(e)
        finally:
            if PENDING.get(url) == want:
                PENDING.pop(url, None)
            Q.task_done()


def listing():
    st = agh("/control/filtering/status", timeout=30)
    cur = {f["url"]: f for f in (st.get("filters") or [])}
    out = []
    for name, desc, url in CATALOG:
        f = cur.get(url)
        out.append({"name": name, "desc": desc, "url": url,
                    "enabled": PENDING.get(url, bool(f and f["enabled"])), "rules": f["rules_count"] if f else 0,
                    "busy": url in PENDING, "error": ERRORS.get(url, "")})
    for url, f in cur.items():  # власні списки, яких немає в каталозі
        if url not in BY_URL:
            out.append({"name": f["name"], "desc": "Власний список", "url": url,
                        "enabled": PENDING.get(url, f["enabled"]), "rules": f["rules_count"],
                        "busy": url in PENDING, "error": ERRORS.get(url, "")})
    return out


def set_filter(url, enabled):
    st = agh("/control/filtering/status", timeout=30)
    cur = {f["url"]: f for f in (st.get("filters") or [])}
    name = BY_URL.get(url, (cur.get(url, {}).get("name", url), ""))[0]
    if url in cur:
        agh("/control/filtering/set_url", {"url": url, "data": {"name": name, "url": url, "enabled": enabled}, "whitelist": False})
    elif enabled and url in BY_URL:
        agh("/control/filtering/add_url", {"name": name, "url": url, "whitelist": False})
    else:
        raise ValueError("unknown list")


class H(BaseHTTPRequestHandler):
    def _send(self, code, obj):
        data = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        try:
            if self.path == "/api/filters":
                return self._send(200, listing())
            if self.path == "/health":
                return self._send(200, {"ok": True})
            self._send(404, {"error": "not found"})
        except Exception as e:
            self._send(502, {"error": str(e)})

    def do_POST(self):
        try:
            if self.path != "/api/filters":
                return self._send(404, {"error": "not found"})
            body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
            url = body["url"]
            if url not in BY_URL and url not in {f["url"] for f in listing()}:
                raise ValueError("unknown list")
            ERRORS.pop(url, None)
            PENDING[url] = bool(body["enabled"])
            Q.put(url)
            self._send(202, {"ok": True})
        except urllib.error.HTTPError as e:
            self._send(502, {"error": e.read().decode(errors="replace").strip() or str(e)})
        except Exception as e:
            self._send(400, {"error": str(e)})

    def log_message(self, *a):
        pass


threading.Thread(target=worker, daemon=True).start()
ThreadingHTTPServer(("0.0.0.0", 8080), H).serve_forever()
