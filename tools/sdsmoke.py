#!/usr/bin/env python3
"""Uses Smart Downloader on an Android emulator like a person would: waits until the engine is ready, shares a
video link to the app, downloads it as video (MP4) and as MP3 audio, and checks the files in Download/Smart
Downloader; then every other file type (M4A, AAC, OPUS, OGG, WAV, FLAC, MKV, 3GP). Then tries a YouTube link (only reported: YouTube often blocks cloud computers).

Usage: sdsmoke.py APK OUTDIR API"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

APK, OUT, API = sys.argv[1], sys.argv[2], int(sys.argv[3])
PKG = "com.blindworldhub.smartdownloader"
CLIP = "http://10.0.2.2:8000/clip.mp4"
os.makedirs(OUT, exist_ok=True)
fails = []
step_no = [0]


def adb(*a, check=False, timeout=120):
    r = subprocess.run(["adb", *a], capture_output=True, text=True, timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError("adb %s: %s" % (" ".join(a), r.stderr))
    return r.stdout


def shot(name):
    step_no[0] += 1
    p = os.path.join(OUT, "%02d-%s.png" % (step_no[0], name))
    with open(p, "wb") as f:
        subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=f, timeout=60)


def dump():
    for _ in range(3):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", timeout=60)
        x = adb("shell", "cat", "/sdcard/ui.xml")
        if "<hierarchy" in x:
            try:
                return ET.fromstring(x[x.index("<?xml") if "<?xml" in x else x.index("<hierarchy"):])
            except ET.ParseError:
                pass
        time.sleep(1)
    return ET.fromstring("<hierarchy/>")


def texts(root):
    return [n.get("text", "") for n in root.iter("node")] + [n.get("content-desc", "") for n in root.iter("node")]


def node(root, pred):
    for n in root.iter("node"):
        if pred(n):
            return n
    return None


def center(n):
    b = re.findall(r"\d+", n.get("bounds"))
    x1, y1, x2, y2 = map(int, b)
    return (x1 + x2) // 2, (y1 + y2) // 2


def screen():
    m = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
    return (int(m.group(1)), int(m.group(2))) if m else (1080, 1920)


def swipe(up=True):
    w, h = screen()
    if up:
        adb("shell", "input", "swipe", str(w // 2), str(h * 3 // 4), str(w // 2), str(h // 4), "300")
    else:
        adb("shell", "input", "swipe", str(w // 2), str(h // 4), str(w // 2), str(h * 3 // 4), "300")
    time.sleep(0.8)


def tap(pred, what, tries=10):
    for i in range(tries):
        r = dump()
        n = node(r, pred)
        if n is not None:
            x, y = center(n)
            adb("shell", "input", "tap", str(x), str(y))
            time.sleep(1)
            return True
        swipe(up=(i < tries // 2))
    fails.append("could not find " + what)
    print("FAIL: could not find", what)
    return False


def wait_text(pred, what, seconds, scroll=False):
    """Waits for a text on the screen; with scroll, also looks further down (the Downloads list)."""
    end = time.time() + seconds
    last = []
    while time.time() < end:
        r = dump()
        last = [t for t in texts(r) if t]
        for t in last:
            if pred(t):
                return t
        if scroll:
            swipe(up=True)
            swipe(up=True)
            r = dump()
            last = [t for t in texts(r) if t]
            for t in last:
                if pred(t):
                    return t
            for _ in range(4):
                swipe(up=False)
        time.sleep(3)
    print("TIMEOUT waiting for %s; screen had: %s" % (what, last[:40]))
    return None


def files():
    return adb("shell", "ls -l '/sdcard/Download/Smart Downloader/'")


def share(url):
    adb("shell", "am", "start", "-a", "android.intent.action.SEND", "-t", "text/plain",
        "--es", "android.intent.extra.TEXT", "'Watch this " + url + "'", "-n", PKG + "/.MainActivity")


def main():
    adb("install", "-r", "-g", APK, check=True, timeout=300)
    if API >= 33:
        adb("shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS")
    if API < 29:
        adb("shell", "pm", "grant", PKG, "android.permission.WRITE_EXTERNAL_STORAGE")
    adb("logcat", "-c")
    adb("shell", "am", "start", "-n", PKG + "/.MainActivity")
    time.sleep(5)
    shot("opened")
    t = wait_text(lambda s: s.startswith("Ready. Paste a link"), "the engine to be ready", 420)
    shot("ready")
    if not t:
        fails.append("the engine did not get ready")
        return

    # 1. video
    share(CLIP)
    t = wait_text(lambda s: s.startswith("Found.") or s == "Video file type" or s == "Audio file type" or "did not work" in s
                  or "not supported" in s, "the clip to be found", 180)
    shot("found")
    if not t or not (t.startswith("Found.") or t.endswith("file type")):
        fails.append("the clip was not found: %s" % t)
        return
    tap(lambda n: n.get("text") == "Video" and n.get("class", "").endswith("RadioButton"), "the Video choice")
    tap(lambda n: n.get("text") == "Download" and n.get("class", "").endswith("Button"), "the Download button")
    t = wait_text(lambda s: s.startswith("Saved: clip") or s.startswith("Failed."), "the video to be saved", 300, scroll=True)
    shot("video-saved")
    ls = files()
    print("Download/Smart Downloader:\n" + ls)
    if not t or not t.startswith("Saved:") or "clip.mp4" not in ls:
        fails.append("the video was not saved: %s" % t)

    # 2. MP3
    for _ in range(4):
        swipe(up=False)
    tap(lambda n: n.get("text") == "Audio" and n.get("class", "").endswith("RadioButton"), "the Audio choice")
    tap(lambda n: n.get("text", "").startswith("MP3") and n.get("class", "").endswith("RadioButton"), "the MP3 file type")
    tap(lambda n: n.get("text", "").startswith("192 kbps"), "MP3 192 kbps")
    tap(lambda n: n.get("text") == "Download" and n.get("class", "").endswith("Button"), "the Download button (audio)")
    t = wait_text(lambda s: s.startswith("Saved: clip.mp3") or (s.startswith("Failed.")), "the MP3 to be saved", 300, scroll=True)
    shot("mp3-saved")
    ls = files()
    print("Download/Smart Downloader:\n" + ls)
    if not t or "clip.mp3" not in ls:
        fails.append("the MP3 was not saved: %s" % t)

    # 3. every other file type, one after the other: each must give its file
    for kind, typ, exts in [("Audio", "M4A", ["m4a"]), ("Audio", "AAC", ["aac", "m4a"]), ("Audio", "OPUS", ["opus"]),
                            ("Audio", "OGG", ["ogg"]), ("Audio", "WAV", ["wav"]), ("Audio", "FLAC", ["flac"]),
                            ("Video", "MKV", ["mkv"]), ("Video", "3GP", ["3gp"])]:
        before = set(files().split("\n"))
        for _ in range(4):
            swipe(up=False)
        tap(lambda n: n.get("text") == kind and n.get("class", "").endswith("RadioButton"), "the %s choice" % kind)
        tap(lambda n: (n.get("text", "") == typ or n.get("text", "").startswith(typ + " ")) and n.get("class", "").endswith("RadioButton"),
            "the %s file type" % typ)
        tap(lambda n: n.get("text") == "Download" and n.get("class", "").endswith("Button"), "the Download button (%s)" % typ)
        got = None
        end = time.time() + 240
        while time.time() < end and not got:
            for line in files().split("\n"):
                if line not in before and any(line.strip().endswith("." + e) for e in exts):
                    got = line.strip()
            time.sleep(4)
        shot("type-" + typ.lower())
        print("%s %s: %s" % (kind, typ, got or "NOT SAVED"))
        if not got:
            fails.append("%s %s was not saved" % (kind, typ))

    # 4. YouTube (reported only)
    for _ in range(4):
        swipe(up=False)
    share("https://www.youtube.com/watch?v=jNQXAC9IVRw")
    t = wait_text(lambda s: s.startswith("Found.") or s.endswith("file type") or "robot" in s or "did not work" in s or "refused" in s or "not available" in s,
                  "YouTube", 180)
    shot("youtube")
    print("YOUTUBE RESULT (not a failure if blocked):", t)


try:
    main()
finally:
    with open(os.path.join(OUT, "logcat.txt"), "w") as f:
        f.write(adb("logcat", "-d", "-t", "4000", timeout=60))
    with open(os.path.join(OUT, "app-log.txt"), "w") as f:
        f.write(adb("shell", "run-as", PKG, "cat", "files/log.txt"))
    shot("end")
print("FAILURES:", fails if fails else "none")
sys.exit(1 if fails else 0)
