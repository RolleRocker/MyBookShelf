package com.bookshelf.framework.http;

import com.bookshelf.domain.port.out.TokenService;

import java.util.Optional;
import java.util.UUID;

public class AuthMiddleware {

    private final TokenService tokenService;

    public AuthMiddleware(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    public Optional<UUID> authenticate(HttpRequest request) {
        String authHeader = request.getHeaders().get("authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String token = authHeader.substring(7);
        UUID userId = tokenService.validateToken(token);
        return Optional.ofNullable(userId);
    }

    public static boolean isPublicRoute(String method, String path) {
        if ("GET".equalsIgnoreCase(method)) {
            return true;
        }
        if ("POST".equalsIgnoreCase(method)) {
            if ("/auth/register".equals(path) || "/auth/login".equals(path)
                    || "/auth/google".equals(path) || "/mcp".equals(path)) {
                return true;
            }
        }
        return false;
    }
}
