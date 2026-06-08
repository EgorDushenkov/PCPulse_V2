package pcpulse.network;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.websocket.WsContext;
import pcpulse.auth.AuthManager;
import pcpulse.worker.WorkerManager;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class WebServer {
    private final WorkerManager workerManager;
    private final AuthManager authManager;
    private final Set<WsContext> connectedClients = ConcurrentHashMap.newKeySet();
    private Javalin app;

    public WebServer(WorkerManager workerManager, AuthManager authManager) {
        this.workerManager = workerManager;
        this.authManager = authManager;
    }

    public void start(int port) {
        app = Javalin.create(config -> {
            config.bundledPlugins.enableCors(cors -> {
                cors.addRule(it -> {
                    it.anyHost();
                });
            });
        });

        try {
            app.start("0.0.0.0", port);
        } catch (Exception e) {

        }

        setupRoutes();
    }

    /**
     * Extracts the Bearer token from the Authorization header.
     * Expected format: "Bearer {token}"
     */
    private String extractToken(Context ctx) {
        String auth = ctx.header("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7);
        }
        return null;
    }

    /**
     * Checks if the request is authorized. Returns true if authorized, false otherwise.
     * Sends 401 response if not authorized.
     */
    private boolean checkAuth(Context ctx) {
        String token = extractToken(ctx);
        if (!authManager.isAuthorized(token)) {
            ctx.status(401).json(Collections.singletonMap("error", "Unauthorized"));
            return false;
        }
        return true;
    }

    private void setupRoutes() {
        // --- AUTH ENDPOINT (no token required) ---
        app.post("/auth/pair", ctx -> {
            try {
                String body = ctx.body();
                // Parse PIN from JSON body: {"pin": "123456"}
                com.fasterxml.jackson.databind.JsonNode node = 
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
                String pin = node.has("pin") ? node.get("pin").asText() : "";
                
                String token = authManager.pair(pin);
                if (token != null) {
                    ctx.json(Collections.singletonMap("token", token));
                } else {
                    ctx.status(401).json(Collections.singletonMap("error", "Invalid PIN"));
                }
            } catch (Exception e) {
                ctx.status(400).json(Collections.singletonMap("error", "Bad request"));
            }
        });

        // --- PROTECTED ENDPOINTS ---
        app.get("/power/{action}", ctx -> {
            if (!checkAuth(ctx)) return;

            String action = ctx.pathParam("action");
            try {
                if (action.equals("sleep")) {
                    Runtime.getRuntime().exec(new String[]{"cmd.exe", "/c", "rundll32.exe powrprof.dll,SetSuspendState 0,1,0"});
                } else if (action.equals("shutdown")) {
                    Runtime.getRuntime().exec(new String[]{"cmd.exe", "/c", "shutdown /s /t 1"});
                } else if (action.equals("restart")) {
                    Runtime.getRuntime().exec(new String[]{"cmd.exe", "/c", "shutdown /r /t 1"});
                }
                ctx.json(Collections.singletonMap("status", "ok"));
            } catch (Exception e) {
                ctx.status(500).result(e.getMessage());
            }
        });

        app.get("/screenshot", ctx -> {
            if (!checkAuth(ctx)) return;

            try {
                Robot robot = new Robot();
                Rectangle screenRect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
                BufferedImage img = robot.createScreenCapture(screenRect);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(img, "jpg", baos);
                byte[] bytes = baos.toByteArray();
                ctx.contentType("image/jpeg");
                ctx.result(bytes);
            } catch (Exception e) {
                ctx.status(500).result(e.getMessage());
            }
        });

        app.get("/kill/{pid}", ctx -> {
            if (!checkAuth(ctx)) return;

            try {
                long pid = Long.parseLong(ctx.pathParam("pid"));
                ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
                ctx.json(Collections.singletonMap("status", "ok"));
            } catch (Exception e) {
                ctx.status(500).result(e.getMessage());
            }
        });

        app.get("/icon", ctx -> {
            String path = ctx.queryParam("path");
            if (path == null || path.isEmpty()) {
                ctx.status(400).result("Path is required");
                return;
            }
            try {
                byte[] iconBytes = workerManager.requestIcon(path).get(5, java.util.concurrent.TimeUnit.SECONDS);
                if (iconBytes != null && iconBytes.length > 0) {
                    ctx.contentType("image/png").result(iconBytes);
                } else {
                    ctx.status(404).result("Icon not found");
                }
            } catch (Exception e) {
                ctx.status(500).result("Error extracting icon");
            }
        });

        // --- WEBSOCKET (token via query parameter) ---
        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> {
                String token = ctx.queryParam("token");
                if (!authManager.isAuthorized(token)) {
                    ctx.session.close(4001, "Unauthorized");
                    return;
                }
                connectedClients.add(ctx);
            });
            ws.onClose(ctx -> connectedClients.remove(ctx));
            ws.onMessage(ctx -> {
                workerManager.sendCommand(ctx.message());
            });
        });
    }

    public void broadcast(String jsonStr) {
        for (WsContext ctx : connectedClients) {
            if (ctx.session.isOpen()) {
                ctx.send(jsonStr);
            }
        }
    }

    public void disconnectUnauthorized() {
        for (WsContext ctx : connectedClients) {
            String token = ctx.queryParam("token");
            if (!authManager.isAuthorized(token)) {
                ctx.session.close(4001, "Unauthorized");
            }
        }
    }
}
