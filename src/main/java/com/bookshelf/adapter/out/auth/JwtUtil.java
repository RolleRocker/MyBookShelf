package com.bookshelf.adapter.out.auth;

import com.bookshelf.domain.port.out.TokenService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JwtUtil implements TokenService {

    private static final Logger logger = LoggerFactory.getLogger(JwtUtil.class);
    private static final long EXPIRY_MS = 24 * 60 * 60 * 1000;
    private static final Gson gson = new Gson();
    private final byte[] secret;

    public JwtUtil() {
        String envSecret = System.getenv("JWT_SECRET");
        if (envSecret != null && !envSecret.isEmpty()) {
            this.secret = envSecret.getBytes(StandardCharsets.UTF_8);
        } else {
            logger.warn("JWT_SECRET not set - generating random secret. Tokens will not survive restarts.");
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.secret = random;
        }
    }

    public JwtUtil(String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String createToken(UUID userId, String username) {
        String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        long now = System.currentTimeMillis() / 1000;
        long exp = now + (EXPIRY_MS / 1000);
        JsonObject payloadObj = new JsonObject();
        payloadObj.addProperty("sub", userId.toString());
        payloadObj.addProperty("username", username);
        payloadObj.addProperty("iss", "mybookshelf");
        payloadObj.addProperty("aud", "mybookshelf");
        payloadObj.addProperty("iat", now);
        payloadObj.addProperty("exp", exp);
        String payloadJson = gson.toJson(payloadObj);
        String payload = base64Url(payloadJson);
        String signature = sign(header + "." + payload);
        return header + "." + payload + "." + signature;
    }

    @Override
    public UUID validateToken(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return null;

            // Validate algorithm to prevent alg:none bypass
            String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            JsonObject header = JsonParser.parseString(headerJson).getAsJsonObject();
            String alg = header.has("alg") ? header.get("alg").getAsString() : null;
            if (!"HS256".equals(alg)) return null;

            String expectedSig = sign(parts[0] + "." + parts[1]);
            if (!MessageDigest.isEqual(expectedSig.getBytes(StandardCharsets.UTF_8), parts[2].getBytes(StandardCharsets.UTF_8))) return null;

            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            JsonObject payload = JsonParser.parseString(payloadJson).getAsJsonObject();

            long exp = payload.has("exp") ? payload.get("exp").getAsLong() : 0;
            if (System.currentTimeMillis() / 1000 > exp) return null;

            // Validate issuer/audience if present (soft migration for existing tokens)
            if (payload.has("iss") && !"mybookshelf".equals(payload.get("iss").getAsString())) return null;
            if (payload.has("aud") && !"mybookshelf".equals(payload.get("aud").getAsString())) return null;

            String sub = payload.has("sub") && !payload.get("sub").isJsonNull()
                ? payload.get("sub").getAsString() : null;
            if (sub == null) return null;

            return UUID.fromString(sub);
        } catch (Exception e) {
            return null;
        }
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] sig = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign JWT", e);
        }
    }

    private String base64Url(String input) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(input.getBytes(StandardCharsets.UTF_8));
    }


}
