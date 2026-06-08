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

# Force UTF-8 encoding for pipe communication with Java server
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

def get_dll_path():
    if getattr(sys, 'frozen', False):
        base_path = sys._MEIPASS
    else:
        base_path = os.path.dirname(os.path.abspath(__file__))
    return os.path.join(base_path, "OpenHardwareMonitorLib.dll")

ohm_available = False
try:
    clr.AddReference(get_dll_path())
    # pyrefly: ignore [missing-import]
    from OpenHardwareMonitor.Hardware import Computer
    ohm_available = True
except Exception as e:
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

def get_icon_as_base64(path):
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
        bmpinfo = hbmp.GetInfo()
        bmpstr = hbmp.GetBitmapBits(True)
        img = Image.frombuffer('RGBA', (32, 32), bmpstr, 'raw', 'BGRA', 0, 1)
        win32gui.DestroyIcon(hicon)
        for h in large[1:]: win32gui.DestroyIcon(h)
        for h in small: win32gui.DestroyIcon(h)
        buf = io.BytesIO()
        img.save(buf, format="PNG")
        return buf.getvalue()
    except Exception as e:
        with open("worker_debug.log", "a") as f: f.write(f"Icon error: {e}\n")
        return None

cmd_queue = queue.Queue()

def hw_thread():
    pythoncom.CoInitialize()
    computer = None
    if ohm_available:
        try:
            computer = Computer()
            computer.CPUEnabled = True
            computer.MainboardEnabled = True
            computer.GPUEnabled = True
            computer.FanControllerEnabled = True
            computer.Open()
        except:
            computer = None

    while True:
        try:
            new_gpu = []
            for g in GPUtil.getGPUs():
                new_gpu.append({
                    "name": g.name,
                    "load": round(g.load * 100),
                    "temp": g.temperature,
                    "mem_p": round(g.memoryUtil * 100)
                })
            state["gpu"] = new_gpu

            if computer:
                new_fans = []
                cpu_clocks = []
                for hw in computer.Hardware:
                    hw.Update()
                    for item in [hw] + list(hw.SubHardware):
                        item.Update()
                        for sensor in item.Sensors:
                            name = str(sensor.Name)
                            val = sensor.Value
                            if val is None: continue
                            stype = str(sensor.SensorType)
                            if stype == 'Clock' and 'CPU Core #' in name:
                                cpu_clocks.append(val)
                            if stype == 'Temperature' and 'CPU Package' in name:
                                state["cpu_temp"] = round(val, 1)
                            if stype == 'Fan':
                                new_fans.append({"name": f"{hw.Name} {name}", "rpm": int(val)})
                if cpu_clocks:
                    state["cpu_freq"] = round(sum(cpu_clocks) / len(cpu_clocks))
                state["fans"] = new_fans
        except:
            pass
        time.sleep(2.0)

def audio_app_thread():
    pythoncom.CoInitialize()
    while True:
        try:
            while not cmd_queue.empty():
                cmd = cmd_queue.get_nowait()
                action = cmd.get("action")
                if action in ["set_volume", "set_master_volume"]:
                    devs = AudioUtilities.GetSpeakers()
                    if devs and hasattr(devs, 'Activate'):
                        vol_ctl = cast(devs.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None), POINTER(IAudioEndpointVolume))
                        vol_ctl.SetMasterVolumeLevelScalar(float(cmd.get("vol", cmd.get("val", 0))) / 100.0, None)
                elif action in ["mute_mic", "set_mic_mute"]:
                    m_devs = AudioUtilities.GetMicrophone()
                    if m_devs and hasattr(m_devs, 'Activate'):
                        mic_ctl = cast(m_devs.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None), POINTER(IAudioEndpointVolume))
                        mute_val = cmd.get("mute", 0)
                        is_muted = 1 if (mute_val == 1 or str(mute_val).lower() == "true") else 0
                        mic_ctl.SetMute(is_muted, None)
                elif action in ["set_mixer", "set_mixer_volume"]:
                    try:
                        sessions = AudioUtilities.GetAllSessions()
                        app_name = str(cmd.get("app", "")).lower()
                        volume_level = cmd.get("vol") if cmd.get("vol") is not None else cmd.get("val", 0)
                        with open("worker_debug.log", "a") as f:
                            f.write(f"[set_mixer] Looking for app: '{app_name}', target vol: {volume_level}\n")
                            f.write(f"[set_mixer] Available sessions: {[s.Process.name() if s.Process else 'None' for s in sessions]}\n")
                        found = False
                        for s in sessions:
                            if s.Process and s.Process.name():
                                proc_name = s.Process.name().lower()
                                if proc_name == app_name:
                                    v_ctl = s._ctl.QueryInterface(ISimpleAudioVolume)
                                    v_ctl.SetMasterVolume(float(volume_level) / 100.0, None)
                                    found = True
                                    with open("worker_debug.log", "a") as f:
                                        f.write(f"[set_mixer] SUCCESS: Set '{proc_name}' volume to {volume_level}\n")
                        if not found:
                            with open("worker_debug.log", "a") as f:
                                f.write(f"[set_mixer] FAILED: No session matched '{app_name}'\n")
                    except Exception as e:
                        with open("worker_debug.log", "a") as f: f.write(f"Set mixer error: {e}\n")
                elif action == "media_command" or action == "media":
                    mc = cmd.get("cmd")
                    if mc == 'play_pause': pyautogui.press('playpause')
                    elif mc == 'next': pyautogui.press('nexttrack')
                    elif mc == 'prev': pyautogui.press('prevtrack')
                elif action == "minimize_app":
                    hwnd = win32gui.GetForegroundWindow()
                    if hwnd:
                        win32gui.ShowWindow(hwnd, win32con.SW_MINIMIZE)
                elif action == "close_app":
                    app_name = cmd.get("name", "").lower()
                    for p in psutil.process_iter(['name']):
                        try:
                            if p.info['name'].lower() == app_name: p.kill()
                        except: pass
                elif action == "run":
                    run_path = cmd.get("path")
                    with open("worker_debug.log", "a") as f:
                        f.write(f"[run] Attempting to open: '{run_path}'\n")
                        f.write(f"[run] Path exists: {os.path.exists(run_path) if run_path else 'path is None'}\n")
                    try:
                        os.startfile(run_path)
                        with open("worker_debug.log", "a") as f:
                            f.write(f"[run] SUCCESS: startfile called\n")
                    except Exception as e:
                        with open("worker_debug.log", "a") as f:
                            f.write(f"[run] ERROR: {e}\n")
                elif action == "shutdown":
                    with open("worker_debug.log", "a") as f: f.write("[power] Shutdown command received\n")
                    os.system("shutdown /s /t 1")
                elif action == "sleep":
                    with open("worker_debug.log", "a") as f: f.write("[power] Sleep command received\n")
                    os.system("rundll32.exe powrprof.dll,SetSuspendState 0,1,0")
                elif action == "restart":
                    with open("worker_debug.log", "a") as f: f.write("[power] Restart command received\n")
                    os.system("shutdown /r /t 1")
                elif action == "get_icon":
                    try:
                        icon_path = cmd.get("path", "")
                        req_id = cmd.get("req_id", "")
                        icon_bytes = get_icon_as_base64(icon_path)
                        if icon_bytes:
                            b64 = base64.b64encode(icon_bytes).decode('utf-8')
                            resp = json.dumps({"type": "icon_response", "req_id": req_id, "data": b64})
                            sys.stdout.write(resp + "\n")
                            sys.stdout.flush()
                        else:
                            resp = json.dumps({"type": "icon_response", "req_id": req_id, "data": ""})
                            sys.stdout.write(resp + "\n")
                            sys.stdout.flush()
                    except Exception as e:
                        with open("worker_debug.log", "a") as f: f.write(f"Icon action error: {e}\n")

            try:
                devices = AudioUtilities.GetSpeakers()
                if devices and hasattr(devices, 'Activate'):
                    vol_obj = cast(devices.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None), POINTER(IAudioEndpointVolume))
                    state["volume"] = round(vol_obj.GetMasterVolumeLevelScalar() * 100)
            except Exception as e:
                with open("worker_debug.log", "a") as f: f.write(f"Speakers error: {e}\n")
            
            try:
                mic_devs = AudioUtilities.GetMicrophone()
                if mic_devs and hasattr(mic_devs, 'Activate'):
                    m_vol = cast(mic_devs.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None), POINTER(IAudioEndpointVolume))
                    state["mic_muted"] = bool(m_vol.GetMute())
            except Exception as e:
                with open("worker_debug.log", "a") as f: f.write(f"Mic error: {e}\n")

            try:
                sessions = AudioUtilities.GetAllSessions()
                new_sessions = []
                for s in sessions:
                    if s.Process and s.Process.name():
                        v_ctl = s._ctl.QueryInterface(ISimpleAudioVolume)
                        new_sessions.append({"name": s.Process.name(), "volume": round(v_ctl.GetMasterVolume() * 100)})
                state["audio_sessions"] = new_sessions
            except Exception as se:
                with open("worker_debug.log", "a") as f: f.write(f"Sessions error: {se}\n")

            running = []
            for p in psutil.process_iter(['name']):
                try: running.append(p.info['name'].lower())
                except: pass
            state["running_apps"] = list(set(running))

            hwnd = win32gui.GetForegroundWindow()
            if hwnd:
                _, pid = win32process.GetWindowThreadProcessId(hwnd)
                try:
                    state["active_app"] = psutil.Process(pid).name().lower()
                except: pass
            else:
                state["active_app"] = ""
        except Exception as e:
            sys.stderr.write(f"Error in audio thread: {e}\n")
        time.sleep(0.5)

async def media_thread():
    pythoncom.CoInitialize()
    while True:
        try:
            sessions = await SessionManager.request_async()
            current_session = sessions.get_current_session()
            if current_session:
                props = await current_session.try_get_media_properties_async()
                info = current_session.get_playback_info()
                state["media"] = {"title": props.title, "artist": props.artist, "status": int(info.playback_status)}
            else:
                state["media"] = None
        except Exception as e:
            sys.stderr.write(f"Error in media thread: {e}\n")
            state["media"] = None
        await asyncio.sleep(1.0)

def start_async_loop():
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    loop.run_until_complete(media_thread())

def print_state_loop():
    while True:
        try:
            if sys.stdout is None:
                sys.stdout = io.TextIOWrapper(os.fdopen(1, 'wb'), encoding='utf-8', errors='replace')
            sys.stdout.write(json.dumps(state) + "\n")
            sys.stdout.flush()
        except Exception as e:
            with open("worker_debug.log", "a") as f: f.write(f"Stdout error: {e}\n")
        time.sleep(1.0)

if __name__ == "__main__":
    with open("worker_debug.log", "w") as f: f.write("Worker started\n")
    threading.Thread(target=hw_thread, daemon=True).start()
    threading.Thread(target=audio_app_thread, daemon=True).start()
    threading.Thread(target=start_async_loop, daemon=True).start()
    threading.Thread(target=print_state_loop, daemon=True).start()

    while True:
        try:
            if sys.stdin is None:
                sys.stdin = io.TextIOWrapper(os.fdopen(0, 'rb'), encoding='utf-8', errors='replace')
            line = sys.stdin.readline()
            if not line:
                time.sleep(1)
                continue
            with open("worker_debug.log", "a") as f: f.write(f"[stdin] Raw command: {line.strip()}\n")
            cmd = json.loads(line)
            cmd_queue.put(cmd)
        except Exception as e:
            with open("worker_debug.log", "a") as f: f.write(f"Stdin error: {e}\n")
            time.sleep(1)
