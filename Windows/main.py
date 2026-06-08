import os
import sys
import socket
import winreg
import threading
import multiprocessing
import customtkinter as ctk
import pystray
from PIL import Image, ImageDraw
import uvicorn

# Импортируем твой сервер! Убедись, что файл называется server.py
import server 

# --- НАСТРОЙКИ ИНТЕРФЕЙСА ---
ctk.set_appearance_mode("Dark")  # Темная тема
ctk.set_default_color_theme("blue")

# Имя нашего приложения для реестра (Автозапуск)
APP_NAME = "PCPulseServer"

def get_local_ip():
    try:
        return socket.gethostbyname(socket.gethostname())
    except:
        return "127.0.0.1"

# --- ЛОГИКА АВТОЗАПУСКА (РЕЕСТР WINDOWS) ---
def get_exe_path():
    """Получает путь к текущему исполняемому файлу (.exe или .py)"""
    if getattr(sys, 'frozen', False):
        return sys.executable
    return os.path.abspath(__file__)

def is_autostart_enabled():
    try:
        key = winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run", 0, winreg.KEY_READ)
        value, _ = winreg.QueryValueEx(key, APP_NAME)
        winreg.CloseKey(key)
        return value == get_exe_path()
    except WindowsError:
        return False

def toggle_autostart(enable):
    try:
        key = winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run", 0, winreg.KEY_ALL_ACCESS)
        if enable:
            winreg.SetValueEx(key, APP_NAME, 0, winreg.REG_SZ, get_exe_path())
        else:
            winreg.DeleteValue(key, APP_NAME)
        winreg.CloseKey(key)
    except WindowsError as e:
        print(f"Ошибка изменения реестра: {e}")

# --- ФУНКЦИЯ ДЛЯ ЗАПУСКА СЕРВЕРА ---
def run_fastapi_server():
    # Запускаем uvicorn напрямую через импортированный объект app
    uvicorn.run(server.app, host="0.0.0.0", port=5000, log_level="error")

# --- ГЛАВНЫЙ КЛАСС ОКНА ---
class PCPulseApp(ctk.CTk):
    def __init__(self):
        super().__init__()

        self.title("PC Pulse Server")
        self.geometry("400x200")
        self.resizable(False, False)
        
        # Перехватываем нажатие на крестик
        self.protocol("WM_DELETE_WINDOW", self.hide_window)

        # Текст с IP
        ip_address = get_local_ip()
        self.label_title = ctk.CTkLabel(self, text="PC Pulse Активен", font=("Roboto", 20, "bold"))
        self.label_title.pack(pady=(20, 5))

        self.label_ip = ctk.CTkLabel(self, text=f"Введите этот IP в приложении:\n{ip_address}", font=("Roboto", 14))
        self.label_ip.pack(pady=(5, 20))

        # Переключатель автозапуска
        self.autostart_var = ctk.BooleanVar(value=is_autostart_enabled())
        self.switch = ctk.CTkSwitch(self, text="Запускать фоном при включении ПК", 
                                    variable=self.autostart_var, command=self.on_switch_toggle)
        self.switch.pack(pady=10)

        # Системный трей
        self.tray_icon = None

    def on_switch_toggle(self):
        toggle_autostart(self.autostart_var.get())

    # --- ЛОГИКА ТРЕЯ (ФОНОВЫЙ РЕЖИМ) ---
    def create_tray_image(self):
        # Создаем простую синюю иконку программно (чтобы не таскать файл .ico)
        image = Image.new('RGB', (64, 64), color=(30, 30, 30))
        draw = ImageDraw.Draw(image)
        draw.ellipse((16, 16, 48, 48), fill=(0, 120, 215))
        return image

    def hide_window(self):
        self.withdraw()  # Прячем окно с панели задач
        image = self.create_tray_image()
        menu = pystray.Menu(
            pystray.MenuItem("Развернуть", self.show_window),
            pystray.MenuItem("Выход", self.quit_app)
        )
        self.tray_icon = pystray.Icon("PCPulse", image, "PC Pulse Server", menu)
        # Запускаем трей в отдельном потоке
        threading.Thread(target=self.tray_icon.run, daemon=True).start()

    def show_window(self, icon, item):
        icon.stop()
        self.after(0, self.deiconify)  # Возвращаем окно

    def quit_app(self, icon, item):
        icon.stop()
        self.quit()
        sys.exit()

if __name__ == "__main__":
    # Обязательно для мультипроцессинга в скомпилированном EXE
    multiprocessing.freeze_support()

    # Запускаем сервер FastAPI как отдельный ПРОЦЕСС (защита от крашей)
    server_process = multiprocessing.Process(target=run_fastapi_server, daemon=True)
    server_process.start()

    # Запускаем красивый интерфейс
    app = PCPulseApp()
    app.mainloop()