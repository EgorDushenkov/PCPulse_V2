import os
import sys
import subprocess
import socket
import time

def show_webview_window():
    import webview
    port = 5000
    for p in range(5000, 5010):
        try:
            with socket.create_connection(("127.0.0.1", p), timeout=0.2) as s:
                port = p
                break
        except (ConnectionRefusedError, OSError, socket.timeout):
            pass

    url = f"http://127.0.0.1:{port}/gui.html"
    window = webview.create_window(
        title="PC Pulse Server",
        url=url,
        width=460,
        height=540,
        resizable=False,
        background_color="#080a10"
    )
    webview.start()

def main():
    base_dir = getattr(sys, '_MEIPASS', os.path.dirname(os.path.abspath(__file__)))
    
    if getattr(sys, 'frozen', False):
        real_exe_path = os.path.abspath(sys.executable)
    else:
        real_exe_path = os.path.abspath(sys.argv[0])
    
    jre_path = os.path.join(base_dir, "jdk-21.0.3+9", "bin", "javaw.exe")
    jar_path = os.path.join(base_dir, "app.jar")
    
    if not os.path.exists(jre_path):
        import tkinter as tk
        from tkinter import messagebox
        root = tk.Tk()
        root.withdraw()
        messagebox.showerror("Error", "JRE not found in embedded package.")
        return

    # If --ui flag is passed, open the modern webview window directly
    if "--ui" in sys.argv:
        show_webview_window()
        return

    is_autostart = any(flag in sys.argv for flag in ("--autostart", "--silent", "--minimized", "-silent", "-minimized"))

    # Check if backend server is already running on port 49991
    try:
        with socket.create_connection(("127.0.0.1", 49991), timeout=0.5) as s:
            if not is_autostart:
                s.sendall(b"SHOW_UI\n")
        if not is_autostart:
            show_webview_window()
        return
    except (ConnectionRefusedError, OSError, socket.timeout):
        pass

    # Start Java Server in background with exact real executable path
    env = os.environ.copy()
    env["PCPULSE_EXE_PATH"] = real_exe_path
    
    proc = subprocess.Popen([jre_path, f"-Dpcpulse.exe.path={real_exe_path}", "-jar", jar_path], cwd=base_dir, env=env, creationflags=0x08000000)
    
    if not is_autostart:
        # Wait for WebServer to initialize
        time.sleep(1.5)
        
        # Open our modern Glassmorphism UI
        show_webview_window()

    # Keep launcher alive as long as Java server runs, so PyInstaller --onefile temp directory isn't deleted!
    proc.wait()

if __name__ == "__main__":
    main()
