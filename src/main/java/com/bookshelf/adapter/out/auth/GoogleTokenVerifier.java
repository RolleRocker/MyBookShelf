package com.bookshelf.adapter.out.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verifies Google ID tokens (RS256 JWTs) using Google's public JWKS keys.
 * No external dependencies — uses only JDK crypto and Gson.
 */
public class GoogleTokenVerifier {

    private static final String GOOGLE_JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs";
    private static final long KEY_REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000; // 6 hours

    private final String clientId;
    private final Map<String, RSAPublicKey> keyCache;
    private final boolean useFixedKeys;
    private volatile long lastKeyFetchTime = 0;

    /** Production constructor — fetches keys from Google's JWKS endpoint. */
    public GoogleTokenVerifier(String clientId) {
        this.clientId = clientId;
        this.keyCache = new ConcurrentHashMap<>();
        this.useFixedKeys = false;
    }

    /** Test constructor — uses pre-loaded keys, never fetches from Google. */
    public GoogleTokenVerifier(String clientId, Map<String, RSAPublicKey> testKeys) {
        this.clientId = clientId;
        this.keyCache = new ConcurrentHashMap<>(testKeys);
        this.useFixedKeys = true;
    }

    public static class GoogleUserInfo {
        private final String sub;
        private final String email;
        private final String name;

        public GoogleUserInfo(String sub, String email, String name) {
            this.sub = sub;
            this.email = email;
            this.name = name;
        }

        public String getSub() { return sub; }
        public String getEmail() { return email; }
        public String getName() { return name; }
    }

    /**
     * Verifies a Google ID token and extracts user info.
     * @return GoogleUserInfo if valid, null if invalid
     */
    public GoogleUserInfo verify(String idToken) {
        if (idToken == null || idToken.isBlank()) return null;

        String[] parts = idToken.split("\\.");
        if (parts.length != 3) return null;

        try {
            // Decode header to get kid
            String headerJson = new String(base64UrlDecode(parts[0]));
            JsonObject header = JsonParser.parseString(headerJson).getAsJsonObject();
            String alg = header.has("alg") ? header.get("alg").getAsString() : null;
            String kid = header.has("kid") ? header.get("kid").getAsString() : null;

            if (!"RS256".equals(alg)) return null;
            if (kid == null) return null;

            // Get the public key for this kid
            RSAPublicKey publicKey = getKey(kid);
            if (publicKey == null) {
                // Key not found — try refreshing keys (rotation may have happened)
                if (!useFixedKeys) {
                    refreshKeys();
                    publicKey = getKey(kid);
                }
                if (publicKey == null) return null;
            }

            // Verify RS256 signature
            byte[] signatureBytes = base64UrlDecode(parts[2]);
            byte[] signedContent = (parts[0] + "." + parts[1]).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(publicKey);
            sig.update(signedContent);
            if (!sig.verify(signatureBytes)) return null;

            // Decode and validate payload
            String payloadJson = new String(base64UrlDecode(parts[1]));
            JsonObject payload = JsonParser.parseString(payloadJson).getAsJsonObject();

            // Validate issuer
            String iss = payload.has("iss") ? payload.get("iss").getAsString() : null;
            if (!"accounts.google.com".equals(iss) && !"https://accounts.google.com".equals(iss)) {
                return null;
            }

            // Validate audience
            String aud = payload.has("aud") ? payload.get("aud").getAsString() : null;
            if (!clientId.equals(aud)) return null;

            // Validate expiry
            long exp = payload.has("exp") ? payload.get("exp").getAsLong() : 0;
            if (exp * 1000 < System.currentTimeMillis()) return null;

            // Extract user info
            String sub = payload.has("sub") ? payload.get("sub").getAsString() : null;
            if (sub == null || sub.isEmpty()) return null;

            String email = payload.has("email") ? payload.get("email").getAsString() : null;
            String name = payload.has("name") ? payload.get("name").getAsString() : null;

            return new GoogleUserInfo(sub, email, name);
        } catch (Exception e) {
            return null;
        }
    }

    private RSAPublicKey getKey(String kid) {
        if (keyCache.isEmpty() && !useFixedKeys) {
            refreshKeys();
        }
        return keyCache.get(kid);
    }

    private synchronized void refreshKeys() {
        long now = System.currentTimeMillis();
        if (now - lastKeyFetchTime < 5000) return; // Don't refetch within 5 seconds

        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build();
            HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(GOOGLE_JWKS_URL))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() == 200) {
                parseJwks(response.body());
                lastKeyFetchTime = now;
            }
        } catch (Exception e) {
            // Failed to fetch keys — keep existing cache
        }
    }

    private void parseJwks(String jwksJson) {
        JsonObject jwks = JsonParser.parseString(jwksJson).getAsJsonObject();
        if (!jwks.has("keys")) return;

        Map<String, RSAPublicKey> newKeys = new ConcurrentHashMap<>();
        for (JsonElement keyElem : jwks.getAsJsonArray("keys")) {
            JsonObject key = keyElem.getAsJsonObject();
            String kty = key.has("kty") ? key.get("kty").getAsString() : null;
            String kid = key.has("kid") ? key.get("kid").getAsString() : null;
            String n = key.has("n") ? key.get("n").getAsString() : null;
            String e = key.has("e") ? key.get("e").getAsString() : null;

            if (!"RSA".equals(kty) || kid == null || n == null || e == null) continue;

            try {
                BigInteger modulus = new BigInteger(1, base64UrlDecode(n));
                BigInteger exponent = new BigInteger(1, base64UrlDecode(e));
                RSAPublicKeySpec spec = new RSAPublicKeySpec(modulus, exponent);
                KeyFactory kf = KeyFactory.getInstance("RSA");
                RSAPublicKey publicKey = (RSAPublicKey) kf.generatePublic(spec);
                newKeys.put(kid, publicKey);
            } catch (Exception ex) {
                // Skip malformed keys
            }
        }

        if (!newKeys.isEmpty()) {
            keyCache.clear();
            keyCache.putAll(newKeys);
        }
    }

    private static byte[] base64UrlDecode(String input) {
        // Add padding if needed
        String padded = input;
        switch (padded.length() % 4) {
            case 2: padded += "=="; break;
            case 3: padded += "="; break;
        }
        return Base64.getUrlDecoder().decode(padded);
    }
}
