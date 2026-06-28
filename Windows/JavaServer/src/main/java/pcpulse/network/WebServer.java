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
import java.io.File;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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

        app.get("/fs/list", ctx -> {
            if (!requireAuth(ctx)) return;
            String path = ctx.queryParam("path");
            List<Map<String, Object>> result = new ArrayList<>();
            try {
                if (path == null || path.trim().isEmpty()) {
                    File[] roots = File.listRoots();
                    if (roots != null) {
                        for (File f : roots) {
                            Map<String, Object> item = new HashMap<>();
                            item.put("name", f.getAbsolutePath());
                            item.put("path", f.getAbsolutePath());
                            item.put("isDir", true);
                            item.put("size", f.getTotalSpace());
                            item.put("date", f.lastModified());
                            result.add(item);
                        }
                    }
                } else {
                    File dir = new File(path);
                    if (dir.exists() && dir.isDirectory()) {
                        File[] files = dir.listFiles();
                        if (files != null) {
                            for (File f : files) {
                                Map<String, Object> item = new HashMap<>();
                                item.put("name", f.getName());
                                item.put("path", f.getAbsolutePath());
                                item.put("isDir", f.isDirectory());
                                item.put("size", f.length());
                                item.put("date", f.lastModified());
                                result.add(item);
                            }
                            result.sort((a, b) -> {
                                boolean dirA = (Boolean) a.get("isDir");
                                boolean dirB = (Boolean) b.get("isDir");
                                if (dirA && !dirB) return -1;
                                if (!dirA && dirB) return 1;
                                return ((String) a.get("name")).compareToIgnoreCase((String) b.get("name"));
                            });
                        }
                    } else {
                        ctx.status(404).result("Directory not found");
                        return;
                    }
                }
                ctx.json(result);
            } catch (Exception e) {
                ctx.status(500).result(e.getMessage());
            }
        });

        app.post("/fs/copy", ctx -> {
            if (!requireAuth(ctx)) return;
            try {
                var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(ctx.body());
                String dest = node.has("destination") ? node.get("destination").asText() : "";
                List<String> sources = new ArrayList<>();
                if (node.has("sources")) {
                    node.get("sources").forEach(s -> sources.add(s.asText()));
                }
                
                if (dest.isEmpty() || sources.isEmpty()) {
                    ctx.status(400).result("Bad Request");
                    return;
                }

                new Thread(() -> {
                    for (String srcStr : sources) {
                        try {
                            Path srcPath = Paths.get(srcStr);
                            Path destPath = Paths.get(dest, srcPath.getFileName().toString());
                            if (Files.isDirectory(srcPath)) {
                                Files.walkFileTree(srcPath, new SimpleFileVisitor<Path>() {
                                    @Override
                                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws java.io.IOException {
                                        Path targetDir = destPath.resolve(srcPath.relativize(dir));
                                        if (!Files.exists(targetDir)) Files.createDirectory(targetDir);
                                        return FileVisitResult.CONTINUE;
                                    }
                                    @Override
                                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws java.io.IOException {
                                        Files.copy(file, destPath.resolve(srcPath.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                                        return FileVisitResult.CONTINUE;
                                    }
                                });
                            } else {
                                Files.copy(srcPath, destPath, StandardCopyOption.REPLACE_EXISTING);
                            }
                        } catch (Exception e) {
                        }
                    }
                }).start();
                
                ctx.json(Collections.singletonMap("status", "started"));
            } catch (Exception e) {
                ctx.status(500).result(e.getMessage());
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
