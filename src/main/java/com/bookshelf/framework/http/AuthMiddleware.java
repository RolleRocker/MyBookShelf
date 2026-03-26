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
            // Only specific GET endpoints are public; API data routes require auth
            if ("/health".equals(path) || "/auth/config".equals(path)) {
                return true;
            }
            // Static files and other non-API paths remain public
            return !path.startsWith("/books") && !path.startsWith("/shelves")
                && !path.startsWith("/goals") && !path.startsWith("/auth")
                && !path.startsWith("/mcp");
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
