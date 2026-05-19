package pcpulse.network;

import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
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
    private final Set<WsContext> connectedClients = ConcurrentHashMap.newKeySet();
    private Javalin app;

    public WebServer(WorkerManager workerManager) {
        this.workerManager = workerManager;
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

    private void setupRoutes() {
        app.get("/power/{action}", ctx -> {
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
            try {
                long pid = Long.parseLong(ctx.pathParam("pid"));
                ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
                ctx.json(Collections.singletonMap("status", "ok"));
            } catch (Exception e) {
                ctx.status(500).result(e.getMessage());
            }
        });

        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> connectedClients.add(ctx));
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
}
