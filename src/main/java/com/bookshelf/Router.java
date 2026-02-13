package com.bookshelf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class Router {

    private record Route(String method, String pattern, String[] segments, Function<HttpRequest, HttpResponse> handler) {}

    private final List<Route> routes = new ArrayList<>();
    private Function<HttpRequest, HttpResponse> fallbackHandler;

    public void setFallbackHandler(Function<HttpRequest, HttpResponse> handler) {
        this.fallbackHandler = handler;
    }

    public void addRoute(String method, String pattern, Function<HttpRequest, HttpResponse> handler) {
        String[] segments = splitPath(pattern);
        routes.add(new Route(method.toUpperCase(), pattern, segments, handler));
    }

    public HttpResponse route(HttpRequest request) {
        String[] requestSegments = splitPath(request.getPath());

        Route bestMatch = null;
        int bestStaticCount = -1;
        Map<String, String> bestParams = null;
        boolean pathMatchedButMethodDifferent = false;

        for (Route route : routes) {
            Map<String, String> params = tryMatch(route.segments, requestSegments);
            if (params != null) {
                if (route.method.equals(request.getMethod())) {
                    int staticCount = countStaticSegments(route.segments);
                    if (staticCount > bestStaticCount) {
                        bestMatch = route;
                        bestStaticCount = staticCount;
                        bestParams = params;
                    }
                } else {
                    pathMatchedButMethodDifferent = true;
                }
            }
        }

        if (bestMatch != null) {
            request.setPathParams(bestParams);
            return bestMatch.handler.apply(request);
        }

        if (pathMatchedButMethodDifferent) {
            return HttpResponse.methodNotAllowed();
        }

        if (fallbackHandler != null && "GET".equals(request.getMethod())) {
            return fallbackHandler.apply(request);
        }

        return HttpResponse.notFound("Not found");
    }

    private Map<String, String> tryMatch(String[] routeSegments, String[] requestSegments) {
        if (routeSegments.length != requestSegments.length) {
            return null;
        }

        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < routeSegments.length; i++) {
            String routeSeg = routeSegments[i];
            String reqSeg = requestSegments[i];

            if (routeSeg.startsWith("{") && routeSeg.endsWith("}")) {
                String paramName = routeSeg.substring(1, routeSeg.length() - 1);
                params.put(paramName, reqSeg);
            } else {
                if (!routeSeg.equals(reqSeg)) {
                    return null;
                }
            }
        }
        return params;
    }

    private int countStaticSegments(String[] segments) {
        int count = 0;
        for (String seg : segments) {
            if (!seg.startsWith("{")) {
                count++;
            }
        }
        return count;
    }

    private String[] splitPath(String path) {
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.isEmpty()) {
            return new String[0];
        }
        return path.split("/");
    }
}
