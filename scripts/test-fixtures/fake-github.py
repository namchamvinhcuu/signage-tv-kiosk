"""Fake GitHub releases API for scripts/test-kiosk-pull.sh (stdlib only).

Two servers on 127.0.0.1: the API (release list, releases/latest, asset URLs that 302-redirect like GitHub does) and
a "storage" host reached through the name `localhost`, so curl sees a different host after the redirect. The storage
server records the request headers of every blob download, so the test can prove the token is not forwarded; the API
server records them too (<state_dir>/api-headers.log), so the test can prove an anonymous run sends no Authorization.

Scenario: <state_dir>/state.json, re-read on every request (the test edits it between runs):
  {"token": "...", "public": true|false (optional; true = a request WITHOUT Authorization is accepted, one WITH it
   must still carry the right token, as on a public GitHub repo), "releases": [...GitHub-like objects; asset "url" = API asset URL...],
   "latest": "v0.2.0" | null, "blobs": {"<asset id>": "<file path>"},
   "override": {"<API path>": <HTTP status>},   (optional: forces that status after the token check)
   "raw": {"<API path>": "<body>"}}             (optional: answers 200 with that literal body after the token check)
Writes <state_dir>/ports ("<api port> <storage port>") once both servers listen.
"""
import json
import os
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

STATE_DIR = sys.argv[1]


def state():
    with open(os.path.join(STATE_DIR, "state.json"), encoding="utf-8") as f:
        return json.load(f)


class Api(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def send(self, code, body=b"", headers=None):
        self.send_response(code)
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        st = state()
        with open(os.path.join(STATE_DIR, "api-headers.log"), "a", encoding="utf-8") as f:
            f.write(json.dumps(dict(self.headers.items())) + "\n")
        auth = self.headers.get("Authorization")
        if st.get("public") and auth is None:
            pass
        elif auth is None:
            # Real GitHub hides a private repo from anonymous callers with 404, not 401.
            return self.send(404, b'{"message":"Not Found"}')
        elif auth != "Bearer " + st["token"]:
            return self.send(401, b'{"message":"Bad credentials"}')
        path = self.path.split("?")[0]
        override = st.get("override", {}).get(path)
        if override:
            return self.send(int(override), b'{"message":"forced by test"}')
        raw = st.get("raw", {}).get(path)
        if raw is not None:
            return self.send(200, raw.encode())
        prefix = "/repos/o/r/releases"
        if path == prefix:
            return self.send(200, json.dumps(st["releases"]).encode())
        if path == prefix + "/latest":
            for rel in st["releases"]:
                if rel["tag_name"] == st.get("latest"):
                    return self.send(200, json.dumps(rel).encode())
            return self.send(404, b'{"message":"Not Found"}')
        if path.startswith(prefix + "/assets/"):
            if self.headers.get("Accept") != "application/octet-stream":
                return self.send(415)
            asset_id = path.rsplit("/", 1)[1]
            loc = "http://localhost:%d/blob/%s" % (STORAGE_PORT, asset_id)
            return self.send(302, headers={"Location": loc})
        return self.send(404)


class Storage(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        with open(os.path.join(STATE_DIR, "storage-headers.log"), "a", encoding="utf-8") as f:
            f.write(json.dumps(dict(self.headers.items())) + "\n")
        asset_id = self.path.rsplit("/", 1)[1]
        blob = state()["blobs"].get(asset_id)
        if blob is None:
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        with open(blob, "rb") as f:
            data = f.read()
        self.send_response(200)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        try:
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass  # the client gave up on purpose (curl --max-filesize), not a fixture error



api = ThreadingHTTPServer(("127.0.0.1", 0), Api)
storage = ThreadingHTTPServer(("127.0.0.1", 0), Storage)
STORAGE_PORT = storage.server_address[1]
threading.Thread(target=storage.serve_forever, daemon=True).start()
with open(os.path.join(STATE_DIR, "ports.tmp"), "w", encoding="utf-8") as f:
    f.write("%d %d\n" % (api.server_address[1], STORAGE_PORT))
os.replace(os.path.join(STATE_DIR, "ports.tmp"), os.path.join(STATE_DIR, "ports"))
api.serve_forever()
