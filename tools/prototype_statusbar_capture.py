#!/usr/bin/env python3
"""Throwaway real-emulator experiment. Images are observations, not assertions."""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET

from PIL import Image

parser = argparse.ArgumentParser()
parser.add_argument("--apk", required=True)
parser.add_argument("--evidence", required=True)
args = parser.parse_args()
out = Path(args.evidence)
out.mkdir(parents=True, exist_ok=True)
package = "com.usagelimits.debug"
receiver = f"{package}/com.usagelimits.prototype.PrototypeStatusbarReceiver"
activity = f"{package}/com.usagelimits.prototype.PrototypeStatusbarActivity"


def adb(*command, check=True, binary=False):
    argv = ["adb", "shell", shlex.join(command[1:])] if command[0] == "shell" else ["adb", *command]
    result = subprocess.run(argv, capture_output=True, timeout=90, check=check)
    return result.stdout if binary else (result.stdout + result.stderr).decode(errors="replace")


def save(name, text):
    (out / name).write_text(text)


def broadcast(command="post", **extras):
    argv = ["shell", "am", "broadcast", "-n", receiver, "--es", "command", command]
    for key, value in extras.items():
        argv += ["--es", key, str(value)]
    response = adb(*argv)
    if "result=0" not in response:
        raise RuntimeError(response)
    # API 36 GroupHelper applies sparse/singleton regrouping after a 3s delay.
    time.sleep(5 if api >= 36 and command in ("post", "update") else 2)
    if command in ("post", "update"):
        active = adb("shell", "dumpsys", "notification", "--noredact")
        present = set(re.findall(r"NotificationRecord\([^\n]*pkg=com\.usagelimits\.debug[^\n]*? id=(710[012])\b", active))
        if present != {"7100", "7101", "7102"}:
            save("rejected-post-notification.txt", active)
            raise RuntimeError(f"Expected three actual posted prototype notifications; observed {present}")


def screen(name):
    path = out / f"{name}.png"
    path.write_bytes(adb("exec-out", "screencap", "-p", binary=True))
    if not name.endswith("shade"):
        with Image.open(path) as image:
            # A crop of real screenshot pixels, not a reconstructed status bar.
            image.crop((0, 0, image.width, 96)).save(out / f"{name}-statusbar.png")


def dump(name):
    save(f"{name}-notification.txt", adb("shell", "dumpsys", "notification", "--noredact"))
    save(f"{name}-systemui.txt", adb("shell", "dumpsys", "activity", "service", "com.android.systemui/.SystemUIService"))
    save(f"{name}-statusbar.txt", adb("shell", "dumpsys", "statusbar"))
    adb("shell", "uiautomator", "dump", "/sdcard/prototype-window.xml", check=False)
    save(f"{name}-window.xml", adb("shell", "cat", "/sdcard/prototype-window.xml", check=False))


def show(name, light=False):
    adb("shell", "cmd", "statusbar", "collapse", check=False)
    adb("shell", "am", "start", "-f", "0x10008000", "-n", activity, "--es", "label", name,
        "--ez", "light", str(light).lower())
    time.sleep(2)


def capture(name, light=False):
    show(name, light)
    screen(name)
    dump(name)
    adb("shell", "cmd", "statusbar", "expand-notifications")
    time.sleep(2)
    screen(f"{name}-shade")
    adb("shell", "uiautomator", "dump", "/sdcard/prototype-window.xml", check=False)
    save(f"{name}-shade-window.xml", adb("shell", "cat", "/sdcard/prototype-window.xml", check=False))
    adb("shell", "cmd", "statusbar", "collapse", check=False)


api = int(adb("shell", "getprop", "ro.build.version.sdk").strip())
save("device.txt", adb("shell", "getprop") + adb("shell", "wm", "size") + adb("shell", "wm", "density"))
save("notification-command-help.txt", adb("shell", "cmd", "notification", "help", check=False))
adb("install", "-r", args.apk)
if api >= 33:
    adb("shell", "pm", "grant", package, "android.permission.POST_NOTIFICATIONS")
adb("shell", "settings", "put", "secure", "lock_screen_show_notifications", "1")
adb("shell", "settings", "put", "secure", "lock_screen_allow_private_notifications", "1")
adb("shell", "svc", "power", "stayon", "true")
adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
adb("shell", "wm", "dismiss-keyguard", check=False)
adb("shell", "logcat", "-c")

if api == 36:
    for scenario in ("candidate-channels", "candidate-composite-bars", "candidate-composite-rings", "candidate-sections", "candidate-colorized", "candidate-summary-one", "candidate-valid-groups", "candidate-summary-three"):
        broadcast("clear")
        broadcast(variant="C", scenario=scenario)
        capture(f"api{api}-{scenario}")
    broadcast("clear")

for scenario in (("a", "b") if api in (34, 35) else ("a", "b", "c", "d", "e")):
    for variant in ("A", "C"):
        # Competition runs last; the stable shell tags update rather than accumulating.
        name = f"api{api}-{variant}-{scenario}"
        broadcast("clear")
        broadcast(variant=variant, scenario=scenario)
        if scenario == "e":
            broadcast("alert")
            save(f"{name}-shell-post.txt", adb("shell", "cmd", "notification", "post", "-t", "Other package one", "prototype-other-1", "First shell notification", check=False)
                 + adb("shell", "cmd", "notification", "post", "-t", "Other package two", "prototype-other-2", "Second shell notification", check=False))
            time.sleep(2)
        capture(name)
        if scenario == "b" and api not in (34, 35):
            capture(name + "-light", light=True)
            broadcast(variant=variant, scenario=scenario, states="stale,exhausted,error")
            capture(name + "-states")
            broadcast(variant=variant, scenario=scenario)
            dump(name + "-before-update")
            save(name + "-before-update-events.txt", adb("shell", "logcat", "-b", "events", "-d", "-v", "threadtime"))
            broadcast("update", variant=variant, scenario=scenario)
            capture(name + "-updated")
            save(name + "-update-events.txt", adb("shell", "logcat", "-b", "events", "-d", "-v", "threadtime"))
            # Find the actual Codex notification title in the shade, not a guessed row.
            adb("shell", "cmd", "statusbar", "expand-notifications")
            time.sleep(2)
            adb("shell", "uiautomator", "dump", "/sdcard/prototype-window.xml", check=False)
            xml = adb("shell", "cat", "/sdcard/prototype-window.xml")
            root = ET.fromstring(xml[xml.index("<?xml"):])
            target = next((node for node in root.iter("node") if "PROTOTYPE codex 99" in node.get("text", "")), None)
            if api >= 36:
                # Android 16 force-groups even singleton app groups. Expand the actual
                # summary so the swipe reaches one child, not all three accounts.
                app_row = next((row for row in root.iter("node") if row.get("resource-id", "").endswith("/expandableNotificationRow") and
                                any(n.get("text") == "Usage Limits" for n in row.iter("node"))), None)
                expand = next((n for n in app_row.iter("node") if n.get("resource-id") == "android:id/expand_button" and n.get("content-desc") == "Expand"), None) if app_row is not None else None
                if expand is not None:
                    bounds = list(map(int, re.findall(r"\d+", expand.attrib["bounds"])))
                    adb("shell", "input", "tap", str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))
                    time.sleep(2)
                    screen(name + "-expanded-before-swipe-shade")
                    adb("shell", "uiautomator", "dump", "/sdcard/prototype-window.xml", check=False)
                    xml = adb("shell", "cat", "/sdcard/prototype-window.xml")
                    save(name + "-expanded-before-swipe-window.xml", xml)
                    root = ET.fromstring(xml[xml.index("<?xml"):])
                    target = next((node for node in root.iter("node") if "PROTOTYPE codex 99" in node.get("text", "")), None)
            if target is not None:
                coords = list(map(int, re.findall(r"\d+", target.attrib["bounds"])))
                y = (coords[1] + coords[3]) // 2
                width = int(re.search(r"(\d+)x\d+", adb("shell", "wm", "size"))[1])
                save(name + "-swipe.txt", f"title_bounds={coords}; swipe={width//2},{y} → {width-2},{y}; API={api}\n")
                adb("shell", "input", "swipe", str(width // 2), str(y), str(width - 2), str(y), "300")
                time.sleep(2)
            else:
                save(name + "-swipe.txt", "Codex title not visible; swipe not attempted.\n")
            screen(name + "-after-swipe-shade")
            dump(name + "-after-swipe")
            deletion_log = adb("shell", "logcat", "-d", "-v", "threadtime", "-s", "PrototypeStatusbar:I", "*:S")
            save(name + "-prototype-log.txt", deletion_log)
            if api >= 34:
                after_swipe = (out / f"{name}-after-swipe-notification.txt").read_text()
                remaining = set(re.findall(r"NotificationRecord\([^\n]*pkg=com\.usagelimits\.debug[^\n]*? id=(710[012])\b", after_swipe))
                if remaining != {"7100", "7102"} or "DELETE_INTENT id=7101" not in deletion_log:
                    raise RuntimeError(f"Expected only Codex dismissed with delete intent; remaining={remaining}")
            adb("shell", "cmd", "statusbar", "collapse", check=False)
            broadcast("clear")
            broadcast(variant=variant, scenario="b")
            # Lock, wake without dismissing the keyguard, then try native doze support.
            show(name + "-lock")
            save(f"api{api}-{variant}-locksettings.txt", adb("shell", "locksettings", "set-disabled", "false", check=False))
            adb("shell", "input", "keyevent", "KEYCODE_SLEEP")
            time.sleep(2)
            adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
            time.sleep(2)
            screen(f"api{api}-{variant}-f-lock")
            dump(f"api{api}-{variant}-f-lock")
            save(f"api{api}-{variant}-f-lock-window.txt", adb("shell", "dumpsys", "window"))
            adb("shell", "cmd", "statusbar", "expand-notifications", check=False)
            time.sleep(2)
            screen(f"api{api}-{variant}-f-lock-shade")
            adb("shell", "cmd", "statusbar", "collapse", check=False)
            save(f"api{api}-{variant}-doze-commands.txt", adb("shell", "dumpsys", "deviceidle", "force-idle", check=False)
                 + adb("shell", "dumpsys", "activity", "service", "com.android.systemui/.SystemUIService", "--help", check=False))
            adb("shell", "settings", "put", "secure", "doze_always_on", "1")
            adb("shell", "input", "keyevent", "KEYCODE_SLEEP")
            time.sleep(5)
            screen(f"api{api}-{variant}-f-doze-attempt")
            save(f"api{api}-{variant}-doze-power.txt", adb("shell", "dumpsys", "power") + adb("shell", "dumpsys", "dreams"))
            adb("shell", "dumpsys", "deviceidle", "unforce", check=False)
            adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
            adb("shell", "wm", "dismiss-keyguard", check=False)

save("prototype-log.txt", adb("shell", "logcat", "-d", "-v", "threadtime", "-s", "PrototypeStatusbar:I", "*:S"))
save("notification-events.txt", adb("shell", "logcat", "-b", "events", "-d", "-v", "threadtime"))
save("capture-manifest.json", json.dumps({"api": api, "variants": ["A", "C"], "scenarios": (["a", "b"] if api in (34, 35) else ["a", "b", "c", "d", "e", "f-lock", "f-doze-attempt"]), "candidates": (["channels", "composite-bars", "composite-rings", "sections", "colorized", "summary-one", "valid-groups", "summary-three"] if api == 36 else []), "screenshots": sorted(p.name for p in out.glob("*.png"))}, indent=2))
print(f"API {api}: captured {len(list(out.glob('*.png')))} PNGs to {out}")
