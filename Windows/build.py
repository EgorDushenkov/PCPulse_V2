import os
import subprocess
import shutil
import urllib.request
import zipfile
import stat

JDK_URL = "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.3%2B9/OpenJDK21U-jdk_x64_windows_hotspot_21.0.3_9.zip"
MAVEN_URL = "https://archive.apache.org/dist/maven/maven-3/3.9.6/binaries/apache-maven-3.9.6-bin.zip"

JDK_DIR = "jdk-21.0.3+9"
MAVEN_DIR = "apache-maven-3.9.6"

def force_rm(func, path, _):
    os.chmod(path, stat.S_IWRITE)
    func(path)

def fetch_tool(url, dest, name):
    """Скачивает и распаковывает, если ещё нет"""
    if os.path.exists(os.path.join(dest, JDK_DIR)) or os.path.exists(os.path.join(dest, MAVEN_DIR)):
        return
    zip_path = os.path.join(dest, f"{name}.zip")
    print(f"Downloading {name}...")
    try:
        urllib.request.urlretrieve(url, zip_path)
        print(f"Extracting {name}...")
        with zipfile.ZipFile(zip_path, 'r') as z:
            z.extractall(dest)
        print(f"{name} ready.")
        os.remove(zip_path)
    except Exception as e:
        print(f"Failed to download {name}: {e}")

def run(cmd, **kwargs):
    print(f"Running: {' '.join(cmd)}")
    subprocess.run(cmd, check=True, **kwargs)

def main():
    root = os.path.abspath(".")
    fetch_tool(JDK_URL, root, "JDK")
    fetch_tool(MAVEN_URL, root, "Maven")

    mvn = os.path.join(root, MAVEN_DIR, "bin", "mvn.cmd")
    java_dir = os.path.join(root, "JavaServer")

    print("=== 1. Worker (Python -> EXE) ===")
    run(["python", "-m", "PyInstaller", "--noconfirm", "--onefile",
         "--add-data", "OpenHardwareMonitorLib.dll;.", "worker.py"], cwd=root)

    print("=== 2. Java Server (Maven -> fat JAR) ===")
    env = os.environ.copy()
    env["JAVA_HOME"] = os.path.join(root, JDK_DIR)
    run([mvn, "clean", "package"], cwd=java_dir, env=env)

    shutil.copy2(os.path.join(java_dir, "target", "pcpulse-server-1.0-SNAPSHOT.jar"), "app.jar")
    shutil.copy2(os.path.join(root, "dist", "worker.exe"), "worker.exe")

    print("=== 3. Launcher (JRE + JAR + Worker + GUI) ===")
    run(["python", "-m", "PyInstaller", "--noconfirm", "--onefile", "--windowed", "--icon=ioo.ico",
         "--add-data", f"{JDK_DIR};{JDK_DIR}",
         "--add-data", "app.jar;.",
         "--add-data", "worker.exe;.",
         "--add-data", "gui.html;.",
         "--hidden-import=webview",
         "--hidden-import=clr_loader",
         "--hidden-import=pythonnet",
         "--hidden-import=bottle",
         "--hidden-import=proxy_tools",
         "launcher.py"], cwd=root)

    print("=== Copying final executable ===")
    shutil.copy2(os.path.join(root, "dist", "launcher.exe"), "PC Pulse.exe")

    print("=== Cleanup ===")
    shutil.rmtree("dist", onerror=force_rm)
    shutil.rmtree("build", onerror=force_rm)
    for f in ("app.jar", "worker.exe", "launcher.spec", "worker.spec"):
        if os.path.exists(f): os.remove(f)

if __name__ == "__main__":
    main()
