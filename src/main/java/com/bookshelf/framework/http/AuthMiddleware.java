package com.bookshelf.framework.http;

import com.bookshelf.domain.port.out.TokenService;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class AuthMiddleware {

    private final TokenService tokenService;
    private final Set<String> publicGetPaths;
    private final Set<String> publicPostPaths;
    private final Set<String> protectedGetPrefixes;

    public AuthMiddleware(TokenService tokenService,
                          Set<String> publicGetPaths,
                          Set<String> publicPostPaths,
                          Set<String> protectedGetPrefixes) {
        this.tokenService = tokenService;
        this.publicGetPaths = publicGetPaths;
        this.publicPostPaths = publicPostPaths;
        this.protectedGetPrefixes = protectedGetPrefixes;
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

    public boolean isPublicRoute(String method, String path) {
        if ("GET".equalsIgnoreCase(method)) {
            if (publicGetPaths.contains(path)) return true;
            for (String prefix : protectedGetPrefixes) {
                if (path.startsWith(prefix)) return false;
            }
            return true;
        }
        if ("POST".equalsIgnoreCase(method)) {
            return publicPostPaths.contains(path);
        }
        return false;
    }
}
