package pcpulse.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

public class WorkerManager {
    private static final ObjectMapper mapper = new ObjectMapper();
    private ObjectNode lastState = mapper.createObjectNode();
    private volatile Process proc;
    private volatile BufferedWriter writer;
    private final ConcurrentHashMap<String, CompletableFuture<byte[]>> iconReqs = new ConcurrentHashMap<>();
    private volatile boolean running = false;
    private Thread supervisorThread;

    public synchronized void start() {
        if (running) return;
        running = true;
        supervisorThread = new Thread(() -> {
            int restartCount = 0;
            while (running) {
                try {
                    System.out.println("[WorkerManager] Запуск worker.exe (попытка " + (restartCount + 1) + ")...");
                    ProcessBuilder pb = new ProcessBuilder("worker.exe");
                    proc = pb.start();
                    writer = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));

                    Process currentProc = proc;
                    Thread errDrain = new Thread(() -> {
                        try (BufferedReader br = new BufferedReader(new InputStreamReader(currentProc.getErrorStream(), StandardCharsets.UTF_8))) {
                            while (br.readLine() != null) { /* /dev/null */ }
                        } catch (Exception ignored) {}
                    });
                    errDrain.setDaemon(true);
                    errDrain.start();

                    try (BufferedReader br = new BufferedReader(new InputStreamReader(currentProc.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while (running && (line = br.readLine()) != null) {
                            restartCount = 0; // сбрасываем счётчик при успешном чтении
                            try {
                                JsonNode node = mapper.readTree(line);
                                if (!node.isObject()) continue;

                                if (node.has("type") && "icon_response".equals(node.get("type").asText())) {
                                    String reqId = node.get("req_id").asText();
                                    String data = node.get("data").asText();
                                    CompletableFuture<byte[]> future = iconReqs.remove(reqId);
                                    if (future != null) {
                                        future.complete(data.isEmpty() ? null : java.util.Base64.getDecoder().decode(data));
                                    }
                                } else {
                                    synchronized (lastState) {
                                        lastState = (ObjectNode) node;
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                    if (running) {
                        try { currentProc.waitFor(); } catch (InterruptedException ignored) {}
                        System.err.println("[WorkerManager] worker.exe завершил работу. Планируется перезапуск.");
                    }
                } catch (Exception e) {
                    if (running) {
                        System.err.println("[WorkerManager] Ошибка запуска/чтения worker.exe: " + e.getMessage());
                    }
                }

                writer = null;
                if (proc != null) {
                    proc.destroyForcibly();
                    proc = null;
                }

                if (running) {
                    restartCount++;
                    long sleepMs = Math.min(10000L, 1000L * (1L << Math.min(restartCount, 3))); // 2s, 4s, 8s max
                    System.err.println("[WorkerManager] Перезапуск через " + (sleepMs / 1000) + " сек...");
                    try { Thread.sleep(sleepMs); } catch (InterruptedException ignored) {}
                }
            }
        }, "WorkerSupervisor");
        supervisorThread.setDaemon(true);
        supervisorThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (supervisorThread != null) supervisorThread.interrupt();
        if (proc != null) proc.destroyForcibly();
        writer = null;
    }

    public void sendCommand(String json) {
        BufferedWriter w = this.writer;
        Process p = this.proc;
        if (w == null || p == null || !p.isAlive()) return;
        try {
            w.write(json + "\n");
            w.flush();
        } catch (Exception e) {
            System.err.println("[Worker] Не смог отправить команду: " + e.getMessage());
            if (p != null) p.destroyForcibly(); // форсируем перезапуск воркера при сбое канала
        }
    }

    public ObjectNode getLatestState() {
        synchronized (lastState) {
            return lastState.deepCopy();
        }
    }

    public CompletableFuture<byte[]> requestIcon(String path) {
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        String reqId = UUID.randomUUID().toString();
        iconReqs.put(reqId, future);

        ObjectNode cmd = mapper.createObjectNode();
        cmd.put("action", "get_icon");
        cmd.put("path", path);
        cmd.put("req_id", reqId);
        sendCommand(cmd.toString());

        return future;
    }
}
