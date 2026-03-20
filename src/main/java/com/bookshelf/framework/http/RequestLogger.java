package com.bookshelf.framework.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RequestLogger {

    private static final Logger logger = LoggerFactory.getLogger(RequestLogger.class);

    public static void log(HttpRequest request, HttpResponse response, long elapsedMs, String clientIp) {
        String method = request != null ? request.getMethod() : "?";
        String path = request != null ? request.getPath() : "?";
        String queryString = "";
        if (request != null && request.getQueryParams() != null && !request.getQueryParams().isEmpty()) {
            StringBuilder sb = new StringBuilder("?");
            request.getQueryParams().forEach((k, v) -> {
                if (sb.length() > 1) sb.append("&");
                sb.append(k).append("=").append(v);
            });
            queryString = sb.toString();
        }
        int status = response != null ? response.getStatusCode() : 0;
        String message = String.format("%s %s%s %d %dms %s", method, path, queryString, status, elapsedMs, clientIp);

        if (status >= 500) {
            logger.error(message);
        } else if (status >= 400) {
            logger.warn(message);
        } else {
            logger.info(message);
        }
    }
}
