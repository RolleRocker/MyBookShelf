package com.bookshelf.domain.port.out;

import java.util.UUID;

public interface TokenService {
    String createToken(UUID userId, String username);
    UUID validateToken(String token);
}
