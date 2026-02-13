package com.bookshelf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class StaticFileHandler {

    private final Path staticDir;
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            ".html", "text/html",
            ".css", "text/css",
            ".js", "application/javascript",
            ".json", "application/json",
            ".png", "image/png",
            ".jpg", "image/jpeg",
            ".jpeg", "image/jpeg",
            ".svg", "image/svg+xml",
            ".ico", "image/x-icon"
    );

    public StaticFileHandler(Path staticDir) {
        this.staticDir = staticDir;
    }

    public HttpResponse handle(HttpRequest request) {
        String path = request.getPath();

        // Map / to /index.html
        if (path.equals("/") || path.isEmpty()) {
            path = "/index.html";
        }

        // Remove leading slash
        if (path.startsWith("/")) {
            path = path.substring(1);
        }

        // Directory traversal protection
        Path resolved = staticDir.resolve(path).normalize();
        if (!resolved.startsWith(staticDir.normalize())) {
            return HttpResponse.notFound("Not found");
        }

        if (!Files.exists(resolved) || Files.isDirectory(resolved)) {
            return HttpResponse.notFound("Not found");
        }

        try {
            byte[] content = Files.readAllBytes(resolved);
            String contentType = getContentType(path);
            return HttpResponse.binary(content, contentType);
        } catch (IOException e) {
            return HttpResponse.internalServerError("Internal server error");
        }
    }

    private String getContentType(String path) {
        int dotIndex = path.lastIndexOf('.');
        if (dotIndex >= 0) {
            String ext = path.substring(dotIndex);
            String type = CONTENT_TYPES.get(ext);
            if (type != null) return type;
        }
        return "application/octet-stream";
    }
}
