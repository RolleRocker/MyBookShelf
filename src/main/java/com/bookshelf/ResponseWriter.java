package com.bookshelf;

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
            response.getHeaders().put("Content-Type", "application/json");
        }
        if (bodyBytes != null) {
            response.getHeaders().put("Content-Length", String.valueOf(bodyBytes.length));
        } else {
            response.getHeaders().put("Content-Length", "0");
        }
        response.getHeaders().put("Connection", "close");

        // Write headers
        for (var entry : response.getHeaders().entrySet()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
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
