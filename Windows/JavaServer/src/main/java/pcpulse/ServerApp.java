package pcpulse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import pcpulse.gui.TrayAndGUI;
import pcpulse.network.WebServer;
import pcpulse.system.SystemMonitor;
import pcpulse.worker.WorkerManager;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ServerApp {
    private static final ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) {
        SystemMonitor systemMonitor = new SystemMonitor();
        WorkerManager workerManager = new WorkerManager();
        
        workerManager.start();

        TrayAndGUI gui = new TrayAndGUI(systemMonitor.getLocalIp(), workerManager::stop);
        gui.init();

        WebServer webServer = new WebServer(workerManager);
        webServer.start(5000);

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(() -> {
            try {
                ObjectNode latestWorkerState = workerManager.getLatestState();
                ObjectNode fullState = systemMonitor.buildFullState(latestWorkerState);
                String jsonStr = mapper.writeValueAsString(fullState);
                webServer.broadcast(jsonStr);
            } catch (Exception e) {
            }
        }, 0, 500, TimeUnit.MILLISECONDS);
    }
}
