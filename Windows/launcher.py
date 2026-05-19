import os
import sys
import subprocess

def main():
    base_dir = getattr(sys, '_MEIPASS', os.path.dirname(os.path.abspath(__file__)))
    
    jre_path = os.path.join(base_dir, "jdk-21.0.3+9", "bin", "javaw.exe")
    jar_path = os.path.join(base_dir, "app.jar")
    worker_path = os.path.join(base_dir, "worker.exe")
    
    if not os.path.exists(jre_path):
        import tkinter as tk
        from tkinter import messagebox
        root = tk.Tk()
        root.withdraw()
        messagebox.showerror("Error", "JRE not found in embedded package.")
        return

    subprocess.run([jre_path, "-jar", jar_path], cwd=base_dir, creationflags=0x08000000)

if __name__ == "__main__":
    main()
