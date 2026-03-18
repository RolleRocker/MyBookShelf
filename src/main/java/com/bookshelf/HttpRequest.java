package com.bookshelf;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class HttpRequest {
    private final String method;
    private final String path;
    private Map<String, String> pathParams;
    private final Map<String, String> queryParams;
    private final Map<String, String> headers;
    private final String body;
    private UUID userId;

    public HttpRequest(String method, String path, Map<String, String> queryParams,
                       Map<String, String> headers, String body) {
        this.method = method;
        this.path = path;
        this.pathParams = new HashMap<>();
        this.queryParams = queryParams;
        this.headers = headers;
        this.body = body;
    }

    public String getMethod() { return method; }
    public String getPath() { return path; }

    public Map<String, String> getPathParams() { return pathParams; }
    public void setPathParams(Map<String, String> pathParams) { this.pathParams = pathParams; }

    public Map<String, String> getQueryParams() { return queryParams; }
    public Map<String, String> getHeaders() { return headers; }
    public String getBody() { return body; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
}
