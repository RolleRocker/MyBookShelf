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

    @Test
    void testAlgNoneTokenIsRejected() {
        UUID userId = UUID.randomUUID();
        String token = jwtUtil.createToken(userId, "testuser");
        // Replace the header with alg:none
        String noneHeader = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String[] parts = token.split("\\.");
        String tampered = noneHeader + "." + parts[1] + "." + parts[2];
        assertNull(jwtUtil.validateToken(tampered), "Token with alg:none must be rejected");
    }

    @Test
    void testWrongAlgorithmTokenIsRejected() {
        UUID userId = UUID.randomUUID();
        String token = jwtUtil.createToken(userId, "testuser");
        // Replace the header with alg:HS384
        String wrongAlgHeader = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"alg\":\"HS384\",\"typ\":\"JWT\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String[] parts = token.split("\\.");
        String tampered = wrongAlgHeader + "." + parts[1] + "." + parts[2];
        assertNull(jwtUtil.validateToken(tampered), "Token with wrong algorithm must be rejected");
    }

    @Test
    void testAlgNoneWithDummySignatureIsRejected() {
        UUID userId = UUID.randomUUID();
        String token = jwtUtil.createToken(userId, "testuser");
        // Build alg:none header with a dummy (non-empty) signature so split produces 3 parts
        // and the token reaches the algorithm validation check (not just the parts.length check)
        String noneHeader = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String[] parts = token.split("\\.");
        String tampered = noneHeader + "." + parts[1] + ".dummysig";
        assertNull(jwtUtil.validateToken(tampered), "Token with alg:none must be rejected by algorithm check");
    }
}
