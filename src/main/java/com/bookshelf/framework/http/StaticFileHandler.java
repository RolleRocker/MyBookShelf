package com.bookshelf.framework.http;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class StaticFileHandler {

    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry(".html", "text/html; charset=utf-8"),
            Map.entry(".css", "text/css; charset=utf-8"),
            Map.entry(".js", "application/javascript; charset=utf-8"),
            Map.entry(".json", "application/json; charset=utf-8"),
            Map.entry(".wasm", "application/wasm"),
            Map.entry(".png", "image/png"),
            Map.entry(".jpg", "image/jpeg"),
            Map.entry(".jpeg", "image/jpeg"),
            Map.entry(".svg", "image/svg+xml"),
            Map.entry(".ico", "image/x-icon")
    );

    private final Path normalizedStaticDir;

    public StaticFileHandler(Path staticDir) {
        this.normalizedStaticDir = staticDir.normalize();
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
        Path resolved = normalizedStaticDir.resolve(path).normalize();
        if (!resolved.startsWith(normalizedStaticDir)) {
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
