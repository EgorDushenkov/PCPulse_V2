import asyncio
import json
import os
import platform
import socket
import threading
import time
import io
import sys
import psutil
import GPUtil
import wmi
import pythoncom
import winreg
import pyautogui
import queue
import win32gui
import win32process
import win32con
import win32ui
from PIL import Image


from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import StreamingResponse
from fastapi.middleware.cors import CORSMiddleware
from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume, ISimpleAudioVolume
from ctypes import cast, POINTER
from comtypes import CLSCTX_ALL
from winsdk.windows.media.control import GlobalSystemMediaTransportControlsSessionManager as SessionManager
import clr

def get_dll_path():
    if getattr(sys, 'frozen', False):
        base_path = sys._MEIPASS
    else:
        base_path = os.path.dirname(os.path.abspath(__file__))
    return os.path.join(base_path, "OpenHardwareMonitorLib.dll")

try:
    clr.AddReference(get_dll_path())
    from OpenHardwareMonitor.Hardware import Computer
    ohm_available = True
    print("[INFO] Библиотека датчиков успешно загружена!")
except Exception as e:
    ohm_available = False
    print(f"[ERROR] Не удалось загрузить OpenHardwareMonitorLib.dll: {e}")
    print("[!] Убедитесь, что файл лежит в папке с сервером.")
audio_queue = queue.Queue()
app = FastAPI()
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

pc_stats = {
    "pc_name": platform.node(),
    "local_ip": socket.gethostbyname(socket.gethostname()),
    "time": "",
    "uptime": 0,
    "status": "online",
    "cpu": {"name": "Unknown", "usage": 0, "freq": 0, "temp": "N/A", "cores": psutil.cpu_count(logical=False)},
    "gpu": [],
    "ram": {"usage": 0, "total": 0, "used": 0, "free": 0},
    "disks": [],
    "network": {"down_kbps": 0, "up_kbps": 0},
    "fans": [],
    "volume": 0,
    "procs": [],
    "audio_sessions": [],
    "media": None,
    "mic_muted": False,
    "active_app": "",  
    "running_apps": []
}

try:
    key = winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"HARDWARE\DESCRIPTION\System\CentralProcessor\0")
    pc_stats["cpu"]["name"], _ = winreg.QueryValueEx(key, "ProcessorNameString")
except:
    pc_stats["cpu"]["name"] = platform.processor()



def fast_audio_thread():
    pythoncom.CoInitialize()
    while True:
        while not audio_queue.empty():
            try:
                data = audio_queue.get_nowait()
                action = data.get("action")
                
                if action in ["set_volume", "set_master_volume"]:
                    vol_ctl = cast(AudioUtilities.GetSpeakers().Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None), POINTER(IAudioEndpointVolume))
                    volume_level = data.get("vol") if data.get("vol") is not None else data.get("val", 0)
                    vol_ctl.SetMasterVolumeLevelScalar(float(volume_level) / 100.0, None)
                    del vol_ctl 

                elif action in ["mute_mic", "set_mic_mute"]:
                    mic_ctl = cast(AudioUtilities.GetMicrophone().Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None), POINTER(IAudioEndpointVolume))
                    mute_val = data.get("mute", 0)
                    is_muted = 1 if (mute_val == 1 or str(mute_val).lower() == "true") else 0
                    mic_ctl.SetMute(is_muted, None)
                    del mic_ctl

                elif action in ["set_mixer", "set_mixer_volume"]:
                    sessions = AudioUtilities.GetAllSessions()
                    app_name = str(data.get("app", "")).lower()
                    volume_level = data.get("vol") if data.get("vol") is not None else data.get("val", 0)
                    for s in sessions:
                        if s.Process and s.Process.name().lower() == app_name:
                            v_ctl = s._ctl.QueryInterface(ISimpleAudioVolume)
                            v_ctl.SetMasterVolume(float(volume_level) / 100.0, None)
                            del v_ctl
                    del sessions
            except Exception as e:
                print(f"[ERROR] Ошибка в очереди аудио: {e}")

        try:
            devices = AudioUtilities.GetSpeakers()
            if devices and hasattr(devices, 'Activate'):
                vol_obj = cast(devices.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None), POINTER(IAudioEndpointVolume))
                pc_stats["volume"] = round(vol_obj.GetMasterVolumeLevelScalar() * 100)
                del vol_obj

            mic_devs = AudioUtilities.GetMicrophone()
            if mic_devs and hasattr(mic_devs, 'Activate'):
                m_vol = cast(mic_devs.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None), POINTER(IAudioEndpointVolume))
                pc_stats["mic_muted"] = bool(m_vol.GetMute())
                del m_vol

            sessions = AudioUtilities.GetAllSessions()
            new_sessions = []
            for s in sessions:
                if s.Process and s.Process.name():
                    v_ctl = s._ctl.QueryInterface(ISimpleAudioVolume)
                    new_sessions.append({"name": s.Process.name(), "volume": round(v_ctl.GetMasterVolume() * 100)})
                    del v_ctl
            pc_stats["audio_sessions"] = new_sessions
            del sessions
        except: pass
        
        time.sleep(0.1)

def slow_hw_thread():
    pythoncom.CoInitialize()
    
    if ohm_available:
        computer = Computer()
        computer.CPUEnabled = True
        computer.MainboardEnabled = True
        computer.FanControllerEnabled = True
        try:
            computer.Open()
        except:
            computer = None
    else:
        computer = None

    while True:
        try:
            new_gpu = []
            for g in GPUtil.getGPUs():
                new_gpu.append({"name": g.name, "load": round(g.load * 100), "temp": g.temperature, "mem_p": round(g.memoryUtil * 100)})
            pc_stats["gpu"] = new_gpu
            
            new_disks = []
            for p in psutil.disk_partitions():
                if 'fixed' in p.opts:
                    try:
                        u = psutil.disk_usage(p.mountpoint)
                        new_disks.append({"dev": p.device, "total": round(u.total/(1024**3), 1), "percent": u.percent})
                    except: pass
            pc_stats["disks"] = new_disks
            
            if computer:
                new_fans = []
                for hw in computer.Hardware:
                    hw.Update()
                    for sensor in hw.Sensors:
                        name = str(sensor.Name)
                        val = sensor.Value
                        if val is None: continue
                        
                        stype = str(sensor.SensorType)
                        if stype == 'Temperature' and 'CPU Package' in name: 
                            pc_stats["cpu"]["temp"] = round(val, 1)
                        if stype == 'Clock' and 'CPU Core' in name: 
                            pc_stats["cpu"]["freq"] = round(val)
                        if stype == 'Fan': 
                            new_fans.append({"name": name, "rpm": int(val)})
                pc_stats["fans"] = new_fans
        except Exception as e: 
            pass
            
        time.sleep(3.0)
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
        print(f"Icon error: {e}")
        return None

async def update_medium_stats():
    last_net = psutil.net_io_counters()
    last_time = time.time()
    while True:
        
        try:
            running = []
            for p in psutil.process_iter(['name']):
                try: running.append(p.info['name'].lower())
                except: pass
                pc_stats["running_apps"] = list(set(running))

                hwnd = win32gui.GetForegroundWindow()
                if hwnd:
                    _, pid = win32process.GetWindowThreadProcessId(hwnd)
                    pc_stats["active_app"] = psutil.Process(pid).name().lower()
                else:
                    pc_stats["active_app"] = ""
            curr_net = psutil.net_io_counters()
            curr_time = time.time()
            dt = curr_time - last_time
            if dt > 0:
                pc_stats["network"]["down_kbps"] = round(((curr_net.bytes_recv - last_net.bytes_recv) * 8 / 1024) / dt, 1)
                pc_stats["network"]["up_kbps"] = round(((curr_net.bytes_sent - last_net.bytes_sent) * 8 / 1024) / dt, 1)
            last_net, last_time = curr_net, curr_time
            
            pc_stats["cpu"]["usage"] = psutil.cpu_percent()
            pc_stats["time"] = time.strftime("%H:%M:%S")
            pc_stats["uptime"] = round((time.time() - psutil.boot_time()) / 3600, 1)
            
            ram = psutil.virtual_memory()
            pc_stats["ram"] = {"usage": ram.percent, "total": round(ram.total/(1024**3), 1), "used": round(ram.used/(1024**3), 1), "free": round(ram.available/(1024**3), 1)}
            
            procs = []
            for p in psutil.process_iter(['pid', 'name', 'cpu_percent']):
                if p.info['name'] != "System Idle Process" and p.info['cpu_percent'] is not None:
                    procs.append({"pid": p.info['pid'], "name": p.info['name'], "cpu": round(p.info['cpu_percent']/psutil.cpu_count(), 1)})
            pc_stats["procs"] = sorted(procs, key=lambda x: x["cpu"], reverse=True)[:5]

        except: pass
        await asyncio.sleep(0.5)




async def update_media_stats():
    while True:
        try:
            sessions = await SessionManager.request_async()
            current_session = sessions.get_current_session()
            if current_session:
                props = await current_session.try_get_media_properties_async()
                info = current_session.get_playback_info()
                pc_stats["media"] = {"title": props.title, "artist": props.artist, "status": int(info.playback_status)}
            else: pc_stats["media"] = None
        except: pc_stats["media"] = None
        await asyncio.sleep(1.0)



def _execute_non_com_cmd(data):
    action = data.get("action")
    print(f"[DEBUG] Исполняю системную команду: {action}")
    print(f"[DEBUG] Системная команда: {data}")
    try:
        if action in ["media", "media_command"]:
            cmd = data.get("cmd")
            if cmd == 'play_pause': pyautogui.press('playpause')
            elif cmd == 'next': pyautogui.press('nexttrack')
            elif cmd == 'prev': pyautogui.press('prevtrack')
        elif action in ["kill_process", "kill"]:
            pid = int(data.get("pid", 0))
            if pid > 0: psutil.Process(pid).kill()
        elif action == "shutdown": os.system("shutdown /s /t 1")
        elif action == "sleep": os.system("rundll32.exe powrprof.dll,SetSuspendState 0,1,0")
        elif action == "run":
            os.startfile(data.get("path"))
            time.sleep(0.3) 
            refresh_app_stats()
            print("[DEBUG] Статусы обновлены после запуска")
        elif action == "minimize_app":
            hwnd = win32gui.GetForegroundWindow()
            if hwnd:
                print(f"[DEBUG] Сворачиваю окно HWND: {hwnd}")
                try:
                    win32gui.ShowWindow(hwnd, win32con.SW_MINIMIZE)
                    refresh_app_stats() 
                    print("[DEBUG] Статусы обновлены после сворачивания")
                    taskbar_hwnd = win32gui.FindWindow("Shell_TrayWnd", None)
                    if taskbar_hwnd:
                        win32gui.SetForegroundWindow(taskbar_hwnd)
                    
                    pc_stats["active_app"] = ""
                    print("[DEBUG] Фокус успешно переведен на Taskbar")
                except Exception as e:
                    pc_stats["active_app"] = ""
                    print(f"[DEBUG] Не удалось программно сменить фокус, но статус обнулен: {e}")
        elif action == "close_app":
            app_name = data.get("name", "").lower()
            for p in psutil.process_iter(['name']):
                try:
                    if p.info['name'].lower() == app_name:
                        p.kill()
                except: pass
            time.sleep(0.1) 
            refresh_app_stats()
            print("[DEBUG] Статусы обновлены после закрытия")
    except Exception as e:
        print(f"[ERROR] Ошибка выполнения команды '{action}': {e}")

def refresh_app_stats():
    """Мгновенно обновляет данные об активном и запущенном софте в общем словаре"""
    try:
        running = []
        for p in psutil.process_iter(['name']):
            try: running.append(p.info['name'].lower())
            except: pass
        pc_stats["running_apps"] = list(set(running))

        hwnd = win32gui.GetForegroundWindow()
        if hwnd:
            _, pid = win32process.GetWindowThreadProcessId(hwnd)
            pc_stats["active_app"] = psutil.Process(pid).name().lower()
        else:
            pc_stats["active_app"] = ""
    except:
        pc_stats["active_app"] = ""

async def handle_command(data):
    print(f"\n[WS INCOMING] Пришла команда: {data}")
    action = data.get("action")
    if action in ["set_volume", "set_master_volume", "mute_mic", "set_mic_mute", "set_mixer", "set_mixer_volume"]:
        audio_queue.put(data)
    else:
        await asyncio.to_thread(_execute_non_com_cmd, data)

@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    await websocket.accept()
    try:
        async def read_commands():
            try:
                while True:
                    msg = await websocket.receive_text()
                    await handle_command(json.loads(msg))
            except: pass

        asyncio.create_task(read_commands())

        while True:
            try:
                await websocket.send_json(pc_stats)
            except RuntimeError:
                break
            await asyncio.sleep(0.2)
    except WebSocketDisconnect:
        pass
@app.get("/icon")
async def get_icon(path: str):
    icon_bytes = await asyncio.to_thread(get_icon_as_base64, path)
    if icon_bytes:
        return StreamingResponse(io.BytesIO(icon_bytes), media_type="image/png")
    return {"error": "not found"}

@app.get("/screenshot")
async def screenshot():
    img = pyautogui.screenshot().convert('RGB')
    buf = io.BytesIO()
    img.save(buf, format='JPEG', quality=70)
    buf.seek(0)
    return StreamingResponse(buf, media_type="image/jpeg")

@app.on_event("startup")
async def startup_event():
    threading.Thread(target=fast_audio_thread, daemon=True).start()
    threading.Thread(target=slow_hw_thread, daemon=True).start()
    
    asyncio.create_task(update_medium_stats())
    asyncio.create_task(update_media_stats())

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=5000)