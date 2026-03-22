package com.bookshelf.framework.http;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class ResponseWriter {

    public static void write(OutputStream output, HttpResponse response) throws IOException {
        StringBuilder sb = new StringBuilder();

        // Status line
        sb.append("HTTP/1.1 ").append(response.getStatusCode())
          .append(" ").append(response.getStatusText()).append("\r\n");

        // Body bytes — prefer rawBody (binary) over body (string)
        byte[] bodyBytes = null;
        if (response.getRawBody() != null) {
            bodyBytes = response.getRawBody();
        } else if (response.getBody() != null) {
            bodyBytes = response.getBody().getBytes(StandardCharsets.UTF_8);
        }

        // Auto-set headers
        if (bodyBytes != null && !response.getHeaders().containsKey("Content-Type")) {
            response.getHeaders().put("Content-Type", "application/json; charset=utf-8");
        }
        if (bodyBytes != null) {
            response.getHeaders().put("Content-Length", String.valueOf(bodyBytes.length));
        } else {
            response.getHeaders().put("Content-Length", "0");
        }
        response.getHeaders().put("Connection", "close");

        // Write headers (strip CR/LF as defense against header injection)
        for (var entry : response.getHeaders().entrySet()) {
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
