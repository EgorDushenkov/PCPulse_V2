import sys
import os
import io
import ctypes

try:
    hwnd = ctypes.windll.kernel32.GetConsoleWindow()
    if hwnd:
        ctypes.windll.user32.ShowWindow(hwnd, 0)
except:
    pass

# без этого кириллица через pipe с Java ломается
if sys.stdout:
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
if sys.stdin:
    sys.stdin = io.TextIOWrapper(sys.stdin.buffer, encoding='utf-8', errors='replace')

import json
import time
import threading
import queue
import GPUtil
import psutil
import pythoncom
import win32gui
import win32process
import win32con
import pyautogui
import win32ui
import base64
from PIL import Image
from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume, ISimpleAudioVolume
from ctypes import cast, POINTER
from comtypes import CLSCTX_ALL
from winsdk.windows.media.control import GlobalSystemMediaTransportControlsSessionManager as SessionManager
import asyncio
import clr

LOG = "worker_debug.log"

def _log(msg):
    with open(LOG, "a") as f:
        f.write(msg + "\n")

def get_dll_path():
    if getattr(sys, 'frozen', False):
        base = sys._MEIPASS
    else:
        base = os.path.dirname(os.path.abspath(__file__))
    return os.path.join(base, "OpenHardwareMonitorLib.dll")

ohm_ok = False
try:
    clr.AddReference(get_dll_path())
    # pyrefly: ignore [missing-import]
    from OpenHardwareMonitor.Hardware import Computer
    ohm_ok = True
except Exception:
    pass

state = {
    "cpu_temp": "N/A",
    "cpu_freq": 0,
    "gpu": [],
    "fans": [],
    "volume": 0,
    "mic_muted": False,
    "audio_sessions": [],
    "media": None,
    "active_app": "",
    "running_apps": []
}

def extract_icon(path):
    try:
        path = path.strip('"')
        large, small = win32gui.ExtractIconEx(path, 0)
        if not large:
            return None
        hicon = large[0]
        hdc = win32ui.CreateDCFromHandle(win32gui.GetDC(0))
        hbmp = win32ui.CreateBitmap()
        hbmp.CreateCompatibleBitmap(hdc, 32, 32)
        hdc_mem = hdc.CreateCompatibleDC()
        hdc_mem.SelectObject(hbmp)
        hdc_mem.DrawIcon((0, 0), hicon)
        bmpstr = hbmp.GetBitmapBits(True)
        # BGRA → RGBA, win32 отдаёт в таком порядке
        img = Image.frombuffer('RGBA', (32, 32), bmpstr, 'raw', 'BGRA', 0, 1)
        win32gui.DestroyIcon(hicon)
        for h in large[1:]: win32gui.DestroyIcon(h)
        for h in small: win32gui.DestroyIcon(h)
        buf = io.BytesIO()
        img.save(buf, format="PNG")
        return buf.getvalue()
    except Exception as e:
        _log(f"Icon error: {e}")
        return None

cmd_queue = queue.Queue()

def hw_loop():
    pythoncom.CoInitialize()
    computer = None
    while True:
        try:
            if ohm_ok and computer is None:
                try:
                    computer = Computer()
                    computer.CPUEnabled = True
                    computer.MainboardEnabled = True
                    computer.GPUEnabled = True
                    computer.FanControllerEnabled = True
                    computer.Open()
                except Exception as e:
                    computer = None

            gpus = []
            try:
                for g in GPUtil.getGPUs():
                    gpus.append({
                        "name": g.name,
                        "load": round(g.load * 100),
                        "temp": g.temperature,
                        "mem_p": round(g.memoryUtil * 100)
                    })
                state["gpu"] = gpus
            except Exception as e:
                pass

            if computer:
                try:
                    fans = []
                    clocks = []
                    for hw in computer.Hardware:
                        hw.Update()
                        for item in [hw] + list(hw.SubHardware):
                            item.Update()
                            for s in item.Sensors:
                                name = str(s.Name)
                                val = s.Value
                                if val is None: continue
                                stype = str(s.SensorType)
                                if stype == 'Clock' and 'CPU Core #' in name:
                                    clocks.append(val)
                                if stype == 'Temperature' and 'CPU Package' in name:
                                    state["cpu_temp"] = round(val, 1)
                                if stype == 'Fan':
                                    fans.append({"name": f"{hw.Name} {name}", "rpm": int(val)})
                    if clocks:
                        state["cpu_freq"] = round(sum(clocks) / len(clocks))
                    state["fans"] = fans
                except Exception as e:
                    _log(f"[hw_loop] OHM update error: {e}")
                    try: computer.Close()
                    except: pass
                    computer = None
        except Exception as e:
            _log(f"[hw_loop] general error: {e}")
        time.sleep(2.0)

def _get_speaker_vol():
    """Хелпер: получить IAudioEndpointVolume для динамиков."""
    dev = AudioUtilities.GetSpeakers()
    if dev and hasattr(dev, 'Activate'):
        return cast(dev.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None), POINTER(IAudioEndpointVolume))
    return None

def _get_mic_vol():
    """Хелпер: получить IAudioEndpointVolume для микрофона."""
    dev = AudioUtilities.GetMicrophone()
    if dev and hasattr(dev, 'Activate'):
        return cast(dev.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None), POINTER(IAudioEndpointVolume))
    return None

def audio_loop():
    pythoncom.CoInitialize()
    while True:
        try:
            while not cmd_queue.empty():
                cmd = cmd_queue.get_nowait()
                action = cmd.get("action")

                if action in ("set_volume", "set_master_volume"):
                    vol = _get_speaker_vol()
                    if vol:
                        vol.SetMasterVolumeLevelScalar(float(cmd.get("vol", cmd.get("val", 0))) / 100.0, None)

                elif action in ("mute_mic", "set_mic_mute"):
                    mic = _get_mic_vol()
                    if mic:
                        raw = cmd.get("mute", 0)
                        mic.SetMute(1 if (raw == 1 or str(raw).lower() == "true") else 0, None)

                elif action in ("set_mixer", "set_mixer_volume"):
                    try:
                        sessions = AudioUtilities.GetAllSessions()
                        app = str(cmd.get("app", "")).lower()
                        vol_level = cmd.get("vol") if cmd.get("vol") is not None else cmd.get("val", 0)
                        _log(f"[mixer] ищу '{app}', target vol: {vol_level}")
                        found = False
                        for s in sessions:
                            if s.Process and s.Process.name():
                                pname = s.Process.name().lower()
                                if pname == app:
                                    ctl = s._ctl.QueryInterface(ISimpleAudioVolume)
                                    ctl.SetMasterVolume(float(vol_level) / 100.0, None)
                                    found = True
                                    _log(f"[mixer] ок: '{pname}' → {vol_level}")
                        if not found:
                            _log(f"[mixer] не нашёл сессию '{app}'")
                    except Exception as e:
                        _log(f"[mixer] ошибка: {e}")

                elif action in ("media_command", "media"):
                    mc = cmd.get("cmd")
                    if mc == 'play_pause': pyautogui.press('playpause')
                    elif mc == 'next': pyautogui.press('nexttrack')
                    elif mc == 'prev': pyautogui.press('prevtrack')

                elif action == "minimize_app":
                    hwnd = win32gui.GetForegroundWindow()
                    if hwnd:
                        win32gui.ShowWindow(hwnd, win32con.SW_MINIMIZE)

                elif action == "close_app":
                    target = cmd.get("name", "").lower()
                    for p in psutil.process_iter(['name']):
                        try:
                            if p.info['name'].lower() == target: p.kill()
                        except: pass

                elif action == "run":
                    run_path = cmd.get("path")
                    _log(f"[run] открываю: '{run_path}' (exists={os.path.exists(run_path) if run_path else '?'})")
                    try:
                        os.startfile(run_path)
                        _log("[run] ок")
                    except Exception as e:
                        _log(f"[run] упало: {e}")

                elif action == "key_press":
                    import ctypes
                    
                    VK_MAP = {
                        'ctrl': 0x11, 'alt': 0x12, 'shift': 0x10, 'win': 0x5B,
                        'up': 0x26, 'down': 0x28, 'left': 0x25, 'right': 0x27,
                        'enter': 0x0D, 'space': 0x20, 'tab': 0x09, 'escape': 0x1B,
                        'backspace': 0x08, 'delete': 0x2E, 'home': 0x24, 'end': 0x23,
                        'pageup': 0x21, 'pagedown': 0x22, 'insert': 0x2D, 'printscreen': 0x2C, 'pause': 0x13,
                        'volumeup': 0xAF, 'volumedown': 0xAE, 'volumemute': 0xAD,
                        'playpause': 0xB3, 'nexttrack': 0xB0, 'prevtrack': 0xB1
                    }
                    for c in range(26): VK_MAP[chr(ord('a') + c)] = 0x41 + c
                    for c in range(10): VK_MAP[str(c)] = 0x30 + c
                    for c in range(1, 13): VK_MAP[f'f{c}'] = 0x6F + c

                    keys = cmd.get("keys", [])
                    if keys:
                        _log(f"[keypress] {keys}")
                        try:
                            for k in keys:
                                vk = VK_MAP.get(k.lower())
                                if vk: ctypes.windll.user32.keybd_event(vk, 0, 0, 0)
                                
                            for k in reversed(keys):
                                vk = VK_MAP.get(k.lower())
                                if vk: ctypes.windll.user32.keybd_event(vk, 0, 2, 0)
                                
                            _log("[keypress] ok")
                        except Exception as e:
                            _log(f"[keypress] error: {e}")

                elif action == "shutdown":
                    _log("[power] shutdown")
                    os.system("shutdown /s /t 1")
                elif action == "sleep":
                    _log("[power] sleep")
                    os.system("rundll32.exe powrprof.dll,SetSuspendState 0,1,0")
                elif action == "restart":
                    _log("[power] restart")
                    os.system("shutdown /r /t 1")

                elif action == "get_icon":
                    try:
                        req_id = cmd.get("req_id", "")
                        icon = extract_icon(cmd.get("path", ""))
                        b64 = base64.b64encode(icon).decode('utf-8') if icon else ""
                        resp = json.dumps({"type": "icon_response", "req_id": req_id, "data": b64})
                        sys.stdout.write(resp + "\n")
                        sys.stdout.flush()
                    except Exception as e:
                        _log(f"[icon] action error: {e}")

            # --- опрос текущего состояния аудио ---
            try:
                vol = _get_speaker_vol()
                if vol:
                    state["volume"] = round(vol.GetMasterVolumeLevelScalar() * 100)
            except Exception as e:
                _log(f"[audio] speakers: {e}")

            try:
                mic = _get_mic_vol()
                if mic:
                    state["mic_muted"] = bool(mic.GetMute())
            except Exception as e:
                #на некоторых машинах GetMicrophone() вообще None — нужен fallback
                _log(f"[audio] mic: {e}")

            try:
                sessions = AudioUtilities.GetAllSessions()
                state["audio_sessions"] = [
                    {"name": s.Process.name(), "volume": round(s._ctl.QueryInterface(ISimpleAudioVolume).GetMasterVolume() * 100)}
                    for s in sessions if s.Process and s.Process.name()
                ]
            except Exception as e:
                _log(f"[audio] sessions: {e}")

            running = set()
            for p in psutil.process_iter(['name']):
                try: running.add(p.info['name'].lower())
                except: pass
            state["running_apps"] = list(running)

            hwnd = win32gui.GetForegroundWindow()
            if hwnd:
                _, pid = win32process.GetWindowThreadProcessId(hwnd)
                try:
                    state["active_app"] = psutil.Process(pid).name().lower()
                except: pass
            else:
                state["active_app"] = ""
        except Exception as e:
            sys.stderr.write(f"audio_loop error: {e}\n")
        time.sleep(0.5)

async def media_loop():
    pythoncom.CoInitialize()
    while True:
        try:
            mgr = await SessionManager.request_async()
            session = mgr.get_current_session()
            if session:
                props = await session.try_get_media_properties_async()
                info = session.get_playback_info()
                state["media"] = {"title": props.title, "artist": props.artist, "status": int(info.playback_status)}
            else:
                state["media"] = None
        except Exception as e:
            sys.stderr.write(f"media_loop error: {e}\n")
            state["media"] = None
        await asyncio.sleep(1.0)

def run_async_loop():
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    loop.run_until_complete(media_loop())

def emit_state():
    while True:
        try:
            if sys.stdout is None:
                sys.stdout = io.TextIOWrapper(os.fdopen(1, 'wb'), encoding='utf-8', errors='replace')
            sys.stdout.write(json.dumps(state) + "\n")
            sys.stdout.flush()
        except Exception as e:
            _log(f"[emit] stdout error: {e}")
        time.sleep(1.0)

def supervise_thread(name, target_func):
    while True:
        try:
            _log(f"[supervisor] Starting loop: {name}")
            target_func()
        except Exception as e:
            _log(f"[supervisor] Loop {name} crashed with error: {e}. Restarting in 2 seconds...")
            time.sleep(2.0)

def parent_watchdog():
    ppid = os.getppid()
    _log(f"[watchdog] Parent PID: {ppid}")
    while True:
        time.sleep(3.0)
        try:
            if not psutil.pid_exists(ppid):
                _log("[watchdog] Parent process died. Exiting worker...")
                os._exit(0)
        except Exception as e:
            pass

if __name__ == "__main__":
    with open(LOG, "w") as f: f.write("Worker started\n")

    for name, target in [("hw_loop", hw_loop), ("audio_loop", audio_loop), ("async_loop", run_async_loop), ("emit_state", emit_state), ("watchdog", parent_watchdog)]:
        threading.Thread(target=supervise_thread, args=(name, target), daemon=True).start()

    eof_count = 0
    while True:
        try:
            if sys.stdin is None:
                sys.stdin = io.TextIOWrapper(os.fdopen(0, 'rb'), encoding='utf-8', errors='replace')
            line = sys.stdin.readline()
            if not line:
                eof_count += 1
                if eof_count >= 10:
                    _log("[stdin] Stdin pipe closed (EOF). Exiting worker...")
                    os._exit(0)
                time.sleep(1)
                continue
            eof_count = 0
            _log(f"[stdin] {line.strip()}")
            cmd_queue.put(json.loads(line))
        except Exception as e:
            _log(f"[stdin] error: {e}")
            time.sleep(1)
