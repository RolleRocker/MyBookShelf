package com.bookshelf;

import static org.junit.jupiter.api.Assertions.*;

import com.bookshelf.adapter.in.http.AuthController;
import com.bookshelf.adapter.in.http.BookController;
import com.bookshelf.adapter.in.http.GoalController;
import com.bookshelf.adapter.in.http.ShelfController;
import com.bookshelf.adapter.in.mcp.McpController;
import com.bookshelf.adapter.out.auth.JwtUtil;
import com.bookshelf.adapter.out.persistence.InMemoryBookRepository;
import com.bookshelf.adapter.out.persistence.InMemoryGoalRepository;
import com.bookshelf.adapter.out.persistence.InMemoryShelfRepository;
import com.bookshelf.adapter.out.persistence.InMemoryUserRepository;
import com.bookshelf.framework.http.AuthMiddleware;
import com.bookshelf.framework.http.HttpServer;
import com.bookshelf.framework.http.Router;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.*;

@TestMethodOrder(MethodOrderer.MethodName.class)
public class McpTest {

    private static HttpServer server;
    private static HttpClient client;
    private static InMemoryBookRepository repository;
    private static InMemoryShelfRepository shelfRepository;
    private static int port;
    private static String authToken;

    @BeforeAll
    static void startServer() throws IOException, InterruptedException {
        repository = new InMemoryBookRepository();
        shelfRepository = new InMemoryShelfRepository(repository);
        BookController controller = new BookController(repository, null, shelfRepository);
        ShelfController shelfController = new ShelfController(shelfRepository, repository);
        McpController mcpController = new McpController(repository, shelfRepository);
        GoalController goalController = new GoalController(new InMemoryGoalRepository(), repository);
        JwtUtil jwtUtil = new JwtUtil("test-secret");
        InMemoryUserRepository userRepository = new InMemoryUserRepository();
        AuthController authController = new AuthController(userRepository, jwtUtil);
        AuthMiddleware authMiddleware = new AuthMiddleware(
            jwtUtil,
            java.util.Set.of("/health", "/auth/config"),
            java.util.Set.of("/auth/register", "/auth/login", "/auth/google", "/mcp"),
            java.util.Set.of("/books", "/shelves", "/goals", "/auth", "/mcp")
        );
        Router router = App.createRouter(controller, shelfController, mcpController, goalController, authController);
        server = new HttpServer(0, router, authMiddleware);
        server.start();
        port = server.getPort();
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        waitForServer(port);
        // Register test user and get auth token
        String regBody = "{\"username\":\"testuser\",\"password\":\"testpassword123\"}";
        var regResp = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/auth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(regBody))
                .build(),
            HttpResponse.BodyHandlers.ofString());
        authToken = JsonParser.parseString(regResp.body()).getAsJsonObject().get("token").getAsString();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop();
    }

    private static void waitForServer(int port) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            try (java.net.Socket s = new java.net.Socket("localhost", port)) {
                return;
            } catch (java.io.IOException e) {
                Thread.sleep(50);
            }
        }
        throw new RuntimeException("Server did not start within 2.5s on port " + port);
    }

    @BeforeEach
    void cleanRepository() {
        repository.clear();
        shelfRepository.clear();
    }

    // --- Helper methods ---

    private HttpResponse<String> mcpPost(String jsonBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String jsonRpc(int id, String method, String paramsJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + paramsJson + "}";
    }

    private String toolCall(int id, String toolName, String argsJson) {
        return jsonRpc(id, "tools/call", "{\"name\":\"" + toolName + "\",\"arguments\":" + argsJson + "}");
    }

    private String getToolResultText(HttpResponse<String> response) {
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        return json.getAsJsonObject("result")
                .getAsJsonArray("content")
                .get(0).getAsJsonObject()
                .get("text").getAsString();
    }

    private void addBook(String title, String author, String readStatus, int rating) throws Exception {
        JsonObject book = new JsonObject();
        book.addProperty("title", title);
        book.addProperty("author", author);
        book.addProperty("readStatus", readStatus);
        if (rating > 0) book.addProperty("rating", rating);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/books"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + authToken)
                .POST(HttpRequest.BodyPublishers.ofString(book.toString()))
                .build();
        client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void addBookWithIsbn(String title, String author, String isbn, String readStatus) throws Exception {
        JsonObject book = new JsonObject();
        book.addProperty("title", title);
        book.addProperty("author", author);
        book.addProperty("isbn", isbn);
        book.addProperty("readStatus", readStatus);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/books"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + authToken)
                .POST(HttpRequest.BodyPublishers.ofString(book.toString()))
                .build();
        client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    // --- Protocol tests ---

    @Test
    void t01_testInitialize() throws Exception {
        String body = jsonRpc(1, "initialize", "{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1.0\"}}");
        HttpResponse<String> response = mcpPost(body);
        assertEquals(200, response.statusCode());

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        assertEquals("2.0", json.get("jsonrpc").getAsString());
        assertEquals(1, json.get("id").getAsInt());

        JsonObject result = json.getAsJsonObject("result");
        assertEquals("2025-03-26", result.get("protocolVersion").getAsString());
        assertNotNull(result.getAsJsonObject("capabilities").getAsJsonObject("tools"));

        JsonObject serverInfo = result.getAsJsonObject("serverInfo");
        assertEquals("bookshelf-mcp", serverInfo.get("name").getAsString());
        assertEquals("1.0.0", serverInfo.get("version").getAsString());
    }

    @Test
    void t02_testInitializedNotification() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
        HttpResponse<String> response = mcpPost(body);
        assertEquals(202, response.statusCode());
    }

    @Test
    void t03_testToolsList() throws Exception {
        String body = jsonRpc(2, "tools/list", "{}");
        HttpResponse<String> response = mcpPost(body);
        assertEquals(200, response.statusCode());

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonArray tools = json.getAsJsonObject("result").getAsJsonArray("tools");
        assertEquals(5, tools.size());

        // Verify all tool names are present
        var toolNames = new java.util.HashSet<String>();
        for (var tool : tools) {
            toolNames.add(tool.getAsJsonObject().get("name").getAsString());
            // Each tool should have description and inputSchema
            assertNotNull(tool.getAsJsonObject().get("description"));
            assertNotNull(tool.getAsJsonObject().get("inputSchema"));
        }
        assertTrue(toolNames.contains("check_book"));
        assertTrue(toolNames.contains("search_books"));
        assertTrue(toolNames.contains("list_books"));
        assertTrue(toolNames.contains("get_book_by_isbn"));
        assertTrue(toolNames.contains("get_bookshelf_stats"));
    }

    @Test
    void t04_testPing() throws Exception {
        String body = jsonRpc(9, "ping", "{}");
        HttpResponse<String> response = mcpPost(body);
        assertEquals(200, response.statusCode());

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        assertNotNull(json.getAsJsonObject("result"));
    }

    @Test
    void t05_testUnknownMethod() throws Exception {
        String body = jsonRpc(10, "unknown/method", "{}");
        HttpResponse<String> response = mcpPost(body);
        assertEquals(200, response.statusCode());

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonObject error = json.getAsJsonObject("error");
        assertEquals(-32601, error.get("code").getAsInt());
        assertTrue(error.get("message").getAsString().contains("Method not found"));
    }

    @Test
    void t06_testInvalidJson() throws Exception {
        HttpResponse<String> response = mcpPost("not valid json {{{");
        assertEquals(200, response.statusCode());

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonObject error = json.getAsJsonObject("error");
        assertEquals(-32700, error.get("code").getAsInt());
    }

    @Test
    void t07_testEmptyBody() throws Exception {
        HttpResponse<String> response = mcpPost("");
        assertEquals(200, response.statusCode());

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonObject error = json.getAsJsonObject("error");
        assertEquals(-32700, error.get("code").getAsInt());
    }

    // --- Tool: check_book ---

    @Test
    void t10_testCheckBookByIsbn() throws Exception {
        addBookWithIsbn("Dune", "Frank Herbert", "9780441013593", "FINISHED");

        String body = toolCall(3, "check_book", "{\"title\":\"Dune\",\"isbn\":\"9780441013593\"}");
        HttpResponse<String> response = mcpPost(body);
        String text = getToolResultText(response);
        assertTrue(text.contains("Dune"));
        assertTrue(text.contains("Frank Herbert"));
        assertTrue(text.contains("on your shelf"));
    }

    @Test
    void t11_testCheckBookByTitle() throws Exception {
        addBook("Dune", "Frank Herbert", "FINISHED", 5);

        String body = toolCall(3, "check_book", "{\"title\":\"Dune\"}");
        HttpResponse<String> response = mcpPost(body);
        String text = getToolResultText(response);
        assertTrue(text.contains("Dune"));
        assertTrue(text.contains("Frank Herbert"));
    }

    @Test
    void t12_testCheckBookByTitleAndAuthor() throws Exception {
        addBook("Dune", "Frank Herbert", "FINISHED", 5);
        addBook("Dune Messiah", "Frank Herbert", "WANT_TO_READ", 0);

        String body = toolCall(3, "check_book", "{\"title\":\"Dune\",\"author\":\"Frank Herbert\"}");
        HttpResponse<String> response = mcpPost(body);
        String text = getToolResultText(response);
        assertTrue(text.contains("Dune"));
        assertTrue(text.contains("Frank Herbert"));
    }

    @Test
    void t13_testCheckBookNotFound() throws Exception {
        String body = toolCall(3, "check_book", "{\"title\":\"Nonexistent Book\"}");
        HttpResponse<String> response = mcpPost(body);
        String text = getToolResultText(response);
        assertTrue(text.toLowerCase().contains("no book found"));
    }

    // --- Tool: search_books ---

    @Test
    void t20_testSearchBooks() throws Exception {
        addBook("Dune", "Frank Herbert", "FINISHED", 5);
        addBook("Project Hail Mary", "Andy Weir", "READING", 4);

        String body = toolCall(4, "search_books", "{\"query\":\"dune\"}");
        HttpResponse<String> response = mcpPost(body);
        String text = getToolResultText(response);
        assertTrue(text.contains("Dune"));
        assertTrue(text.contains("1 book(s)"));
    }

    @Test
    void t21_testSearchBooksNoResults() throws Exception {
        String body = toolCall(4, "search_books", "{\"query\":\"zzzzz\"}");
        HttpResponse<String> response = mcpPost(body);
        String text = getToolResultText(response);
        assertTrue(text.toLowerCase().contains("no books found"));
    }

    // --- Tool: list_books ---

    @Test
    void t30_testListBooksAll() throws Exception {
        addBook("Dune", "Frank Herbert", "FINISHED", 5);
        addBook("Project Hail Mary", "Andy Weir", "READING", 4);

        String body = toolCall(5, "list_books", "{}");
        HttpResponse<String> response = mcpPost(body);
        String text = getToolResultText(response);
        assertTrue(text.contains("2 book(s)"));
        assertTrue(text.contains("Dune"));
        assertTrue(text.contains("Project Hail Mary"));
    }

    @Test
    void t31_testListBooksByStatus() throws Exception {
        addBook("Dune", "Frank Herbert", "FINISHED", 5);
        addBook("Project Hail Mary", "Andy Weir", "READING", 4);

        String body = toolCall(5, "list_books", "{\"readStatus\":\"READING\"}");
        HttpResponse<String> response = mcpPost(body);
        String text = getToolResultText(response);
        assertTrue(text.contains("1 book(s)"));
        assertTrue(text.contains("Project Hail Mary"));
        assertFalse(text.contains("Dune"));
    }

    @Test
    void t32_testListBooksInvalidStatus() throws Exception {
        String body = toolCall(5, "list_books", "{\"readStatus\":\"INVALID\"}");
        HttpResponse<String> response = mcpPost(body);
        String text = getToolResultText(response);
        assertTrue(text.contains("Invalid readStatus"));
    }

    // --- Tool: get_book_by_isbn ---

    @Test
    void t40_testGetBookByIsbn() throws Exception {
        addBookWithIsbn("Dune", "Frank Herbert", "9780441013593", "FINISHED");

        String body = toolCall(6, "get_book_by_isbn", "{\"isbn\":\"9780441013593\"}");
        HttpResponse<String> response = mcpPost(body);
        String text = getToolResultText(response);
        assertTrue(text.contains("Dune"));
        assertTrue(text.contains("Frank Herbert"));
        assertTrue(text.contains("9780441013593"));
    }

    @Test
    void t41_testGetBookByIsbnNotFound() throws Exception {
        String body = toolCall(6, "get_book_by_isbn", "{\"isbn\":\"0000000000\"}");
        HttpResponse<String> response = mcpPost(body);
        String text = getToolResultText(response);
        assertTrue(text.toLowerCase().contains("no book found"));
    }

    // --- Tool: get_bookshelf_stats ---

    @Test
    void t50_testGetBookshelfStats() throws Exception {
        addBook("Dune", "Frank Herbert", "FINISHED", 5);
        addBook("Project Hail Mary", "Andy Weir", "READING", 4);
        addBook("The Hobbit", "J.R.R. Tolkien", "WANT_TO_READ", 0);

        String body = toolCall(7, "get_bookshelf_stats", "{}");
        HttpResponse<String> response = mcpPost(body);
        String text = getToolResultText(response);
        assertTrue(text.contains("3 book(s)"));
        assertTrue(text.contains("Want to read: 1"));
        assertTrue(text.contains("Reading: 1"));
        assertTrue(text.contains("Finished: 1"));
        assertTrue(text.contains("Average rating"));
    }

    @Test
    void t51_testGetBookshelfStatsEmpty() throws Exception {
        String body = toolCall(7, "get_bookshelf_stats", "{}");
        HttpResponse<String> response = mcpPost(body);
        String text = getToolResultText(response);
        assertTrue(text.toLowerCase().contains("empty"));
    }

    // --- Tool error handling ---

    @Test
    void t60_testUnknownTool() throws Exception {
        String body = toolCall(8, "nonexistent_tool", "{}");
        HttpResponse<String> response = mcpPost(body);

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonObject result = json.getAsJsonObject("result");
        assertTrue(result.get("isError").getAsBoolean());
        String text = result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
        assertTrue(text.contains("Unknown tool"));
    }
}
