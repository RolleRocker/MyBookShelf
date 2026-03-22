package com.bookshelf;

import static org.junit.jupiter.api.Assertions.*;

import com.bookshelf.adapter.in.http.AuthController;
import com.bookshelf.adapter.in.http.BookController;
import com.bookshelf.adapter.in.http.GoalController;
import com.bookshelf.adapter.in.http.ShelfController;
import com.bookshelf.adapter.in.mcp.McpController;
import com.bookshelf.adapter.out.auth.GoogleTokenVerifier;
import com.bookshelf.adapter.out.auth.JwtUtil;
import com.bookshelf.adapter.out.persistence.InMemoryBookRepository;
import com.bookshelf.adapter.out.persistence.InMemoryGoalRepository;
import com.bookshelf.adapter.out.persistence.InMemoryShelfRepository;
import com.bookshelf.adapter.out.persistence.InMemoryUserRepository;
import com.bookshelf.framework.http.AuthMiddleware;
import com.bookshelf.framework.http.HttpServer;
import com.bookshelf.framework.http.Router;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.*;

@TestMethodOrder(MethodOrderer.MethodName.class)
public class GoogleAuthApiTest {

    private static HttpServer server;
    private static HttpClient client;
    private static InMemoryBookRepository bookRepository;
    private static InMemoryUserRepository userRepository;
    private static int port;
    private static KeyPair rsaKeyPair;
    private static final String TEST_KID = "test-key-1";
    private static final String TEST_CLIENT_ID = "test-client-id.apps.googleusercontent.com";

    @BeforeAll
    static void startServer() throws Exception {
        // Generate RSA key pair for signing test tokens
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        rsaKeyPair = kpg.generateKeyPair();

        bookRepository = new InMemoryBookRepository();
        userRepository = new InMemoryUserRepository();
        InMemoryShelfRepository shelfRepository = new InMemoryShelfRepository(bookRepository);
        BookController bookController = new BookController(bookRepository, null, shelfRepository);
        ShelfController shelfController = new ShelfController(shelfRepository, bookRepository);
        McpController mcpController = new McpController(bookRepository, shelfRepository);
        GoalController goalController = new GoalController(new InMemoryGoalRepository(), bookRepository);
        JwtUtil jwtUtil = new JwtUtil("test-secret");
        GoogleTokenVerifier verifier = new GoogleTokenVerifier(
            TEST_CLIENT_ID,
            Map.of(TEST_KID, (RSAPublicKey) rsaKeyPair.getPublic())
        );
        AuthController authController = new AuthController(
            userRepository, jwtUtil, verifier, TEST_CLIENT_ID);
        AuthMiddleware authMiddleware = new AuthMiddleware(jwtUtil);
        Router router = App.createRouter(bookController, shelfController, mcpController, goalController, authController);
        server = new HttpServer(0, router, authMiddleware);
        server.start();
        port = server.getPort();
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        waitForServer(port);
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop();
    }

    @BeforeEach
    void clean() {
        bookRepository.clear();
    }

    // --- Helpers ---

    private static void waitForServer(int port) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            try (java.net.Socket s = new java.net.Socket("localhost", port)) {
                return;
            } catch (IOException e) {
                Thread.sleep(50);
            }
        }
        throw new RuntimeException("Server did not start within 2.5s on port " + port);
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private HttpResponse<String> post(String path, String body) throws IOException, InterruptedException {
        return client.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postWithAuth(String path, String body, String token) throws IOException, InterruptedException {
        return client.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return client.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    }

    /** Build a Google-format ID token signed with our test RSA key. */
    private String buildGoogleIdToken(String sub, String email, String name, long expSeconds) throws Exception {
        JsonObject header = new JsonObject();
        header.addProperty("alg", "RS256");
        header.addProperty("typ", "JWT");
        header.addProperty("kid", TEST_KID);

        JsonObject payload = new JsonObject();
        payload.addProperty("iss", "accounts.google.com");
        payload.addProperty("aud", TEST_CLIENT_ID);
        payload.addProperty("sub", sub);
        if (email != null) payload.addProperty("email", email);
        if (name != null) payload.addProperty("name", name);
        payload.addProperty("iat", System.currentTimeMillis() / 1000);
        payload.addProperty("exp", expSeconds);

        String headerB64 = base64UrlEncode(header.toString().getBytes(StandardCharsets.UTF_8));
        String payloadB64 = base64UrlEncode(payload.toString().getBytes(StandardCharsets.UTF_8));
        String signingInput = headerB64 + "." + payloadB64;

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(rsaKeyPair.getPrivate());
        sig.update(signingInput.getBytes(StandardCharsets.UTF_8));
        byte[] signature = sig.sign();
        String signatureB64 = base64UrlEncode(signature);

        return signingInput + "." + signatureB64;
    }

    private String buildValidGoogleIdToken(String sub, String email, String name) throws Exception {
        long exp = System.currentTimeMillis() / 1000 + 3600; // 1 hour from now
        return buildGoogleIdToken(sub, email, name, exp);
    }

    private static String base64UrlEncode(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    // --- Tests ---

    @Test
    void t01_googleLoginCreatesNewUser() throws Exception {
        String token = buildValidGoogleIdToken("google-123", "alice@gmail.com", "Alice Smith");
        HttpResponse<String> resp = post("/auth/google", "{\"credential\":\"" + token + "\"}");

        assertEquals(201, resp.statusCode());
        JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
        assertNotNull(json.get("token"));
        assertFalse(json.get("token").getAsString().isEmpty());
        JsonObject user = json.getAsJsonObject("user");
        assertEquals("alice", user.get("username").getAsString());
        assertNotNull(user.get("id"));
    }

    @Test
    void t02_googleLoginReturnsExistingUser() throws Exception {
        String token1 = buildValidGoogleIdToken("google-456", "bob@gmail.com", "Bob Jones");
        HttpResponse<String> resp1 = post("/auth/google", "{\"credential\":\"" + token1 + "\"}");
        assertEquals(201, resp1.statusCode());
        String userId = JsonParser.parseString(resp1.body()).getAsJsonObject()
            .getAsJsonObject("user").get("id").getAsString();

        // Second login with same Google ID
        String token2 = buildValidGoogleIdToken("google-456", "bob@gmail.com", "Bob Jones");
        HttpResponse<String> resp2 = post("/auth/google", "{\"credential\":\"" + token2 + "\"}");
        assertEquals(200, resp2.statusCode());
        String userId2 = JsonParser.parseString(resp2.body()).getAsJsonObject()
            .getAsJsonObject("user").get("id").getAsString();
        assertEquals(userId, userId2);
    }

    @Test
    void t03_googleTokenWorksOnProtectedEndpoints() throws Exception {
        String googleToken = buildValidGoogleIdToken("google-789", "carol@gmail.com", "Carol");
        HttpResponse<String> loginResp = post("/auth/google", "{\"credential\":\"" + googleToken + "\"}");
        assertEquals(201, loginResp.statusCode());
        String jwtToken = JsonParser.parseString(loginResp.body()).getAsJsonObject()
            .get("token").getAsString();

        // Use the JWT to create a book
        String bookBody = "{\"title\":\"Dune\",\"author\":\"Frank Herbert\",\"readStatus\":\"READING\"}";
        HttpResponse<String> bookResp = postWithAuth("/books", bookBody, jwtToken);
        assertEquals(201, bookResp.statusCode());
    }

    @Test
    void t04_missingCredentialReturns400() throws Exception {
        HttpResponse<String> resp = post("/auth/google", "{}");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void t05_emptyCredentialReturns400() throws Exception {
        HttpResponse<String> resp = post("/auth/google", "{\"credential\":\"\"}");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void t06_invalidTokenReturns400() throws Exception {
        HttpResponse<String> resp = post("/auth/google", "{\"credential\":\"not.a.valid.token\"}");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void t07_expiredTokenReturns400() throws Exception {
        long expiredExp = System.currentTimeMillis() / 1000 - 3600; // 1 hour ago
        String token = buildGoogleIdToken("google-expired", "expired@gmail.com", "Expired", expiredExp);
        HttpResponse<String> resp = post("/auth/google", "{\"credential\":\"" + token + "\"}");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void t08_tamperedTokenReturns400() throws Exception {
        String token = buildValidGoogleIdToken("google-tamper", "tamper@gmail.com", "Tamper");
        // Tamper with the signature
        String tampered = token.substring(0, token.lastIndexOf('.') + 1) + "tampered-signature";
        HttpResponse<String> resp = post("/auth/google", "{\"credential\":\"" + tampered + "\"}");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void t09_differentGoogleAccountsCreateDifferentUsers() throws Exception {
        String token1 = buildValidGoogleIdToken("google-aaa", "user1@gmail.com", "User One");
        String token2 = buildValidGoogleIdToken("google-bbb", "user2@gmail.com", "User Two");

        HttpResponse<String> resp1 = post("/auth/google", "{\"credential\":\"" + token1 + "\"}");
        HttpResponse<String> resp2 = post("/auth/google", "{\"credential\":\"" + token2 + "\"}");

        assertEquals(201, resp1.statusCode());
        assertEquals(201, resp2.statusCode());

        String id1 = JsonParser.parseString(resp1.body()).getAsJsonObject()
            .getAsJsonObject("user").get("id").getAsString();
        String id2 = JsonParser.parseString(resp2.body()).getAsJsonObject()
            .getAsJsonObject("user").get("id").getAsString();
        assertNotEquals(id1, id2);
    }

    @Test
    void t10_usernameDerivationHandlesDuplicates() throws Exception {
        // First user gets "dave"
        String token1 = buildValidGoogleIdToken("google-dave1", "dave@gmail.com", "Dave");
        HttpResponse<String> resp1 = post("/auth/google", "{\"credential\":\"" + token1 + "\"}");
        assertEquals(201, resp1.statusCode());
        String username1 = JsonParser.parseString(resp1.body()).getAsJsonObject()
            .getAsJsonObject("user").get("username").getAsString();
        assertEquals("dave", username1);

        // Second user with same email prefix gets "dave2"
        String token2 = buildValidGoogleIdToken("google-dave2", "dave@outlook.com", "Dave Other");
        HttpResponse<String> resp2 = post("/auth/google", "{\"credential\":\"" + token2 + "\"}");
        assertEquals(201, resp2.statusCode());
        String username2 = JsonParser.parseString(resp2.body()).getAsJsonObject()
            .getAsJsonObject("user").get("username").getAsString();
        assertEquals("dave2", username2);
    }

    @Test
    void t11_authConfigReturnsGoogleClientId() throws Exception {
        HttpResponse<String> resp = get("/auth/config");
        assertEquals(200, resp.statusCode());
        JsonObject config = JsonParser.parseString(resp.body()).getAsJsonObject();
        assertEquals(TEST_CLIENT_ID, config.get("googleClientId").getAsString());
    }
}
