import os
import subprocess
import shutil
import urllib.request
import zipfile
import stat

JDK_URL = "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.3%2B9/OpenJDK21U-jdk_x64_windows_hotspot_21.0.3_9.zip"
MAVEN_URL = "https://archive.apache.org/dist/maven/maven-3/3.9.6/binaries/apache-maven-3.9.6-bin.zip"

def rmtree_error(func, path, exc_info):
    os.chmod(path, stat.S_IWRITE)
    func(path)

def download_and_extract(url, extract_to, name):
    zip_path = os.path.join(extract_to, f"{name}.zip")
    if not os.path.exists(os.path.join(extract_to, name)) and not os.path.exists(os.path.join(extract_to, "jdk-21.0.3+9")) and not os.path.exists(os.path.join(extract_to, "apache-maven-3.9.6")):
        print(f"Downloading {name}...")
        try:
            urllib.request.urlretrieve(url, zip_path)
            print(f"Extracting {name}...")
            with zipfile.ZipFile(zip_path, 'r') as zip_ref:
                zip_ref.extractall(extract_to)
            print(f"{name} ready.")
            os.remove(zip_path)
        except Exception as e:
            print(f"Failed to download {name}: {e}")

def run_cmd(cmd, cwd=None, env=None):
    print(f"Running: {' '.join(cmd)}")
    subprocess.run(cmd, cwd=cwd, env=env, check=True)

def main():
    base_dir = os.path.abspath(".")
    download_and_extract(JDK_URL, base_dir, "JDK")
    download_and_extract(MAVEN_URL, base_dir, "Maven")
    
    java_server_dir = os.path.join(base_dir, "JavaServer")
    maven_bin = os.path.join(base_dir, "apache-maven-3.9.6", "bin", "mvn.cmd")
    
    print("=== 1. Building Worker (Python to EXE) ===")
    run_cmd(["python", "-m", "PyInstaller", "--noconfirm", "--onefile", 
             "--add-data", "OpenHardwareMonitorLib.dll;.", "worker.py"], cwd=base_dir)
    
    print("=== 2. Building Java Server (Maven to fat JAR) ===")
    env = os.environ.copy()
    env["JAVA_HOME"] = os.path.join(base_dir, "jdk-21.0.3+9")
    run_cmd([maven_bin, "clean", "package"], cwd=java_server_dir, env=env)
    
    shutil.copy2(os.path.join(java_server_dir, "target", "pcpulse-server-1.0-SNAPSHOT.jar"), "app.jar")
    shutil.copy2(os.path.join(base_dir, "dist", "worker.exe"), "worker.exe")
    
    print("=== 3. Building Launcher (Bundling JRE + JAR + Worker) ===")
    run_cmd(["python", "-m", "PyInstaller", "--noconfirm", "--onefile", "--windowed", "--icon=ioo.ico",
             "--add-data", "jdk-21.0.3+9;jdk-21.0.3+9",
             "--add-data", "app.jar;.",
             "--add-data", "worker.exe;.",
             "launcher.py"], cwd=base_dir)
    
    print("=== Copying final executable ===")
    shutil.copy2(os.path.join(base_dir, "dist", "launcher.exe"), "PC Pulse.exe")

    print("=== Cleaning up ===")
    shutil.rmtree("dist", onerror=rmtree_error)
    shutil.rmtree("build", onerror=rmtree_error)
    for f in ["app.jar", "worker.exe", "launcher.spec", "worker.spec"]:
        if os.path.exists(f): os.remove(f)

if __name__ == "__main__":
    main()
