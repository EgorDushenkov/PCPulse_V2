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
}
