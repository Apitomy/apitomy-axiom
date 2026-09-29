#!/usr/bin/env python3
"""Capture a real opencode SSE event stream for one prompt with tool calls and a permission request."""
import http.client
import json
import os
import subprocess
import threading
import time
import urllib.request

PORT = 47140
BASE = f"http://127.0.0.1:{PORT}"
WORK = "/tmp/opencode/cap"
OUT = "/tmp/opencode/cap-events.jsonl"

os.makedirs(WORK, exist_ok=True)
with open(os.path.join(WORK, "hello.txt"), "w") as f:
    f.write("Hello from the Axiom capture fixture.\n")
with open("/tmp/opencode/cap-config.json", "w") as f:
    json.dump({"permission": {"bash": "ask"}}, f)

env = dict(os.environ, OPENCODE_CONFIG="/tmp/opencode/cap-config.json")
proc = subprocess.Popen(["opencode", "serve", "--hostname", "127.0.0.1", "--port", str(PORT)],
                        cwd=WORK, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def req(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method,
                               headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(r, timeout=30) as resp:
        raw = resp.read()
        return json.loads(raw) if raw else None


for _ in range(120):
    try:
        req("GET", "/global/health")
        break
    except Exception:
        time.sleep(0.25)

events = []
done = threading.Event()
session = {}


def stream():
    conn = http.client.HTTPConnection("127.0.0.1", PORT, timeout=300)
    conn.request("GET", "/event", headers={"Accept": "text/event-stream"})
    resp = conn.getresponse()
    buf = []
    with open(OUT, "w") as out:
        while not done.is_set():
            line = resp.readline()
            if not line:
                break
            line = line.decode().rstrip("\n")
            if line.startswith("data:"):
                buf.append(line[5:].strip())
            elif line == "" and buf:
                payload = json.loads("\n".join(buf))
                buf = []
                out.write(json.dumps(payload) + "\n")
                out.flush()
                events.append(payload)
                t = payload.get("type", "")
                props = payload.get("properties", {})
                if t.startswith("permission") and t != "permission.replied" and session.get("id"):
                    pid = props.get("id") or props.get("permissionID")
                    try:
                        req("POST", f"/session/{session['id']}/permissions/{pid}", {"response": "once"})
                    except Exception as e:  # noqa: BLE001
                        print("permission reply failed", e)
                if t == "session.idle" and props.get("sessionID") == session.get("id"):
                    done.set()


threading.Thread(target=stream, daemon=True).start()
time.sleep(1)
s = req("POST", "/session", {"title": "capture"})
session["id"] = s["id"]
req("POST", f"/session/{s['id']}/prompt_async", {
    "model": {"providerID": "github-copilot", "modelID": "claude-sonnet-5"},
    "system": "You are a test fixture generator. Be extremely brief.",
    "parts": [{"type": "text", "text":
               "Use the read tool to read hello.txt, then use the bash tool to run `echo captured`, "
               "then reply with exactly: DONE"}],
})
done.wait(240)
proc.terminate()
print("events:", len(events))
