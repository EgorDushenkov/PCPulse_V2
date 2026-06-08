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
    private final WorkerManager worker;
    private final AuthManager auth;
    private final Set<WsContext> clients = ConcurrentHashMap.newKeySet();
    private Javalin app;

    public WebServer(WorkerManager worker, AuthManager auth) {
        this.worker = worker;
        this.auth = auth;
    }

    public void start(int port) {
        app = Javalin.create(cfg -> {
            cfg.bundledPlugins.enableCors(cors -> cors.addRule(it -> it.anyHost()));
        });

        try {
            app.start("0.0.0.0", port);
        } catch (Exception e) {
            //если порт занят — надо бы сказать пользователю, а не молча сдохнуть
            System.err.println("[Server] Не удалось стартовать на порту " + port + ": " + e.getMessage());
        }

        setupRoutes();
    }

    private String extractToken(Context ctx) {
        String hdr = ctx.header("Authorization");
        if (hdr != null && hdr.startsWith("Bearer ")) return hdr.substring(7);
        return null;
    }

    private boolean requireAuth(Context ctx) {
        if (!auth.isAuthorized(extractToken(ctx))) {
            ctx.status(401).json(Collections.singletonMap("error", "Unauthorized"));
            return false;
        }
        return true;
    }

    private void setupRoutes() {
        app.post("/auth/pair", ctx -> {
            try {
                var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(ctx.body());
                String pin = node.has("pin") ? node.get("pin").asText() : "";

                String token = auth.pair(pin);
                if (token != null) {
                    ctx.json(Collections.singletonMap("token", token));
                } else {
                    ctx.status(401).json(Collections.singletonMap("error", "Invalid PIN"));
                }
            } catch (Exception e) {
                ctx.status(400).json(Collections.singletonMap("error", "Bad request"));
            }
        });

        app.get("/power/{action}", ctx -> {
            if (!requireAuth(ctx)) return;

            String action = ctx.pathParam("action");
            try {
                switch (action) {
                    case "sleep" -> Runtime.getRuntime().exec(new String[]{"cmd.exe", "/c", "rundll32.exe powrprof.dll,SetSuspendState 0,1,0"});
                    case "shutdown" -> Runtime.getRuntime().exec(new String[]{"cmd.exe", "/c", "shutdown /s /t 1"});
                    case "restart" -> Runtime.getRuntime().exec(new String[]{"cmd.exe", "/c", "shutdown /r /t 1"});
                }
                ctx.json(Collections.singletonMap("status", "ok"));
            } catch (Exception e) {
                ctx.status(500).result("Power command failed: " + e.getMessage());
            }
        });

        app.get("/screenshot", ctx -> {
            if (!requireAuth(ctx)) return;

            try {
                Robot robot = new Robot();
                BufferedImage img = robot.createScreenCapture(new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()));
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                ImageIO.write(img, "jpg", buf);
                ctx.contentType("image/jpeg").result(buf.toByteArray());
            } catch (Exception e) {
                ctx.status(500).result("Screenshot failed: " + e.getMessage());
            }
        });

        app.get("/kill/{pid}", ctx -> {
            if (!requireAuth(ctx)) return;

            try {
                long pid = Long.parseLong(ctx.pathParam("pid"));
                ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
                ctx.json(Collections.singletonMap("status", "ok"));
            } catch (NumberFormatException e) {
                ctx.status(400).result("Invalid PID");
            } catch (Exception e) {
                ctx.status(500).result("Kill failed: " + e.getMessage());
            }
        });

        app.get("/icon", ctx -> {
            String path = ctx.queryParam("path");
            if (path == null || path.isEmpty()) {
                ctx.status(400).result("Path is required");
                return;
            }
            try {
                byte[] icon = worker.requestIcon(path).get(5, java.util.concurrent.TimeUnit.SECONDS);
                if (icon != null && icon.length > 0) {
                    ctx.contentType("image/png").result(icon);
                } else {
                    ctx.status(404).result("Icon not found");
                }
            } catch (java.util.concurrent.TimeoutException e) {
                ctx.status(504).result("Worker не ответил за 5 сек");
            } catch (Exception e) {
                ctx.status(500).result("Icon extraction failed");
            }
        });

        // ws авторизация через query-параметр, потому что браузерный WS API не даёт ставить заголовки

        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> {
                if (!auth.isAuthorized(ctx.queryParam("token"))) {
                    ctx.session.close(4001, "Unauthorized");
                    return;
                }
                clients.add(ctx);
            });
            ws.onClose(ctx -> clients.remove(ctx));
            ws.onMessage(ctx -> worker.sendCommand(ctx.message()));
        });
    }

    public void broadcast(String json) {
        for (WsContext ctx : clients) {
            if (ctx.session.isOpen()) ctx.send(json);
        }
    }

    public void disconnectUnauthorized() {
        for (WsContext ctx : clients) {
            if (!auth.isAuthorized(ctx.queryParam("token"))) {
                ctx.session.close(4001, "Unauthorized");
            }
        }
    }
}
