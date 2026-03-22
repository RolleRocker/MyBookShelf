package com.bookshelf.framework.http;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class HttpServer {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(HttpServer.class);

    private final int port;
    private final Router router;
    private final AuthMiddleware authMiddleware;
    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private volatile boolean running;

    public HttpServer(int port, Router router) {
        this(port, router, null);
    }

    public HttpServer(int port, Router router, AuthMiddleware authMiddleware) {
        this.port = port;
        this.router = router;
        this.authMiddleware = authMiddleware;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        threadPool = Executors.newFixedThreadPool(10);
        running = true;

        Thread acceptThread = new Thread(() -> {
            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    threadPool.submit(() -> handleConnection(clientSocket));
                } catch (IOException e) {
                    if (running) {
                        logger.warn("Error accepting connection: {}", e.getMessage());
                    }
                }
            }
        });
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private static final int READ_TIMEOUT_MS = 30_000;

    private void handleConnection(Socket socket) {
        HttpRequest request = null;
        HttpResponse response = null;
        long startTime = System.nanoTime();
        String clientIp = socket.getInetAddress().getHostAddress();
        try {
            socket.setSoTimeout(READ_TIMEOUT_MS);
            InputStream buffered = new BufferedInputStream(socket.getInputStream());
            request = RequestParser.parse(buffered);

            // Auth check
            if (authMiddleware != null) {
                boolean isPublic = AuthMiddleware.isPublicRoute(request.getMethod(), request.getPath());
                if (!isPublic) {
                    java.util.Optional<java.util.UUID> userId = authMiddleware.authenticate(request);
                    if (userId.isEmpty()) {
                        response = HttpResponse.unauthorized("authentication required");
                        ResponseWriter.write(socket.getOutputStream(), response);
                        socket.shutdownOutput();
                        return;
                    }
                    request.setUserId(userId.get());
                }
            }

            response = router.route(request);
            ResponseWriter.write(socket.getOutputStream(), response);
            socket.shutdownOutput();
        } catch (RequestTooLargeException e) {
            response = HttpResponse.payloadTooLarge("Request body too large");
            try {
                ResponseWriter.write(socket.getOutputStream(), response);
            } catch (IOException ignored) {}
        } catch (IOException e) {
            response = HttpResponse.badRequest("Bad request");
            try {
                ResponseWriter.write(socket.getOutputStream(), response);
            } catch (IOException ignored) {}
        } catch (Exception e) {
            logger.error("Unhandled exception processing request", e);
            response = HttpResponse.internalServerError("Internal server error");
            try {
                ResponseWriter.write(socket.getOutputStream(), response);
            } catch (IOException ignored) {}
        } finally {
            long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
            RequestLogger.log(request, response, elapsedMs, clientIp);
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            logger.error("Error closing server socket: {}", e.getMessage());
        }
        if (threadPool != null) {
            threadPool.shutdown();
            try {
                if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    threadPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                threadPool.shutdownNow();
            }
        }
    }

    public int getPort() {
        return serverSocket != null ? serverSocket.getLocalPort() : port;
    }
}
