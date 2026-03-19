package com.bookshelf;

import static org.junit.jupiter.api.Assertions.*;

import com.bookshelf.mcp.McpController;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.*;

@TestMethodOrder(MethodOrderer.MethodName.class)
public class AuthApiTest {

    private static HttpServer server;
    private static HttpClient client;
    private static InMemoryBookRepository bookRepository;
    private static int port;
    private static int userCounter = 0;

    @BeforeAll
    static void startServer() throws IOException, InterruptedException {
        bookRepository = new InMemoryBookRepository();
        InMemoryShelfRepository shelfRepository = new InMemoryShelfRepository(bookRepository);
        BookController bookController = new BookController(bookRepository, null, shelfRepository);
        ShelfController shelfController = new ShelfController(shelfRepository, bookRepository);
        McpController mcpController = new McpController(bookRepository, shelfRepository);
        GoalController goalController = new GoalController(new InMemoryGoalRepository(), bookRepository);
        JwtUtil jwtUtil = new JwtUtil("test-secret");
        InMemoryUserRepository userRepository = new InMemoryUserRepository();
        AuthController authController = new AuthController(userRepository, jwtUtil);
        AuthMiddleware authMiddleware = new AuthMiddleware(jwtUtil);
        Router router = App.createRouter(bookController, shelfController, mcpController, goalController, authController);
        server = new HttpServer(0, router, authMiddleware);
        server.start();
        port = server.getPort();
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        Thread.sleep(100);
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop();
    }

    @BeforeEach
    void cleanRepository() {
        bookRepository.clear();
    }

    // --- Helpers ---

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

    private String nextUsername() {
        return "user" + (++userCounter);
    }

    private String registerAndGetToken(String username) throws IOException, InterruptedException {
        String body = "{\"username\":\"" + username + "\",\"password\":\"password123456\"}";
        HttpResponse<String> resp = post("/auth/register", body);
        assertEquals(201, resp.statusCode(), "Registration should succeed");
        return JsonParser.parseString(resp.body()).getAsJsonObject().get("token").getAsString();
    }

    // --- Tests ---

    @Test
    void t01_testRegisterReturnsTokenAndUser() throws Exception {
        String username = nextUsername();
        String body = "{\"username\":\"" + username + "\",\"password\":\"password123456\"}";
        HttpResponse<String> resp = post("/auth/register", body);

        assertEquals(201, resp.statusCode());
        JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
        assertNotNull(json.get("token"));
        assertFalse(json.get("token").getAsString().isEmpty());
        JsonObject user = json.getAsJsonObject("user");
        assertNotNull(user);
        assertEquals(username, user.get("username").getAsString());
        assertNotNull(user.get("id"));
    }

    @Test
    void t02_testLoginReturnsToken() throws Exception {
        String username = nextUsername();
        String regBody = "{\"username\":\"" + username + "\",\"password\":\"password123456\"}";
        post("/auth/register", regBody);

        String loginBody = "{\"username\":\"" + username + "\",\"password\":\"password123456\"}";
        HttpResponse<String> resp = post("/auth/login", loginBody);

        assertEquals(200, resp.statusCode());
        JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
        assertNotNull(json.get("token"));
        assertFalse(json.get("token").getAsString().isEmpty());
    }

    @Test
    void t03_testRegisterDuplicateUsername() throws Exception {
        String username = nextUsername();
        String body = "{\"username\":\"" + username + "\",\"password\":\"password123456\"}";
        post("/auth/register", body);

        HttpResponse<String> resp = post("/auth/register", body);
        assertEquals(409, resp.statusCode());
    }

    @Test
    void t04_testLoginWrongPassword() throws Exception {
        String username = nextUsername();
        post("/auth/register", "{\"username\":\"" + username + "\",\"password\":\"password123456\"}");

        HttpResponse<String> resp = post("/auth/login",
            "{\"username\":\"" + username + "\",\"password\":\"wrongpassword\"}");
        assertEquals(401, resp.statusCode());
    }

    @Test
    void t05_testLoginNonExistentUser() throws Exception {
        HttpResponse<String> resp = post("/auth/login",
            "{\"username\":\"nonexistent\",\"password\":\"password123456\"}");
        assertEquals(401, resp.statusCode());
    }

    @Test
    void t06_testProtectedEndpointWithoutToken() throws Exception {
        String bookBody = "{\"title\":\"Dune\",\"author\":\"Frank Herbert\",\"readStatus\":\"READING\"}";
        HttpResponse<String> resp = post("/books", bookBody);
        assertEquals(401, resp.statusCode());
    }

    @Test
    void t07_testProtectedEndpointWithValidToken() throws Exception {
        String token = registerAndGetToken(nextUsername());
        String bookBody = "{\"title\":\"Dune\",\"author\":\"Frank Herbert\",\"readStatus\":\"READING\"}";
        HttpResponse<String> resp = postWithAuth("/books", bookBody, token);
        assertEquals(201, resp.statusCode());
    }

    @Test
    void t08_testProtectedEndpointWithInvalidToken() throws Exception {
        String bookBody = "{\"title\":\"Dune\",\"author\":\"Frank Herbert\",\"readStatus\":\"READING\"}";
        HttpResponse<String> resp = postWithAuth("/books", bookBody, "invalidtoken");
        assertEquals(401, resp.statusCode());
    }

    @Test
    void t09_testProtectedEndpointWithTamperedToken() throws Exception {
        String token = registerAndGetToken(nextUsername());
        // Tamper with the signature
        String tampered = token.substring(0, token.lastIndexOf('.') + 1) + "tampered";
        String bookBody = "{\"title\":\"Dune\",\"author\":\"Frank Herbert\",\"readStatus\":\"READING\"}";
        HttpResponse<String> resp = postWithAuth("/books", bookBody, tampered);
        assertEquals(401, resp.statusCode());
    }

    @Test
    void t10_testGetEndpointsWorkWithoutAuth() throws Exception {
        HttpResponse<String> resp = get("/books");
        assertEquals(200, resp.statusCode());
    }

    @Test
    void t11_testRegisterWeakPassword() throws Exception {
        String body = "{\"username\":\"" + nextUsername() + "\",\"password\":\"short\"}";
        HttpResponse<String> resp = post("/auth/register", body);
        assertEquals(400, resp.statusCode());
    }

    @Test
    void t12_testRegisterShortUsername() throws Exception {
        String body = "{\"username\":\"ab\",\"password\":\"password123456\"}";
        HttpResponse<String> resp = post("/auth/register", body);
        assertEquals(400, resp.statusCode());
    }
}
