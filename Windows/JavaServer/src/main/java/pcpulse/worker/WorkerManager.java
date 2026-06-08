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
    private Process proc;
    private BufferedWriter writer;
    private final ConcurrentHashMap<String, CompletableFuture<byte[]>> iconReqs = new ConcurrentHashMap<>();

    public void start() {
        try {
            proc = new ProcessBuilder("worker.exe").start();
            writer = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));

            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
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
                } catch (Exception e) {
                    System.err.println("[Worker] stdout reader упал: " + e.getMessage());
                }
            });
            reader.setDaemon(true);
            reader.start();

            // stderr надо дренить, иначе буфер забьётся и worker зависнет
            Thread errDrain = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                    while (br.readLine() != null) { /* /dev/null */ }
                } catch (Exception ignored) {}
            });
            errDrain.setDaemon(true);
            errDrain.start();
        } catch (Exception e) {
            //нормально обработать — сейчас если worker.exe нет, просто молча ляжем
            e.printStackTrace();
        }
    }

    public void stop() {
        if (proc != null) proc.destroyForcibly();
    }

    public void sendCommand(String json) {
        if (writer == null) return;
        try {
            writer.write(json + "\n");
            writer.flush();
        } catch (Exception e) {
            System.err.println("[Worker] Не смог отправить команду: " + e.getMessage());
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
