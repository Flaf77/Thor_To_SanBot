#!/usr/bin/env python3
"""
Thor-side client for the "Sanbot Thor Bridge" Android app (stdlib only, Python 3.8+).

Two ways to connect (use whichever matches the app's Connection page):
  1. Thor connects to the tablet   ->  link = SanbotLink.connect("192.168.50.2")
  2. The tablet connects to Thor   ->  link = SanbotLink.listen(port=9100)   (app: "Connect to Thor")

Example:
    link = SanbotLink.connect("192.168.50.2", token="secret")
    link.on_event = lambda name, data: print("event", name, data)
    link.cmd("speak", text="Hello from Thor")
    link.cmd("head.absolute", pan=90, tilt=15)
    link.drive("forward", speed=3)          # repeat at >= 3 Hz while you want to move
    print(link.state["power"]["battery_percent"])

Media (tablet IP = link.tablet_ip):
    H.264 head camera : tcp://<tablet>:9101  (raw Annex-B) - see hd_camera_gst() below
    Tablet mic        : tcp://<tablet>:9102  16 kHz mono s16le  - mic_chunks()
    Robot speaker     : tcp://<tablet>:9103  16 kHz mono s16le  - play_pcm()
    Android cameras   : http://<tablet>:8080/camera/<id>.mjpg
"""
import itertools
import json
import socket
import threading
import time

DISCOVERY_PORT = 9199


class SanbotLink:
    def __init__(self, sock, token=None):
        self.sock = sock
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.tablet_ip = sock.getpeername()[0]
        self.hello = None
        self.state = {}
        self.on_event = None          # callback(name, data)
        self.on_state = None          # callback(state_dict)
        self._ids = itertools.count(1)
        self._pending = {}
        self._lock = threading.Lock()
        self._send_lock = threading.Lock()
        self._hello_evt = threading.Event()
        self.alive = True
        threading.Thread(target=self._rx, daemon=True).start()
        threading.Thread(target=self._heartbeat, daemon=True).start()
        if not self._hello_evt.wait(5):
            raise ConnectionError("no hello from the tablet")
        if token:
            self._send({"type": "auth", "token": token})

    # ---------------------------------------------------------------- connecting

    @classmethod
    def connect(cls, tablet_ip, port=9100, token=None, timeout=5):
        """Thor -> tablet (the app's 'Let Thor connect' must be enabled)."""
        s = socket.create_connection((tablet_ip, port), timeout=timeout)
        s.settimeout(None)
        return cls(s, token)

    @classmethod
    def listen(cls, port=9100, token=None, beacon=True, name="thor"):
        """Tablet -> Thor (the app's 'Connect to Thor' must point at this machine)."""
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind(("0.0.0.0", port))
        srv.listen(1)
        stop = threading.Event()
        if beacon:
            threading.Thread(target=_beacon_loop, args=(name, port, stop), daemon=True).start()
        print(f"[thor] waiting for the tablet on port {port} ...")
        s, addr = srv.accept()
        stop.set()
        srv.close()
        print(f"[thor] tablet connected from {addr[0]}")
        return cls(s, token)

    # ---------------------------------------------------------------- commands

    def cmd(self, name, timeout=5.0, **args):
        """Send a command and wait for its ack. Returns the ack dict (check ack['ok'])."""
        cid = next(self._ids)
        evt = threading.Event()
        with self._lock:
            self._pending[cid] = [evt, None]
        self._send({"type": "cmd", "id": cid, "cmd": name, "args": args})
        if not evt.wait(timeout):
            with self._lock:
                self._pending.pop(cid, None)
            raise TimeoutError(f"no ack for {name}")
        with self._lock:
            return self._pending.pop(cid)[1]

    def cmd_nowait(self, name, **args):
        self._send({"type": "cmd", "id": next(self._ids), "cmd": name, "args": args})

    def drive(self, action, speed=3, timeout_ms=600):
        """Velocity-style drive. The tablet stops the wheels if not repeated within timeout_ms."""
        self.cmd_nowait("wheels.drive", action=action, speed=speed, timeout_ms=timeout_ms)

    def stop(self):
        return self.cmd("stop_all")

    def set_state_rate(self, hz):
        self._send({"type": "config", "state_rate_hz": hz})

    def close(self):
        self.alive = False
        try:
            self.sock.close()
        except OSError:
            pass

    # ---------------------------------------------------------------- internals

    def _send(self, obj):
        data = (json.dumps(obj) + "\n").encode()
        with self._send_lock:
            self.sock.sendall(data)

    def _heartbeat(self):
        while self.alive:
            time.sleep(5)
            try:
                self._send({"type": "ping", "t": time.time()})
            except OSError:
                self.alive = False

    def _rx(self):
        buf = b""
        try:
            while self.alive:
                chunk = self.sock.recv(65536)
                if not chunk:
                    break
                buf += chunk
                while b"\n" in buf:
                    line, buf = buf.split(b"\n", 1)
                    if line.strip():
                        self._handle(json.loads(line))
        except (OSError, ValueError) as e:
            print("[thor] link error:", e)
        self.alive = False
        print("[thor] link closed")

    def _handle(self, m):
        t = m.get("type")
        if t == "hello":
            self.hello = m
            self._hello_evt.set()
        elif t == "ack":
            with self._lock:
                p = self._pending.get(m.get("id"))
                if p:
                    p[1] = m
                    p[0].set()
            if not m.get("ok") and m.get("cmd") != "wheels.drive" and m.get("id") not in self._pending:
                print("[thor] command failed:", m.get("cmd"), m.get("error"))
        elif t == "state":
            self.state = m["state"]
            if self.on_state:
                self.on_state(self.state)
        elif t == "event":
            if self.on_event:
                self.on_event(m["name"], m.get("data"))
        elif t == "error":
            print("[thor] tablet error:", m.get("error"))


# -------------------------------------------------------------------- discovery

def _beacon_loop(name, port, stop):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    msg = json.dumps({"type": "thor_beacon", "name": name, "port": port}).encode()
    while not stop.is_set():
        try:
            s.sendto(msg, ("255.255.255.255", DISCOVERY_PORT))
        except OSError:
            pass
        stop.wait(2)


def discover(timeout=5.0):
    """Returns {tablet_ip: beacon_dict} for every Sanbot tablet heard on UDP 9199."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    s.bind(("", DISCOVERY_PORT))
    s.settimeout(0.5)
    s.sendto(json.dumps({"type": "discover"}).encode(), ("255.255.255.255", DISCOVERY_PORT))
    found, end = {}, time.time() + timeout
    while time.time() < end:
        try:
            data, (ip, _) = s.recvfrom(4096)
            m = json.loads(data)
            if m.get("type") == "sanbot_beacon":
                found[ip] = m
        except (socket.timeout, ValueError):
            pass
    s.close()
    return found


# -------------------------------------------------------------------- media helpers

def hd_camera_gst(tablet_ip):
    """GStreamer pipeline for OpenCV on Jetson (hardware H.264 decode)."""
    return (f"tcpclientsrc host={tablet_ip} port=9101 ! h264parse ! nvv4l2decoder ! "
            "nvvidconv ! video/x-raw,format=BGRx ! videoconvert ! video/x-raw,format=BGR ! appsink drop=1 sync=false")


def mic_chunks(tablet_ip, chunk_bytes=640):
    """Yields raw 16 kHz mono s16le PCM chunks (20 ms each by default) from the tablet mic."""
    with socket.create_connection((tablet_ip, 9102)) as s:
        while True:
            data = s.recv(chunk_bytes)
            if not data:
                return
            yield data


def play_pcm(tablet_ip, pcm_bytes):
    """Plays 16 kHz mono s16le PCM on the robot speaker."""
    with socket.create_connection((tablet_ip, 9103)) as s:
        s.sendall(pcm_bytes)


# -------------------------------------------------------------------- demo

if __name__ == "__main__":
    import argparse

    ap = argparse.ArgumentParser(description="Sanbot Thor Bridge demo")
    ap.add_argument("--tablet", help="tablet IP (Thor connects to the tablet)")
    ap.add_argument("--listen", action="store_true", help="wait for the tablet to connect to Thor")
    ap.add_argument("--discover", action="store_true", help="list Sanbot tablets on the network")
    ap.add_argument("--port", type=int, default=9100)
    ap.add_argument("--token", default=None)
    a = ap.parse_args()

    if a.discover:
        for ip, b in discover().items():
            print(ip, b)
        raise SystemExit

    if a.listen:
        link = SanbotLink.listen(a.port, a.token)
    elif a.tablet:
        link = SanbotLink.connect(a.tablet, a.port, a.token)
    else:
        found = discover()
        if not found:
            raise SystemExit("no tablet found; use --tablet IP or --listen")
        ip = next(iter(found))
        print("found tablet", ip)
        link = SanbotLink.connect(ip, found[ip].get("control_port", a.port), a.token)

    print("hello:", json.dumps(link.hello, indent=1)[:1500])
    link.on_event = lambda n, d: print("EVENT", n, d)
    print(link.cmd("speak", text="Hello, Thor is my brain now."))
    print(link.cmd("head.absolute", pan=60))
    time.sleep(1.5)
    print(link.cmd("head.absolute", pan=120))
    time.sleep(1.5)
    print(link.cmd("head.center"))
    print(link.cmd("led", part="all", mode="flicker_blue"))
    print("streaming events/state for 30 s (touch the robot!) ...")
    t_end = time.time() + 30
    while time.time() < t_end and link.alive:
        time.sleep(5)
        print("battery:", link.state.get("power", {}).get("battery_percent"))
    link.close()
