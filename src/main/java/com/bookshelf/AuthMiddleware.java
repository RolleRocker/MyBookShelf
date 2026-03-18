package com.bookshelf;

import java.util.Optional;
import java.util.UUID;

public class AuthMiddleware {

    private final JwtUtil jwtUtil;

    public AuthMiddleware(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    public Optional<UUID> authenticate(HttpRequest request) {
        String authHeader = request.getHeaders().get("authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String token = authHeader.substring(7);
        UUID userId = jwtUtil.validateToken(token);
        return Optional.ofNullable(userId);
    }

    public static boolean isPublicRoute(String method, String path) {
        if ("GET".equalsIgnoreCase(method)) {
            return true;
        }
        if ("POST".equalsIgnoreCase(method)) {
            if ("/auth/register".equals(path) || "/auth/login".equals(path) || "/mcp".equals(path)) {
                return true;
            }
        }
        return false;
    }
}
