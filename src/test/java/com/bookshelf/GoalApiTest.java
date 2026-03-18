package com.bookshelf;

import static org.junit.jupiter.api.Assertions.*;

import com.bookshelf.mcp.McpController;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
public class GoalApiTest {

    private static HttpServer server;
    private static HttpClient client;
    private static InMemoryBookRepository bookRepository;
    private static InMemoryGoalRepository goalRepository;
    private static int port;
    private static final Gson gson = new GsonBuilder().serializeNulls().create();

    @BeforeAll
    static void startServer() throws IOException, InterruptedException {
        bookRepository = new InMemoryBookRepository();
        goalRepository = new InMemoryGoalRepository();
        InMemoryShelfRepository shelfRepository = new InMemoryShelfRepository(bookRepository);
        BookController bookController = new BookController(bookRepository, null, shelfRepository);
        ShelfController shelfController = new ShelfController(shelfRepository, bookRepository);
        McpController mcpController = new McpController(bookRepository, shelfRepository);
        GoalController goalController = new GoalController(goalRepository, bookRepository);
        Router router = App.createRouter(bookController, shelfController, mcpController, goalController, null);
        server = new HttpServer(0, router);
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
    void cleanRepositories() {
        bookRepository.clear();
        goalRepository.clear();
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return client.send(
            HttpRequest.newBuilder().uri(URI.create(baseUrl() + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws IOException, InterruptedException {
        return client.send(
            HttpRequest.newBuilder().uri(URI.create(baseUrl() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(String path, String body) throws IOException, InterruptedException {
        return client.send(
            HttpRequest.newBuilder().uri(URI.create(baseUrl() + path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path) throws IOException, InterruptedException {
        return client.send(
            HttpRequest.newBuilder().uri(URI.create(baseUrl() + path)).DELETE().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private void createFinishedBook(String title) throws IOException, InterruptedException {
        JsonObject json = new JsonObject();
        json.addProperty("title", title);
        json.addProperty("author", "Author");
        json.addProperty("readStatus", "FINISHED");
        post("/books", json.toString());
    }

    // --- Tests ---

    @Test
    void t01_createAndRetrieveGoal() throws Exception {
        JsonObject json = new JsonObject();
        json.addProperty("year", 2026);
        json.addProperty("target", 24);
        var res = post("/goals", json.toString());
        assertEquals(201, res.statusCode());
        JsonObject body = JsonParser.parseString(res.body()).getAsJsonObject();
        assertNotNull(body.get("id").getAsString());
        assertEquals(2026, body.get("year").getAsInt());
        assertEquals(24, body.get("target").getAsInt());
        assertEquals(0, body.get("progress").getAsInt());
        assertEquals(0, body.get("percentage").getAsInt());

        var getRes = get("/goals/2026");
        assertEquals(200, getRes.statusCode());
        JsonObject getBody = JsonParser.parseString(getRes.body()).getAsJsonObject();
        assertEquals(2026, getBody.get("year").getAsInt());
        assertEquals(24, getBody.get("target").getAsInt());
    }

    @Test
    void t02_listAllGoals() throws Exception {
        post("/goals", "{\"year\": 2025, \"target\": 10}");
        post("/goals", "{\"year\": 2026, \"target\": 24}");
        var res = get("/goals");
        assertEquals(200, res.statusCode());
        JsonArray arr = JsonParser.parseString(res.body()).getAsJsonArray();
        assertEquals(2, arr.size());
    }

    @Test
    void t03_progressReflectsFinishedBooks() throws Exception {
        int currentYear = java.time.LocalDate.now().getYear();
        post("/goals", "{\"year\": " + currentYear + ", \"target\": 10}");

        createFinishedBook("Book 1");
        createFinishedBook("Book 2");
        createFinishedBook("Book 3");

        var res = get("/goals/" + currentYear);
        assertEquals(200, res.statusCode());
        JsonObject body = JsonParser.parseString(res.body()).getAsJsonObject();
        assertEquals(3, body.get("progress").getAsInt());
        assertEquals(30, body.get("percentage").getAsInt());
    }

    @Test
    void t04_progressExcludesNonFinishedBooks() throws Exception {
        int currentYear = java.time.LocalDate.now().getYear();
        post("/goals", "{\"year\": " + currentYear + ", \"target\": 10}");

        // Create non-finished books
        post("/books", "{\"title\": \"A\", \"author\": \"X\", \"readStatus\": \"READING\"}");
        post("/books", "{\"title\": \"B\", \"author\": \"X\", \"readStatus\": \"WANT_TO_READ\"}");
        post("/books", "{\"title\": \"C\", \"author\": \"X\", \"readStatus\": \"DNF\"}");

        var res = get("/goals/" + currentYear);
        JsonObject body = JsonParser.parseString(res.body()).getAsJsonObject();
        assertEquals(0, body.get("progress").getAsInt());
    }

    @Test
    void t05_updateGoalTarget() throws Exception {
        post("/goals", "{\"year\": 2026, \"target\": 10}");
        var res = put("/goals/2026", "{\"target\": 20}");
        assertEquals(200, res.statusCode());
        JsonObject body = JsonParser.parseString(res.body()).getAsJsonObject();
        assertEquals(20, body.get("target").getAsInt());
    }

    @Test
    void t06_deleteGoal() throws Exception {
        post("/goals", "{\"year\": 2026, \"target\": 10}");
        var res = delete("/goals/2026");
        assertEquals(204, res.statusCode());

        var getRes = get("/goals/2026");
        assertEquals(404, getRes.statusCode());
    }

    @Test
    void t07_duplicateYearReturns409() throws Exception {
        post("/goals", "{\"year\": 2026, \"target\": 10}");
        var res = post("/goals", "{\"year\": 2026, \"target\": 30}");
        assertEquals(409, res.statusCode());
    }

    @Test
    void t08_invalidTargetReturns400() throws Exception {
        var res1 = post("/goals", "{\"year\": 2026, \"target\": 0}");
        assertEquals(400, res1.statusCode());

        var res2 = post("/goals", "{\"year\": 2026, \"target\": -5}");
        assertEquals(400, res2.statusCode());
    }

    @Test
    void t09_invalidYearReturns400() throws Exception {
        var res = post("/goals", "{\"year\": 1999, \"target\": 10}");
        assertEquals(400, res.statusCode());
    }

    @Test
    void t10_goalNotFoundReturns404() throws Exception {
        var res = get("/goals/2099");
        assertEquals(404, res.statusCode());
    }

    @Test
    void t11_onPaceComputation() throws Exception {
        int currentYear = java.time.LocalDate.now().getYear();
        // Set a target of 1 so that even 1 finished book should put us on pace
        post("/goals", "{\"year\": " + currentYear + ", \"target\": 1}");
        createFinishedBook("Done");

        var res = get("/goals/" + currentYear);
        JsonObject body = JsonParser.parseString(res.body()).getAsJsonObject();
        assertTrue(body.get("onPace").getAsBoolean());
        assertTrue(body.get("paceDelta").getAsInt() >= 0);
    }
}
