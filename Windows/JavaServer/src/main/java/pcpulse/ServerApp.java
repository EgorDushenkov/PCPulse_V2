package pcpulse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import pcpulse.auth.AuthManager;
import pcpulse.gui.TrayAndGUI;
import pcpulse.network.WebServer;
import pcpulse.system.SystemMonitor;
import pcpulse.worker.WorkerManager;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ServerApp {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static volatile TrayAndGUI instanceGui;

    public static void main(String[] args) {
        try {
            java.net.ServerSocket lockSocket = new java.net.ServerSocket(49991, 10, java.net.InetAddress.getByName("127.0.0.1"));
            Thread lockThread = new Thread(() -> {
                while (true) {
                    try (java.net.Socket s = lockSocket.accept();
                         java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream()))) {
                        String line = br.readLine();
                        if ("SHOW_UI".equals(line) && instanceGui != null) {
                            instanceGui.showWindow();
                        }
                    } catch (Exception ignored) {}
                }
            }, "SingleInstanceListener");
            lockThread.setDaemon(true);
            lockThread.start();
        } catch (java.io.IOException e) {
            try (java.net.Socket s = new java.net.Socket("127.0.0.1", 49991);
                 java.io.OutputStream os = s.getOutputStream()) {
                os.write("SHOW_UI\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                os.flush();
            } catch (Exception ignored) {}
            System.exit(0);
            return;
        }

        boolean autostart = java.util.prefs.Preferences.userNodeForPackage(TrayAndGUI.class).getBoolean("autostart_enabled", false);
        if (autostart) {
            updateAutostartRegistry(true);
        }

        SystemMonitor monitor = new SystemMonitor();
        WorkerManager worker = new WorkerManager();
        AuthManager auth = new AuthManager();

        worker.start();

        WebServer server = new WebServer(worker, auth, monitor);

        TrayAndGUI gui = new TrayAndGUI(
            monitor.getLocalIp(),
            auth,
            worker::stop,
            server::disconnectUnauthorized
        );
        instanceGui = gui;
        gui.init();

        int actualPort = server.start(5000);
        if (actualPort != 5000 && actualPort > 0) {
            gui.updateIp(monitor.getLocalIp() + ":" + actualPort);
        }

        // раз в 500мс собираем состояние и раздаём по вебсокетам
        Executors.newScheduledThreadPool(1).scheduleAtFixedRate(() -> {
            try {
                ObjectNode ws = worker.getLatestState();
                ObjectNode full = monitor.buildFullState(ws);
                server.broadcast(mapper.writeValueAsString(full));

                String currentIp = monitor.getLocalIp();
                if (actualPort != 5000 && actualPort > 0) {
                    gui.updateIp(currentIp + ":" + actualPort);
                } else {
                    gui.updateIp(currentIp);
                }
            } catch (Throwable t) {
                System.err.println("[ServerApp] Loop error: " + t.getMessage());
            }
        }, 0, 500, TimeUnit.MILLISECONDS);
    }

    public static String getRealExePath() {
        String path = System.getProperty("pcpulse.exe.path");
        if (path == null || path.trim().isEmpty()) {
            path = System.getenv("PCPULSE_EXE_PATH");
        }
        if (path == null || path.trim().isEmpty()) {
            java.io.File pcPulse = new java.io.File("PC Pulse.exe");
            if (pcPulse.exists()) {
                path = pcPulse.getAbsolutePath();
            } else {
                java.io.File launcher = new java.io.File("launcher.exe");
                if (launcher.exists()) {
                    path = launcher.getAbsolutePath();
                } else {
                    path = "PC Pulse.exe";
                }
            }
        }
        return path;
    }

    public static void updateAutostartRegistry(boolean enabled) {
        try {
            String exePath = getRealExePath();
            String psCmd;
            if (enabled) {
                psCmd = "$rawPath = '" + exePath.replace("'", "''") + "'; " +
                        "$path = [char]34 + $rawPath + [char]34; " +
                        "$dir = Split-Path $rawPath; if (-not $dir) { $dir = $pwd.Path }; " +
                        "$val = $path + ' --autostart'; " +
                        "try { New-ItemProperty -Path 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Run' -Name 'PCPulseServer' -Value $val -PropertyType String -Force -ErrorAction SilentlyContinue } catch {}; " +
                        "try { $wsh = New-Object -ComObject WScript.Shell; $lnk = $wsh.CreateShortcut($env:APPDATA + '\\Microsoft\\Windows\\Start Menu\\Programs\\Startup\\PCPulseServer.lnk'); $lnk.TargetPath = $rawPath; $lnk.Arguments = '--autostart'; $lnk.WorkingDirectory = $dir; $lnk.Save(); } catch {}; " +
                        "try { $action = New-ScheduledTaskAction -Execute $rawPath -Argument '--autostart' -WorkingDirectory $dir; $trigger = New-ScheduledTaskTrigger -AtLogOn; Register-ScheduledTask -TaskName 'PCPulseServer' -Action $action -Trigger $trigger -RunLevel Highest -Force -ErrorAction SilentlyContinue; } catch {}; " +
                        "exit 0";
                System.out.println("[Autostart] Enabling for: " + exePath);
            } else {
                psCmd = "try { Remove-ItemProperty -Path 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Run' -Name 'PCPulseServer' -ErrorAction SilentlyContinue } catch {}; " +
                        "try { Remove-Item ($env:APPDATA + '\\Microsoft\\Windows\\Start Menu\\Programs\\Startup\\PCPulseServer.lnk') -ErrorAction SilentlyContinue } catch {}; " +
                        "try { Unregister-ScheduledTask -TaskName 'PCPulseServer' -Confirm:$false -ErrorAction SilentlyContinue } catch {}; " +
                        "exit 0";
                System.out.println("[Autostart] Disabling");
            }
            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", psCmd);
            pb.start().waitFor();
        } catch (Exception e) {
            System.err.println("[Autostart] Error updating registry: " + e.getMessage());
        }
    }
}
