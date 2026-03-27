package com.bookshelf.framework.http;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class ResponseWriter {

    public static void write(OutputStream output, HttpResponse response) throws IOException {
        StringBuilder sb = new StringBuilder();

        // Status line (sanitize status text against CRLF injection)
        String statusText = response.getStatusText().replaceAll("[\\r\\n]", "");
        sb.append("HTTP/1.1 ").append(response.getStatusCode())
          .append(" ").append(statusText).append("\r\n");

        // Body bytes — prefer rawBody (binary) over body (string)
        byte[] bodyBytes = null;
        if (response.getRawBody() != null) {
            bodyBytes = response.getRawBody();
        } else if (response.getBody() != null) {
            bodyBytes = response.getBody().getBytes(StandardCharsets.UTF_8);
        }

        // Build merged headers without mutating the response object.
        // Security headers go first, then response-specific headers override.
        Map<String, String> allHeaders = new LinkedHashMap<>();

        // Security headers (defaults)
        allHeaders.put("X-Content-Type-Options", "nosniff");
        allHeaders.put("X-Frame-Options", "DENY");
        allHeaders.put("Content-Security-Policy",
            "default-src 'self'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; "
            + "font-src https://fonts.gstatic.com; script-src 'self' https://accounts.google.com; "
            + "img-src 'self' data: blob:; connect-src 'self'");
        allHeaders.put("Referrer-Policy", "strict-origin-when-cross-origin");

        // Response-specific headers override security defaults
        if (response.getHeaders() != null) {
            allHeaders.putAll(response.getHeaders());
        }

        // Auto-set headers
        if (bodyBytes != null && !allHeaders.containsKey("Content-Type")) {
            allHeaders.put("Content-Type", "application/json; charset=utf-8");
        }
        if (bodyBytes != null) {
            allHeaders.put("Content-Length", String.valueOf(bodyBytes.length));
        } else {
            allHeaders.put("Content-Length", "0");
        }
        allHeaders.put("Connection", "close");

        // Write headers (strip CR/LF as defense against header injection)
        for (var entry : allHeaders.entrySet()) {
            String key = entry.getKey().replaceAll("[\\r\\n]", "");
            String value = entry.getValue().replaceAll("[\\r\\n]", "");
            sb.append(key).append(": ").append(value).append("\r\n");
        }
        sb.append("\r\n");

        output.write(sb.toString().getBytes(StandardCharsets.UTF_8));

        // Write body
        if (bodyBytes != null) {
            output.write(bodyBytes);
        }

        output.flush();
    }
}
