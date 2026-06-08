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

    public static void main(String[] args) {
        SystemMonitor monitor = new SystemMonitor();
        WorkerManager worker = new WorkerManager();
        AuthManager auth = new AuthManager();

        worker.start();

        WebServer server = new WebServer(worker, auth);

        TrayAndGUI gui = new TrayAndGUI(
            monitor.getLocalIp(),
            auth,
            worker::stop,
            server::disconnectUnauthorized
        );
        gui.init();

        server.start(5000);

        // раз в 500мс собираем состояние и раздаём по вебсокетам
        Executors.newScheduledThreadPool(1).scheduleAtFixedRate(() -> {
            try {
                ObjectNode ws = worker.getLatestState();
                ObjectNode full = monitor.buildFullState(ws);
                server.broadcast(mapper.writeValueAsString(full));
            } catch (Exception ignored) {
                // если один тик упал — ничего, следующий подхватит
            }
        }, 0, 500, TimeUnit.MILLISECONDS);
    }
}
