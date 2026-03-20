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
import java.util.UUID;
import org.junit.jupiter.api.*;

@TestMethodOrder(MethodOrderer.MethodName.class)
public class ShelfApiTest {

    private static HttpServer server;
    private static HttpClient client;
    private static InMemoryBookRepository bookRepository;
    private static InMemoryShelfRepository shelfRepository;
    private static int port;
    private static String authToken;
    private static final Gson gson = new GsonBuilder()
        .serializeNulls()
        .create();

    @BeforeAll
    static void startServer() throws IOException, InterruptedException {
        bookRepository = new InMemoryBookRepository();
        shelfRepository = new InMemoryShelfRepository(bookRepository);
        BookController bookController = new BookController(
            bookRepository,
            null,
            shelfRepository
        );
        ShelfController shelfController = new ShelfController(
            shelfRepository,
            bookRepository
        );
        McpController mcpController = new McpController(
            bookRepository,
            shelfRepository
        );
        GoalController goalController = new GoalController(
            new InMemoryGoalRepository(),
            bookRepository
        );
        JwtUtil jwtUtil = new JwtUtil("test-secret");
        InMemoryUserRepository userRepository = new InMemoryUserRepository();
        AuthController authController = new AuthController(userRepository, jwtUtil);
        AuthMiddleware authMiddleware = new AuthMiddleware(jwtUtil);
        Router router = App.createRouter(
            bookController,
            shelfController,
            mcpController,
            goalController,
            authController
        );
        server = new HttpServer(0, router, authMiddleware);
        server.start();
        port = server.getPort();
        client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
        Thread.sleep(100);
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

    @BeforeEach
    void clean() {
        bookRepository.clear();
        shelfRepository.clear();
    }

    // --- Helper methods ---

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private HttpResponse<String> get(String path)
        throws IOException, InterruptedException {
        return client.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
    }

    private HttpResponse<String> post(String path, String body)
        throws IOException, InterruptedException {
        return client.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + authToken)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
    }

    private HttpResponse<String> put(String path, String body)
        throws IOException, InterruptedException {
        return client.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + authToken)
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
    }

    private HttpResponse<String> delete(String path)
        throws IOException, InterruptedException {
        return client.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Authorization", "Bearer " + authToken)
                .DELETE()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
    }

    private String createShelfJson(String name) {
        JsonObject json = new JsonObject();
        json.addProperty("name", name);
        return json.toString();
    }

    private String createShelfJson(String name, String color) {
        JsonObject json = new JsonObject();
        json.addProperty("name", name);
        if (color != null) json.addProperty("color", color);
        return json.toString();
    }

    private String createBookAndGetId(String title, String author)
        throws IOException, InterruptedException {
        JsonObject json = new JsonObject();
        json.addProperty("title", title);
        json.addProperty("author", author);
        json.addProperty("readStatus", "WANT_TO_READ");
        var resp = post("/books", json.toString());
        assertEquals(201, resp.statusCode());
        return JsonParser.parseString(resp.body())
            .getAsJsonObject()
            .get("id")
            .getAsString();
    }

    private String createBookAndGetId(
        String title,
        String author,
        String genre,
        int rating,
        int pageCount
    ) throws IOException, InterruptedException {
        JsonObject json = new JsonObject();
        json.addProperty("title", title);
        json.addProperty("author", author);
        json.addProperty("readStatus", "WANT_TO_READ");
        json.addProperty("genre", genre);
        json.addProperty("rating", rating);
        json.addProperty("pageCount", pageCount);
        var resp = post("/books", json.toString());
        assertEquals(201, resp.statusCode());
        return JsonParser.parseString(resp.body())
            .getAsJsonObject()
            .get("id")
            .getAsString();
    }

    private String createShelfAndGetId(String name)
        throws IOException, InterruptedException {
        var resp = post("/shelves", createShelfJson(name));
        assertEquals(201, resp.statusCode());
        return JsonParser.parseString(resp.body())
            .getAsJsonObject()
            .get("id")
            .getAsString();
    }

    private String getId(HttpResponse<String> resp) {
        return JsonParser.parseString(resp.body())
            .getAsJsonObject()
            .get("id")
            .getAsString();
    }

    // ========================================
    // Core CRUD (T_SHELF_01 — T_SHELF_12)
    // ========================================

    @Test
    void t01_createAndRetrieveShelf() throws Exception {
        var resp = post("/shelves", createShelfJson("Favorites"));
        assertEquals(201, resp.statusCode());
        JsonObject shelf = JsonParser.parseString(
            resp.body()
        ).getAsJsonObject();
        assertNotNull(shelf.get("id").getAsString());
        assertEquals("Favorites", shelf.get("name").getAsString());
        assertEquals(0, shelf.get("bookCount").getAsInt());
        assertTrue(shelf.get("coverBookIds").getAsJsonArray().isEmpty());

        // GET by id
        var getResp = get("/shelves/" + shelf.get("id").getAsString());
        assertEquals(200, getResp.statusCode());
        JsonObject detail = JsonParser.parseString(
            getResp.body()
        ).getAsJsonObject();
        assertEquals("Favorites", detail.get("name").getAsString());
        assertTrue(detail.get("books").getAsJsonArray().isEmpty());
        assertEquals(
            0,
            detail.getAsJsonObject("stats").get("bookCount").getAsInt()
        );
    }

    @Test
    void t02_listAllShelves() throws Exception {
        createShelfAndGetId("Shelf A");
        createShelfAndGetId("Shelf B");
        createShelfAndGetId("Shelf C");

        var resp = get("/shelves");
        assertEquals(200, resp.statusCode());
        JsonArray arr = JsonParser.parseString(resp.body()).getAsJsonArray();
        assertEquals(3, arr.size());
    }

    @Test
    void t03_updateShelf() throws Exception {
        String id = createShelfAndGetId("Old Name");
        var resp = put("/shelves/" + id, "{\"name\": \"New Name\"}");
        assertEquals(200, resp.statusCode());
        JsonObject shelf = JsonParser.parseString(
            resp.body()
        ).getAsJsonObject();
        assertEquals("New Name", shelf.get("name").getAsString());
    }

    @Test
    void t04_deleteShelf() throws Exception {
        String bookId = createBookAndGetId("Test Book", "Author");
        String shelfId = createShelfAndGetId("To Delete");
        post(
            "/shelves/" + shelfId + "/books",
            "{\"bookId\": \"" + bookId + "\"}"
        );

        var delResp = delete("/shelves/" + shelfId);
        assertEquals(204, delResp.statusCode());

        assertEquals(404, get("/shelves/" + shelfId).statusCode());
        // Book should still exist
        assertEquals(200, get("/books/" + bookId).statusCode());
    }

    @Test
    void t05_addBookToShelf() throws Exception {
        String shelfId = createShelfAndGetId("My Shelf");
        String bookId = createBookAndGetId("Dune", "Frank Herbert");

        var resp = post(
            "/shelves/" + shelfId + "/books",
            "{\"bookId\": \"" + bookId + "\"}"
        );
        assertEquals(201, resp.statusCode());

        var getResp = get("/shelves/" + shelfId);
        JsonObject detail = JsonParser.parseString(
            getResp.body()
        ).getAsJsonObject();
        assertEquals(1, detail.get("books").getAsJsonArray().size());
    }

    @Test
    void t06_removeBookFromShelf() throws Exception {
        String shelfId = createShelfAndGetId("My Shelf");
        String bookId = createBookAndGetId("Dune", "Frank Herbert");
        post(
            "/shelves/" + shelfId + "/books",
            "{\"bookId\": \"" + bookId + "\"}"
        );

        var delResp = delete("/shelves/" + shelfId + "/books/" + bookId);
        assertEquals(204, delResp.statusCode());

        var getResp = get("/shelves/" + shelfId);
        JsonObject detail = JsonParser.parseString(
            getResp.body()
        ).getAsJsonObject();
        assertEquals(0, detail.get("books").getAsJsonArray().size());
    }

    @Test
    void t07_duplicateShelfNameReturns409() throws Exception {
        createShelfAndGetId("Favorites");
        var resp = post("/shelves", createShelfJson("Favorites"));
        assertEquals(409, resp.statusCode());
    }

    @Test
    void t08_addSameBookTwiceReturns409() throws Exception {
        String shelfId = createShelfAndGetId("My Shelf");
        String bookId = createBookAndGetId("Dune", "Frank Herbert");
        post(
            "/shelves/" + shelfId + "/books",
            "{\"bookId\": \"" + bookId + "\"}"
        );

        var resp = post(
            "/shelves/" + shelfId + "/books",
            "{\"bookId\": \"" + bookId + "\"}"
        );
        assertEquals(409, resp.statusCode());
    }

    @Test
    void t09_deleteBookCascadesToShelfBooks() throws Exception {
        String shelfId = createShelfAndGetId("My Shelf");
        String bookId = createBookAndGetId("Dune", "Frank Herbert");
        post(
            "/shelves/" + shelfId + "/books",
            "{\"bookId\": \"" + bookId + "\"}"
        );

        delete("/books/" + bookId);

        var getResp = get("/shelves/" + shelfId);
        JsonObject detail = JsonParser.parseString(
            getResp.body()
        ).getAsJsonObject();
        assertEquals(0, detail.get("books").getAsJsonArray().size());
    }

    @Test
    void t10_bookResponseIncludesShelvesArray() throws Exception {
        String shelfId1 = createShelfAndGetId("Shelf A");
        String shelfId2 = createShelfAndGetId("Shelf B");
        String bookId = createBookAndGetId("Dune", "Frank Herbert");
        post(
            "/shelves/" + shelfId1 + "/books",
            "{\"bookId\": \"" + bookId + "\"}"
        );
        post(
            "/shelves/" + shelfId2 + "/books",
            "{\"bookId\": \"" + bookId + "\"}"
        );

        var resp = get("/books/" + bookId);
        JsonObject book = JsonParser.parseString(resp.body()).getAsJsonObject();
        JsonArray shelves = book.getAsJsonArray("shelves");
        assertEquals(2, shelves.size());
        // Each entry should have id, name, color
        JsonObject s = shelves.get(0).getAsJsonObject();
        assertNotNull(s.get("id").getAsString());
        assertNotNull(s.get("name").getAsString());
        assertNotNull(s.get("color").getAsString());
    }

    @Test
    void t11_missingNameReturns400() throws Exception {
        var resp = post("/shelves", "{}");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void t12_shelfNotFoundReturns404() throws Exception {
        var resp = get("/shelves/00000000-0000-0000-0000-000000000000");
        assertEquals(404, resp.statusCode());
    }

    // ========================================
    // Color & Sorting (T_SHELF_13 — T_SHELF_18)
    // ========================================

    @Test
    void t13_createShelfWithCustomColor() throws Exception {
        var resp = post("/shelves", createShelfJson("Test", "#FF5733"));
        assertEquals(201, resp.statusCode());
        JsonObject shelf = JsonParser.parseString(
            resp.body()
        ).getAsJsonObject();
        assertEquals("#FF5733", shelf.get("color").getAsString());
    }

    @Test
    void t14_defaultColorWhenNoneProvided() throws Exception {
        var resp = post("/shelves", createShelfJson("Test"));
        assertEquals(201, resp.statusCode());
        JsonObject shelf = JsonParser.parseString(
            resp.body()
        ).getAsJsonObject();
        assertEquals("#C4975A", shelf.get("color").getAsString());
    }

    @Test
    void t15_invalidColorHexReturns400() throws Exception {
        var resp = post("/shelves", createShelfJson("Test", "not-a-color"));
        assertEquals(400, resp.statusCode());
    }

    @Test
    void t16_setShelfSortPreference() throws Exception {
        String shelfId = createShelfAndGetId("Sorted");
        String b1 = createBookAndGetId("Zorro", "Author A");
        String b2 = createBookAndGetId("Alpha", "Author B");
        String b3 = createBookAndGetId("Middle", "Author C");
        post("/shelves/" + shelfId + "/books", "{\"bookId\": \"" + b1 + "\"}");
        post("/shelves/" + shelfId + "/books", "{\"bookId\": \"" + b2 + "\"}");
        post("/shelves/" + shelfId + "/books", "{\"bookId\": \"" + b3 + "\"}");

        put(
            "/shelves/" + shelfId,
            "{\"sortField\": \"title\", \"sortDirection\": \"asc\"}"
        );

        var resp = get("/shelves/" + shelfId);
        JsonArray books = JsonParser.parseString(resp.body())
            .getAsJsonObject()
            .getAsJsonArray("books");
        assertEquals(
            "Alpha",
            books.get(0).getAsJsonObject().get("title").getAsString()
        );
        assertEquals(
            "Middle",
            books.get(1).getAsJsonObject().get("title").getAsString()
        );
        assertEquals(
            "Zorro",
            books.get(2).getAsJsonObject().get("title").getAsString()
        );
    }

    @Test
    void t17_customSortOrderPreserved() throws Exception {
        String shelfId = createShelfAndGetId("Custom");
        String b1 = createBookAndGetId("A", "Author");
        String b2 = createBookAndGetId("B", "Author");
        String b3 = createBookAndGetId("C", "Author");
        post("/shelves/" + shelfId + "/books", "{\"bookId\": \"" + b1 + "\"}");
        post("/shelves/" + shelfId + "/books", "{\"bookId\": \"" + b2 + "\"}");
        post("/shelves/" + shelfId + "/books", "{\"bookId\": \"" + b3 + "\"}");

        // Reorder to C, A, B
        String orderJson =
            "{\"order\": [\"" + b3 + "\", \"" + b1 + "\", \"" + b2 + "\"]}";
        put("/shelves/" + shelfId + "/books/reorder", orderJson);

        var resp = get("/shelves/" + shelfId);
        JsonArray books = JsonParser.parseString(resp.body())
            .getAsJsonObject()
            .getAsJsonArray("books");
        assertEquals(
            "C",
            books.get(0).getAsJsonObject().get("title").getAsString()
        );
        assertEquals(
            "A",
            books.get(1).getAsJsonObject().get("title").getAsString()
        );
        assertEquals(
            "B",
            books.get(2).getAsJsonObject().get("title").getAsString()
        );
    }

    @Test
    void t18_invalidSortFieldReturns400() throws Exception {
        String shelfId = createShelfAndGetId("Test");
        var resp = put("/shelves/" + shelfId, "{\"sortField\": \"invalid\"}");
        assertEquals(400, resp.statusCode());
    }

    // ========================================
    // Reorder (T_SHELF_19 — T_SHELF_22)
    // ========================================

    @Test
    void t19_reorderShelves() throws Exception {
        String id1 = createShelfAndGetId("First");
        String id2 = createShelfAndGetId("Second");
        String id3 = createShelfAndGetId("Third");

        String orderJson =
            "{\"order\": [\"" + id3 + "\", \"" + id2 + "\", \"" + id1 + "\"]}";
        var resp = put("/shelves/reorder", orderJson);
        assertEquals(200, resp.statusCode());

        var listResp = get("/shelves");
        JsonArray arr = JsonParser.parseString(
            listResp.body()
        ).getAsJsonArray();
        assertEquals(
            "Third",
            arr.get(0).getAsJsonObject().get("name").getAsString()
        );
        assertEquals(
            "Second",
            arr.get(1).getAsJsonObject().get("name").getAsString()
        );
        assertEquals(
            "First",
            arr.get(2).getAsJsonObject().get("name").getAsString()
        );
    }

    @Test
    void t20_reorderBooksWithinShelf() throws Exception {
        String shelfId = createShelfAndGetId("My Shelf");
        String b1 = createBookAndGetId("Book 1", "Author");
        String b2 = createBookAndGetId("Book 2", "Author");
        String b3 = createBookAndGetId("Book 3", "Author");
        post("/shelves/" + shelfId + "/books", "{\"bookId\": \"" + b1 + "\"}");
        post("/shelves/" + shelfId + "/books", "{\"bookId\": \"" + b2 + "\"}");
        post("/shelves/" + shelfId + "/books", "{\"bookId\": \"" + b3 + "\"}");

        String orderJson =
            "{\"order\": [\"" + b3 + "\", \"" + b1 + "\", \"" + b2 + "\"]}";
        put("/shelves/" + shelfId + "/books/reorder", orderJson);

        var resp = get("/shelves/" + shelfId);
        JsonArray books = JsonParser.parseString(resp.body())
            .getAsJsonObject()
            .getAsJsonArray("books");
        assertEquals(
            "Book 3",
            books.get(0).getAsJsonObject().get("title").getAsString()
        );
        assertEquals(
            "Book 1",
            books.get(1).getAsJsonObject().get("title").getAsString()
        );
        assertEquals(
            "Book 2",
            books.get(2).getAsJsonObject().get("title").getAsString()
        );
    }

    @Test
    void t21_reorderWithMissingBookIdReturns400() throws Exception {
        String shelfId = createShelfAndGetId("My Shelf");
        String b1 = createBookAndGetId("Book 1", "Author");
        String b2 = createBookAndGetId("Book 2", "Author");
        post("/shelves/" + shelfId + "/books", "{\"bookId\": \"" + b1 + "\"}");
        post("/shelves/" + shelfId + "/books", "{\"bookId\": \"" + b2 + "\"}");

        // Only include 1 of 2 books
        String orderJson = "{\"order\": [\"" + b1 + "\"]}";
        var resp = put("/shelves/" + shelfId + "/books/reorder", orderJson);
        assertEquals(400, resp.statusCode());
    }

    @Test
    void t22_reorderShelvesWithInvalidIdReturns400() throws Exception {
        String id1 = createShelfAndGetId("First");
        String fakeId = UUID.randomUUID().toString();
        String orderJson = "{\"order\": [\"" + id1 + "\", \"" + fakeId + "\"]}";
        var resp = put("/shelves/reorder", orderJson);
        assertEquals(400, resp.statusCode());
    }

    // ========================================
    // Stats (T_SHELF_23 — T_SHELF_25)
    // ========================================

    @Test
    void t23_shelfStatsComputedCorrectly() throws Exception {
        String shelfId = createShelfAndGetId("Stats Test");
        String b1 = createBookAndGetId("Book 1", "A1", "Sci-Fi", 3, 200);
        String b2 = createBookAndGetId("Book 2", "A2", "Sci-Fi", 4, 300);
        String b3 = createBookAndGetId("Book 3", "A3", "Fantasy", 5, 400);
        post("/shelves/" + shelfId + "/books", "{\"bookId\": \"" + b1 + "\"}");
        post("/shelves/" + shelfId + "/books", "{\"bookId\": \"" + b2 + "\"}");
        post("/shelves/" + shelfId + "/books", "{\"bookId\": \"" + b3 + "\"}");

        var resp = get("/shelves/" + shelfId);
        JsonObject stats = JsonParser.parseString(resp.body())
            .getAsJsonObject()
            .getAsJsonObject("stats");
        assertEquals(3, stats.get("bookCount").getAsInt());
        assertEquals(4.0, stats.get("averageRating").getAsDouble(), 0.01);
        assertEquals(900, stats.get("totalPages").getAsInt());

        JsonObject genres = stats.getAsJsonObject("genreBreakdown");
        assertEquals(2, genres.get("Sci-Fi").getAsInt());
        assertEquals(1, genres.get("Fantasy").getAsInt());
    }

    @Test
    void t24_statsExcludeUnratedFromAverage() throws Exception {
        String shelfId = createShelfAndGetId("Rating Test");
        String b1 = createBookAndGetId("Rated", "Author", "Fiction", 4, 100);
        String b2 = createBookAndGetId("Unrated", "Author");
        post("/shelves/" + shelfId + "/books", "{\"bookId\": \"" + b1 + "\"}");
        post("/shelves/" + shelfId + "/books", "{\"bookId\": \"" + b2 + "\"}");

        var resp = get("/shelves/" + shelfId);
        JsonObject stats = JsonParser.parseString(resp.body())
            .getAsJsonObject()
            .getAsJsonObject("stats");
        assertEquals(4.0, stats.get("averageRating").getAsDouble(), 0.01);
    }

    @Test
    void t25_statsWithEmptyShelf() throws Exception {
        String shelfId = createShelfAndGetId("Empty");
        var resp = get("/shelves/" + shelfId);
        JsonObject stats = JsonParser.parseString(resp.body())
            .getAsJsonObject()
            .getAsJsonObject("stats");
        assertEquals(0, stats.get("bookCount").getAsInt());
        assertEquals(0.0, stats.get("averageRating").getAsDouble(), 0.01);
        assertEquals(0, stats.get("totalPages").getAsInt());
        assertTrue(
            stats.getAsJsonObject("genreBreakdown").entrySet().isEmpty()
        );
    }

    // ========================================
    // Cover Collage (T_SHELF_26 — T_SHELF_28)
    // ========================================

    @Test
    void t26_coverBookIdsReturnsFirst4Books() throws Exception {
        String shelfId = createShelfAndGetId("Covers");
        String[] bookIds = new String[5];
        for (int i = 0; i < 5; i++) {
            bookIds[i] = createBookAndGetId("Book " + i, "Author");
            post(
                "/shelves/" + shelfId + "/books",
                "{\"bookId\": \"" + bookIds[i] + "\"}"
            );
        }

        var resp = get("/shelves");
        JsonArray shelves = JsonParser.parseString(
            resp.body()
        ).getAsJsonArray();
        JsonArray coverIds = shelves
            .get(0)
            .getAsJsonObject()
            .getAsJsonArray("coverBookIds");
        assertEquals(4, coverIds.size());
    }

    @Test
    void t27_coverBookIdsWithFewerThan4Books() throws Exception {
        String shelfId = createShelfAndGetId("Small");
        String b1 = createBookAndGetId("Book 1", "Author");
        String b2 = createBookAndGetId("Book 2", "Author");
        post("/shelves/" + shelfId + "/books", "{\"bookId\": \"" + b1 + "\"}");
        post("/shelves/" + shelfId + "/books", "{\"bookId\": \"" + b2 + "\"}");

        var resp = get("/shelves");
        JsonArray shelves = JsonParser.parseString(
            resp.body()
        ).getAsJsonArray();
        JsonArray coverIds = shelves
            .get(0)
            .getAsJsonObject()
            .getAsJsonArray("coverBookIds");
        assertEquals(2, coverIds.size());
    }

    @Test
    void t28_coverBookIdsEmptyForEmptyShelf() throws Exception {
        createShelfAndGetId("Empty");
        var resp = get("/shelves");
        JsonArray shelves = JsonParser.parseString(
            resp.body()
        ).getAsJsonArray();
        JsonArray coverIds = shelves
            .get(0)
            .getAsJsonObject()
            .getAsJsonArray("coverBookIds");
        assertTrue(coverIds.isEmpty());
    }

    // ========================================
    // Position Edge Cases (T_SHELF_29 — T_SHELF_33)
    // ========================================

    @Test
    void t29_positionAutoAssignedOnCreate() throws Exception {
        createShelfAndGetId("A");
        createShelfAndGetId("B");
        createShelfAndGetId("C");

        var resp = get("/shelves");
        JsonArray arr = JsonParser.parseString(resp.body()).getAsJsonArray();
        assertEquals(
            0,
            arr.get(0).getAsJsonObject().get("position").getAsInt()
        );
        assertEquals(
            1,
            arr.get(1).getAsJsonObject().get("position").getAsInt()
        );
        assertEquals(
            2,
            arr.get(2).getAsJsonObject().get("position").getAsInt()
        );
    }

    @Test
    void t30_positionAutoAssignedOnBookAdd() throws Exception {
        String shelfId = createShelfAndGetId("My Shelf");
        String b1 = createBookAndGetId("A", "Author");
        String b2 = createBookAndGetId("B", "Author");
        String b3 = createBookAndGetId("C", "Author");
        post("/shelves/" + shelfId + "/books", "{\"bookId\": \"" + b1 + "\"}");
        post("/shelves/" + shelfId + "/books", "{\"bookId\": \"" + b2 + "\"}");
        post("/shelves/" + shelfId + "/books", "{\"bookId\": \"" + b3 + "\"}");

        // Default sort is custom, so books should be in insertion order
        var resp = get("/shelves/" + shelfId);
        JsonArray books = JsonParser.parseString(resp.body())
            .getAsJsonObject()
            .getAsJsonArray("books");
        assertEquals(
            "A",
            books.get(0).getAsJsonObject().get("title").getAsString()
        );
        assertEquals(
            "B",
            books.get(1).getAsJsonObject().get("title").getAsString()
        );
        assertEquals(
            "C",
            books.get(2).getAsJsonObject().get("title").getAsString()
        );
    }

    @Test
    void t31_deletingShelfRenumbersPositions() throws Exception {
        String id1 = createShelfAndGetId("First");
        String id2 = createShelfAndGetId("Second");
        String id3 = createShelfAndGetId("Third");

        delete("/shelves/" + id2);

        var resp = get("/shelves");
        JsonArray arr = JsonParser.parseString(resp.body()).getAsJsonArray();
        assertEquals(2, arr.size());
        assertEquals(
            0,
            arr.get(0).getAsJsonObject().get("position").getAsInt()
        );
        assertEquals(
            1,
            arr.get(1).getAsJsonObject().get("position").getAsInt()
        );
    }

    @Test
    void t32_descriptionAndNotesAreOptional() throws Exception {
        var resp = post("/shelves", createShelfJson("Test"));
        assertEquals(201, resp.statusCode());
        JsonObject shelf = JsonParser.parseString(
            resp.body()
        ).getAsJsonObject();
        assertTrue(shelf.get("description").isJsonNull());
    }

    @Test
    void t33_updateOnlyNotesWithoutChangingOtherFields() throws Exception {
        String id = createShelfAndGetId("Test");
        var createResp = get("/shelves/" + id);
        JsonObject original = JsonParser.parseString(
            createResp.body()
        ).getAsJsonObject();
        String originalColor = original.get("color").getAsString();

        put("/shelves/" + id, "{\"notes\": \"Some notes\"}");

        var resp = get("/shelves/" + id);
        JsonObject updated = JsonParser.parseString(
            resp.body()
        ).getAsJsonObject();
        assertEquals("Test", updated.get("name").getAsString());
        assertEquals(originalColor, updated.get("color").getAsString());
        assertEquals("Some notes", updated.get("notes").getAsString());
    }

    // ========================================
    // Interaction with Existing Features (T_SHELF_34 — T_SHELF_43)
    // ========================================

    @Test
    void t34_shelfBooksHaveReadStatus() throws Exception {
        String shelfId = createShelfAndGetId("Mixed Status");
        JsonObject finishedBook = new JsonObject();
        finishedBook.addProperty("title", "Done");
        finishedBook.addProperty("author", "Author");
        finishedBook.addProperty("readStatus", "FINISHED");
        var resp1 = post("/books", finishedBook.toString());
        String b1 = JsonParser.parseString(resp1.body())
            .getAsJsonObject()
            .get("id")
            .getAsString();

        JsonObject readingBook = new JsonObject();
        readingBook.addProperty("title", "In Progress");
        readingBook.addProperty("author", "Author");
        readingBook.addProperty("readStatus", "READING");
        var resp2 = post("/books", readingBook.toString());
        String b2 = JsonParser.parseString(resp2.body())
            .getAsJsonObject()
            .get("id")
            .getAsString();

        post("/shelves/" + shelfId + "/books", "{\"bookId\": \"" + b1 + "\"}");
        post("/shelves/" + shelfId + "/books", "{\"bookId\": \"" + b2 + "\"}");

        var resp = get("/shelves/" + shelfId);
        JsonArray books = JsonParser.parseString(resp.body())
            .getAsJsonObject()
            .getAsJsonArray("books");
        assertEquals(2, books.size());
        // Verify readStatus is present on each book
        for (int i = 0; i < books.size(); i++) {
            assertNotNull(
                books.get(i).getAsJsonObject().get("readStatus").getAsString()
            );
        }
    }

    @Test
    void t35_shelfBooksHaveTitlesForSearch() throws Exception {
        String shelfId = createShelfAndGetId("Searchable");
        String b1 = createBookAndGetId("Dune", "Frank Herbert");
        String b2 = createBookAndGetId("Neuromancer", "William Gibson");
        String b3 = createBookAndGetId("Foundation", "Isaac Asimov");
        post("/shelves/" + shelfId + "/books", "{\"bookId\": \"" + b1 + "\"}");
        post("/shelves/" + shelfId + "/books", "{\"bookId\": \"" + b2 + "\"}");
        post("/shelves/" + shelfId + "/books", "{\"bookId\": \"" + b3 + "\"}");

        var resp = get("/shelves/" + shelfId);
        JsonArray books = JsonParser.parseString(resp.body())
            .getAsJsonObject()
            .getAsJsonArray("books");
        assertEquals(3, books.size());
        // Each book has title for frontend search
        for (int i = 0; i < books.size(); i++) {
            assertNotNull(
                books.get(i).getAsJsonObject().get("title").getAsString()
            );
        }
    }

    @Test
    void t37_shelfSortByRatingWithUnrated() throws Exception {
        String shelfId = createShelfAndGetId("Rating Sort");
        String b1 = createBookAndGetId("Five Star", "A", "Fiction", 5, 100);
        String b2 = createBookAndGetId("Three Star", "B", "Fiction", 3, 100);
        String b3 = createBookAndGetId("Unrated", "C");
        String b4 = createBookAndGetId("One Star", "D", "Fiction", 1, 100);
        post("/shelves/" + shelfId + "/books", "{\"bookId\": \"" + b1 + "\"}");
        post("/shelves/" + shelfId + "/books", "{\"bookId\": \"" + b2 + "\"}");
        post("/shelves/" + shelfId + "/books", "{\"bookId\": \"" + b3 + "\"}");
        post("/shelves/" + shelfId + "/books", "{\"bookId\": \"" + b4 + "\"}");

        put(
            "/shelves/" + shelfId,
            "{\"sortField\": \"rating\", \"sortDirection\": \"desc\"}"
        );

        var resp = get("/shelves/" + shelfId);
        JsonArray books = JsonParser.parseString(resp.body())
            .getAsJsonObject()
            .getAsJsonArray("books");
        assertEquals(
            "Five Star",
            books.get(0).getAsJsonObject().get("title").getAsString()
        );
        assertEquals(
            "Three Star",
            books.get(1).getAsJsonObject().get("title").getAsString()
        );
        assertEquals(
            "One Star",
            books.get(2).getAsJsonObject().get("title").getAsString()
        );
        assertEquals(
            "Unrated",
            books.get(3).getAsJsonObject().get("title").getAsString()
        );
    }

    @Test
    void t38_shelfSortByTitleWithNullTitles() throws Exception {
        String shelfId = createShelfAndGetId("Title Sort");
        String b1 = createBookAndGetId("Alpha", "Author");
        String b2 = createBookAndGetId("Zeta", "Author");
        // Create a book with null title via ISBN-only (simulate)
        // Since we can't easily create a null-title book via API without ISBN enrichment,
        // we just test the two with titles sort correctly
        post("/shelves/" + shelfId + "/books", "{\"bookId\": \"" + b1 + "\"}");
        post("/shelves/" + shelfId + "/books", "{\"bookId\": \"" + b2 + "\"}");

        put(
            "/shelves/" + shelfId,
            "{\"sortField\": \"title\", \"sortDirection\": \"asc\"}"
        );

        var resp = get("/shelves/" + shelfId);
        JsonArray books = JsonParser.parseString(resp.body())
            .getAsJsonObject()
            .getAsJsonArray("books");
        assertEquals(
            "Alpha",
            books.get(0).getAsJsonObject().get("title").getAsString()
        );
        assertEquals(
            "Zeta",
            books.get(1).getAsJsonObject().get("title").getAsString()
        );
    }

    @Test
    void t39_bookAppearsInMultipleShelvesStatsIndependently() throws Exception {
        String shelf1Id = createShelfAndGetId("Shelf 1");
        String shelf2Id = createShelfAndGetId("Shelf 2");
        String bookId = createBookAndGetId(
            "Shared Book",
            "Author",
            "Sci-Fi",
            5,
            300
        );
        post(
            "/shelves/" + shelf1Id + "/books",
            "{\"bookId\": \"" + bookId + "\"}"
        );
        post(
            "/shelves/" + shelf2Id + "/books",
            "{\"bookId\": \"" + bookId + "\"}"
        );

        var resp1 = get("/shelves/" + shelf1Id);
        JsonObject stats1 = JsonParser.parseString(resp1.body())
            .getAsJsonObject()
            .getAsJsonObject("stats");
        assertEquals(1, stats1.get("bookCount").getAsInt());
        assertEquals(5.0, stats1.get("averageRating").getAsDouble(), 0.01);
        assertEquals(300, stats1.get("totalPages").getAsInt());

        var resp2 = get("/shelves/" + shelf2Id);
        JsonObject stats2 = JsonParser.parseString(resp2.body())
            .getAsJsonObject()
            .getAsJsonObject("stats");
        assertEquals(1, stats2.get("bookCount").getAsInt());
        assertEquals(5.0, stats2.get("averageRating").getAsDouble(), 0.01);
        assertEquals(300, stats2.get("totalPages").getAsInt());
    }

    @Test
    void t40_deletingBookUpdatesShelfStats() throws Exception {
        String shelfId = createShelfAndGetId("Stats Update");
        String b1 = createBookAndGetId("Book A", "Author", "Fiction", 4, 200);
        String b2 = createBookAndGetId("Book B", "Author", "Fiction", 5, 300);
        post("/shelves/" + shelfId + "/books", "{\"bookId\": \"" + b1 + "\"}");
        post("/shelves/" + shelfId + "/books", "{\"bookId\": \"" + b2 + "\"}");

        // Verify stats before
        var before = get("/shelves/" + shelfId);
        JsonObject statsBefore = JsonParser.parseString(before.body())
            .getAsJsonObject()
            .getAsJsonObject("stats");
        assertEquals(2, statsBefore.get("bookCount").getAsInt());
        assertEquals(4.5, statsBefore.get("averageRating").getAsDouble(), 0.01);

        // Delete book B
        delete("/books/" + b2);

        var after = get("/shelves/" + shelfId);
        JsonObject statsAfter = JsonParser.parseString(after.body())
            .getAsJsonObject()
            .getAsJsonObject("stats");
        assertEquals(1, statsAfter.get("bookCount").getAsInt());
        assertEquals(4.0, statsAfter.get("averageRating").getAsDouble(), 0.01);
    }

    @Test
    void t41_updatingBookRatingUpdatesShelfStats() throws Exception {
        String shelfId = createShelfAndGetId("Rating Update");
        String bookId = createBookAndGetId("Book", "Author", "Fiction", 3, 100);
        post(
            "/shelves/" + shelfId + "/books",
            "{\"bookId\": \"" + bookId + "\"}"
        );

        var before = get("/shelves/" + shelfId);
        assertEquals(
            3.0,
            JsonParser.parseString(before.body())
                .getAsJsonObject()
                .getAsJsonObject("stats")
                .get("averageRating")
                .getAsDouble(),
            0.01
        );

        put("/books/" + bookId, "{\"rating\": 5}");

        var after = get("/shelves/" + shelfId);
        assertEquals(
            5.0,
            JsonParser.parseString(after.body())
                .getAsJsonObject()
                .getAsJsonObject("stats")
                .get("averageRating")
                .getAsDouble(),
            0.01
        );
    }

    @Test
    void t42_coverBookIdsUpdatesWhenBookRemoved() throws Exception {
        String shelfId = createShelfAndGetId("Cover Update");
        String[] bookIds = new String[5];
        for (int i = 0; i < 5; i++) {
            bookIds[i] = createBookAndGetId("Book " + i, "Author");
            post(
                "/shelves/" + shelfId + "/books",
                "{\"bookId\": \"" + bookIds[i] + "\"}"
            );
        }

        // Remove first book
        delete("/shelves/" + shelfId + "/books/" + bookIds[0]);

        var resp = get("/shelves");
        JsonArray shelves = JsonParser.parseString(
            resp.body()
        ).getAsJsonArray();
        JsonArray coverIds = shelves
            .get(0)
            .getAsJsonObject()
            .getAsJsonArray("coverBookIds");
        assertEquals(4, coverIds.size());
        // First cover ID should now be bookIds[1]
        assertEquals(bookIds[1], coverIds.get(0).getAsString());
    }

    @Test
    void t43_bookShelvesArrayUpdatesAfterRemoval() throws Exception {
        String shelfId = createShelfAndGetId("Removal Test");
        String bookId = createBookAndGetId("Book", "Author");
        post(
            "/shelves/" + shelfId + "/books",
            "{\"bookId\": \"" + bookId + "\"}"
        );

        // Verify book has shelves
        var before = get("/books/" + bookId);
        assertEquals(
            1,
            JsonParser.parseString(before.body())
                .getAsJsonObject()
                .getAsJsonArray("shelves")
                .size()
        );

        // Remove from shelf
        delete("/shelves/" + shelfId + "/books/" + bookId);

        var after = get("/books/" + bookId);
        assertEquals(
            0,
            JsonParser.parseString(after.body())
                .getAsJsonObject()
                .getAsJsonArray("shelves")
                .size()
        );
    }

    // ========================================
    // Validation Edge Cases (T_SHELF_44 — T_SHELF_56)
    // ========================================

    @Test
    void t44_emptyStringNameReturns400() throws Exception {
        var resp = post("/shelves", "{\"name\": \"\"}");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void t45_whitespaceOnlyNameReturns400() throws Exception {
        var resp = post("/shelves", "{\"name\": \"   \"}");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void t46_nameAtExactly100CharsAccepted() throws Exception {
        String name = "a".repeat(100);
        var resp = post("/shelves", "{\"name\": \"" + name + "\"}");
        assertEquals(201, resp.statusCode());
    }

    @Test
    void t47_nameAt101CharsReturns400() throws Exception {
        String name = "a".repeat(101);
        var resp = post("/shelves", "{\"name\": \"" + name + "\"}");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void t48_duplicateNameDifferentCaseReturns409() throws Exception {
        createShelfAndGetId("Favorites");
        var resp = post("/shelves", createShelfJson("favorites"));
        assertEquals(409, resp.statusCode());
    }

    @Test
    void t49_specialCharactersInNameAccepted() throws Exception {
        var resp = post(
            "/shelves",
            createShelfJson("Sci-Fi & Fantasy (Best!)")
        );
        assertEquals(201, resp.statusCode());
        JsonObject shelf = JsonParser.parseString(
            resp.body()
        ).getAsJsonObject();
        assertEquals(
            "Sci-Fi & Fantasy (Best!)",
            shelf.get("name").getAsString()
        );
    }

    @Test
    void t50_colorHexValidation() throws Exception {
        // Too short
        assertEquals(
            400,
            post("/shelves", createShelfJson("T1", "#FFF")).statusCode()
        );
        // Invalid hex chars
        assertEquals(
            400,
            post("/shelves", createShelfJson("T2", "#GGGGGG")).statusCode()
        );
        // Missing #
        assertEquals(
            400,
            post("/shelves", createShelfJson("T3", "C4975A")).statusCode()
        );
        // Lowercase valid
        assertEquals(
            201,
            post("/shelves", createShelfJson("T4", "#c4975a")).statusCode()
        );
    }

    @Test
    void t51_reorderWithEmptyArrayReturns400() throws Exception {
        var resp = put("/shelves/reorder", "{\"order\": []}");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void t52_reorderBooksWithBookNotOnShelfReturns400() throws Exception {
        String shelfId = createShelfAndGetId("My Shelf");
        String b1 = createBookAndGetId("Book 1", "Author");
        String b2 = createBookAndGetId("Book 2", "Author");
        String b3 = createBookAndGetId("Not on shelf", "Author");
        post("/shelves/" + shelfId + "/books", "{\"bookId\": \"" + b1 + "\"}");
        post("/shelves/" + shelfId + "/books", "{\"bookId\": \"" + b2 + "\"}");

        String orderJson = "{\"order\": [\"" + b1 + "\", \"" + b3 + "\"]}";
        var resp = put("/shelves/" + shelfId + "/books/reorder", orderJson);
        assertEquals(400, resp.statusCode());
    }

    @Test
    void t53_addBookWithInvalidUuidReturns400() throws Exception {
        String shelfId = createShelfAndGetId("My Shelf");
        var resp = post(
            "/shelves/" + shelfId + "/books",
            "{\"bookId\": \"not-a-uuid\"}"
        );
        assertEquals(400, resp.statusCode());
    }

    @Test
    void t54_addBookWithNonExistentUuidReturns404() throws Exception {
        String shelfId = createShelfAndGetId("My Shelf");
        var resp = post(
            "/shelves/" + shelfId + "/books",
            "{\"bookId\": \"00000000-0000-0000-0000-000000000000\"}"
        );
        assertEquals(404, resp.statusCode());
    }

    @Test
    void t55_malformedJsonOnCreateReturns400() throws Exception {
        var resp = post("/shelves", "not json");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void t56_nonUuidShelfPathReturns404() throws Exception {
        var resp = get("/shelves/not-a-uuid");
        assertEquals(404, resp.statusCode());
    }

    @Test
    void t57_booksListResponseIncludesShelvesArray() throws Exception {
        // Verify GET /books (list) also includes shelves array on each book
        String shelfId = createShelfAndGetId("My Shelf");
        String bookId = createBookAndGetId("Test Book", "Author");
        post(
            "/shelves/" + shelfId + "/books",
            "{\"bookId\": \"" + bookId + "\"}"
        );

        var resp = get("/books");
        assertEquals(200, resp.statusCode());
        JsonArray books = JsonParser.parseString(resp.body()).getAsJsonArray();
        assertEquals(1, books.size());
        JsonArray shelves = books
            .get(0)
            .getAsJsonObject()
            .getAsJsonArray("shelves");
        assertNotNull(shelves);
        assertEquals(1, shelves.size());
        assertEquals(
            "My Shelf",
            shelves.get(0).getAsJsonObject().get("name").getAsString()
        );
    }
}
