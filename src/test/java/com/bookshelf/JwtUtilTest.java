package com.bookshelf;

import static org.junit.jupiter.api.Assertions.*;

import com.bookshelf.adapter.out.auth.JwtUtil;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class JwtUtilTest {

    private final JwtUtil jwtUtil = new JwtUtil("test-secret-key");

    @Test
    void testValidTokenReturnsUserId() {
        UUID userId = UUID.randomUUID();
        String token = jwtUtil.createToken(userId, "testuser");
        assertEquals(userId, jwtUtil.validateToken(token));
    }

    @Test
    void testTamperedSignatureReturnsNull() {
        UUID userId = UUID.randomUUID();
        String token = jwtUtil.createToken(userId, "testuser");
        String tampered = token.substring(0, token.length() - 1) + "X";
        assertNull(jwtUtil.validateToken(tampered));
    }

    @Test
    void testUsernameWithSpecialCharsProducesValidToken() {
        UUID userId = UUID.randomUUID();
        String token = jwtUtil.createToken(userId, "user\"with\\quotes");
        UUID result = jwtUtil.validateToken(token);
        assertEquals(userId, result, "Token with special-char username should roundtrip cleanly");
    }

    @Test
    void testUsernameWithQuoteIsPreservedInPayload() {
        UUID userId = UUID.randomUUID();
        String token = jwtUtil.createToken(userId, "user\"name");
        String[] parts = token.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]),
            java.nio.charset.StandardCharsets.UTF_8);
        com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(payload).getAsJsonObject();
        assertEquals("user\"name", obj.get("username").getAsString());
    }

    @Test
    void testMalformedTokenReturnsNull() {
        assertNull(jwtUtil.validateToken("not.a.token"));
        assertNull(jwtUtil.validateToken(""));
        assertNull(jwtUtil.validateToken("a.b"));
        assertNull(jwtUtil.validateToken(null));
    }
}
