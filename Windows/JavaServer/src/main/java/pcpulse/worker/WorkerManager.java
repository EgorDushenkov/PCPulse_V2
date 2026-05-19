package pcpulse.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

public class WorkerManager {
    private static final ObjectMapper mapper = new ObjectMapper();
    private ObjectNode latestWorkerState = mapper.createObjectNode();
    private Process workerProcess;
    private BufferedWriter workerWriter;

    public WorkerManager() {
    }

    public void start() {
        try {
            ProcessBuilder pb = new ProcessBuilder("worker.exe");
            pb.redirectErrorStream(true);
            workerProcess = pb.start();
            workerWriter = new BufferedWriter(new OutputStreamWriter(workerProcess.getOutputStream()));

            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(workerProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        try {
                            JsonNode node = mapper.readTree(line);
                            if (node.isObject()) {
                                synchronized (latestWorkerState) {
                                    latestWorkerState = (ObjectNode) node;
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
}
