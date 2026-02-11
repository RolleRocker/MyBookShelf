package com.bookshelf;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class HttpServer {

    private final int port;
    private final Router router;
    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private volatile boolean running;

    public HttpServer(int port, Router router) {
        this.port = port;
        this.router = router;
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
                        System.err.println("Error accepting connection: " + e.getMessage());
                    }
                }
            }
        });
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void handleConnection(Socket socket) {
        try {
            HttpRequest request = RequestParser.parse(socket.getInputStream());
            HttpResponse response = router.route(request);
            ResponseWriter.write(socket.getOutputStream(), response);
            socket.shutdownOutput();
        } catch (Exception e) {
            System.err.println("Error handling request: " + e.getMessage());
        } finally {
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
            System.err.println("Error closing server socket: " + e.getMessage());
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
