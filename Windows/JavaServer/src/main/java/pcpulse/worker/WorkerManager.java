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
    private ObjectNode latestWorkerState = mapper.createObjectNode();
    private Process workerProcess;
    private BufferedWriter workerWriter;
    private final ConcurrentHashMap<String, CompletableFuture<byte[]>> iconRequests = new ConcurrentHashMap<>();

    public WorkerManager() {
    }

    public void start() {
        try {
            ProcessBuilder pb = new ProcessBuilder("worker.exe");
            workerProcess = pb.start();
            workerWriter = new BufferedWriter(new OutputStreamWriter(workerProcess.getOutputStream(), StandardCharsets.UTF_8));

            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(workerProcess.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        try {
                            JsonNode node = mapper.readTree(line);
                            if (node.isObject()) {
                                if (node.has("type") && "icon_response".equals(node.get("type").asText())) {
                                    String reqId = node.get("req_id").asText();
                                    String data = node.get("data").asText();
                                    CompletableFuture<byte[]> future = iconRequests.remove(reqId);
                                    if (future != null) {
                                        if (data.isEmpty()) {
                                            future.complete(null);
                                        } else {
                                            future.complete(java.util.Base64.getDecoder().decode(data));
                                        }
                                    }
                                } else {
                                    synchronized (latestWorkerState) {
                                        latestWorkerState = (ObjectNode) node;
                                    }
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            readerThread.setDaemon(true);
            readerThread.start();

            // Drain stderr separately to prevent it from blocking the process
            Thread stderrThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(workerProcess.getErrorStream(), StandardCharsets.UTF_8))) {
                    while (reader.readLine() != null) { /* discard */ }
                } catch (Exception ignored) {}
            });
            stderrThread.setDaemon(true);
            stderrThread.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void stop() {
        if (workerProcess != null) {
            workerProcess.destroyForcibly();
        }
    }

    public void sendCommand(String jsonCommand) {
        if (workerWriter != null) {
            try {
                workerWriter.write(jsonCommand + "\n");
                workerWriter.flush();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public ObjectNode getLatestState() {
        synchronized (latestWorkerState) {
            return latestWorkerState.deepCopy();
        }
    }

    public CompletableFuture<byte[]> requestIcon(String path) {
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        String reqId = UUID.randomUUID().toString();
        iconRequests.put(reqId, future);
        
        ObjectNode cmd = mapper.createObjectNode();
        cmd.put("action", "get_icon");
        cmd.put("path", path);
        cmd.put("req_id", reqId);
        sendCommand(cmd.toString());
        
        return future;
    }
}
