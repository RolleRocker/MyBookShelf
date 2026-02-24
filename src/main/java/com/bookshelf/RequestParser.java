package com.bookshelf;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class RequestParser {

    private static final int MAX_BODY_SIZE = 10 * 1024 * 1024; // 10 MB
    private static final int MAX_HEADER_LINE_SIZE = 8 * 1024;  // 8 KB per header line

    public static HttpRequest parse(InputStream input) throws IOException {
        String requestLine = readLine(input);
        if (requestLine == null || requestLine.isEmpty()) {
            throw new IOException("Empty request");
        }

        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            throw new IOException("Malformed request line: " + requestLine);
        }

        String method = parts[0].toUpperCase();
        String fullPath = parts[1];

        // Split path and query string
        String path;
        Map<String, String> queryParams = new HashMap<>();
        int qIndex = fullPath.indexOf('?');
        if (qIndex >= 0) {
            path = fullPath.substring(0, qIndex);
            parseQueryString(fullPath.substring(qIndex + 1), queryParams);
        } else {
            path = fullPath;
        }

        // Read headers
        Map<String, String> headers = new HashMap<>();
        String headerLine;
        while ((headerLine = readLine(input)) != null && !headerLine.isEmpty()) {
            int colonIndex = headerLine.indexOf(':');
            if (colonIndex > 0) {
                String key = headerLine.substring(0, colonIndex).trim().toLowerCase();
                String value = headerLine.substring(colonIndex + 1).trim()
                        .replace("\r", "").replace("\n", "");
                headers.put(key, value);
            }
        }

        // Read body based on Content-Length
        String body = null;
        String contentLengthStr = headers.get("content-length");
        if (contentLengthStr != null) {
            int contentLength;
            try {
                contentLength = Integer.parseInt(contentLengthStr.trim());
            } catch (NumberFormatException e) {
                throw new IOException("Invalid Content-Length header: " + contentLengthStr.trim());
            }
            if (contentLength < 0) {
                throw new IOException("Invalid Content-Length: " + contentLength);
            }
            if (contentLength > MAX_BODY_SIZE) {
                throw new RequestTooLargeException("Request body too large (" + contentLength + " bytes)");
            }
            if (contentLength > 0) {
                byte[] bodyBytes = new byte[contentLength];
                int totalRead = 0;
                while (totalRead < contentLength) {
                    int read = input.read(bodyBytes, totalRead, contentLength - totalRead);
                    if (read == -1) break;
                    totalRead += read;
                }
                body = new String(bodyBytes, 0, totalRead, StandardCharsets.UTF_8);
            }
        }

        return new HttpRequest(method, path, queryParams, headers, body);
    }

    private static String readLine(InputStream input) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = input.read()) != -1) {
            if (c == '\r') {
                int next = input.read(); // consume \n
                if (next != '\n' && next != -1) {
                    sb.append((char) c);
                    sb.append((char) next);
                    if (sb.length() > MAX_HEADER_LINE_SIZE) {
                        throw new IOException("Header line too long");
                    }
                    continue;
                }
                break;
            }
            if (c == '\n') {
                break;
            }
            sb.append((char) c);
            if (sb.length() > MAX_HEADER_LINE_SIZE) {
                throw new IOException("Header line too long");
            }
        }
        return sb.length() == 0 && c == -1 ? null : sb.toString();
    }

    private static void parseQueryString(String queryString, Map<String, String> params) throws IOException {
        if (queryString == null || queryString.isEmpty()) return;
        for (String pair : queryString.split("&")) {
            int eqIndex = pair.indexOf('=');
            if (eqIndex > 0) {
                try {
                    String key = URLDecoder.decode(pair.substring(0, eqIndex), StandardCharsets.UTF_8);
                    String value = URLDecoder.decode(pair.substring(eqIndex + 1), StandardCharsets.UTF_8);
                    params.put(key, value);
                } catch (IllegalArgumentException e) {
                    throw new IOException("Malformed percent-encoding in query string", e);
                }
            }
        }
    }
}
