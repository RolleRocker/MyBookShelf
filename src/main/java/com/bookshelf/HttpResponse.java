package com.bookshelf;

import java.util.HashMap;
import java.util.Map;

public class HttpResponse {
    private final int statusCode;
    private final String statusText;
    private final Map<String, String> headers;
    private final String body;
    private final byte[] rawBody;

    public HttpResponse(int statusCode, String statusText, Map<String, String> headers, String body) {
        this(statusCode, statusText, headers, body, null);
    }

    public HttpResponse(int statusCode, String statusText, Map<String, String> headers, String body, byte[] rawBody) {
        this.statusCode = statusCode;
        this.statusText = statusText;
        this.headers = headers != null ? headers : new HashMap<>();
        this.body = body;
        this.rawBody = rawBody;
    }

    public int getStatusCode() { return statusCode; }
    public String getStatusText() { return statusText; }
    public Map<String, String> getHeaders() { return headers; }
    public String getBody() { return body; }
    public byte[] getRawBody() { return rawBody; }

    public static HttpResponse ok(String body) {
        return new HttpResponse(200, "OK", new HashMap<>(), body);
    }

    public static HttpResponse created(String body) {
        return new HttpResponse(201, "Created", new HashMap<>(), body);
    }

    public static HttpResponse accepted(String body) {
        return new HttpResponse(202, "Accepted", new HashMap<>(), body);
    }

    public static HttpResponse noContent() {
        return new HttpResponse(204, "No Content", new HashMap<>(), null);
    }

    public static HttpResponse badRequest(String error) {
        return new HttpResponse(400, "Bad Request", new HashMap<>(), "{\"error\":\"" + escapeJson(error) + "\"}");
    }

    public static HttpResponse notFound(String error) {
        return new HttpResponse(404, "Not Found", new HashMap<>(), "{\"error\":\"" + escapeJson(error) + "\"}");
    }

    public static HttpResponse binary(byte[] data, String contentType) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", contentType);
        return new HttpResponse(200, "OK", headers, null, data);
    }

    public static HttpResponse internalServerError(String error) {
        return new HttpResponse(500, "Internal Server Error", new HashMap<>(), "{\"error\":\"" + escapeJson(error) + "\"}");
    }

    public static HttpResponse payloadTooLarge(String error) {
        return new HttpResponse(413, "Payload Too Large", new HashMap<>(), "{\"error\":\"" + escapeJson(error) + "\"}");
    }

    public static HttpResponse methodNotAllowed() {
        return new HttpResponse(405, "Method Not Allowed", new HashMap<>(), "{\"error\":\"Method not allowed\"}");
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
