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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@TestMethodOrder(MethodOrderer.MethodName.class)
public class BookApiTest {

    private static HttpServer server;
    private static HttpClient client;
    private static InMemoryBookRepository repository;
    private static InMemoryShelfRepository shelfRepository;
    private static int port;
    private static String authToken;
    private static final Gson gson = new GsonBuilder()
        .serializeNulls()
        .create();

    @BeforeAll
    static void startServer() throws IOException, InterruptedException {
        repository = new InMemoryBookRepository();
        shelfRepository = new InMemoryShelfRepository(repository);
        BookController controller = new BookController(
            repository,
            null,
            shelfRepository
        );
        ShelfController shelfController = new ShelfController(
            shelfRepository,
            repository
        );
        McpController mcpController = new McpController(
            repository,
            shelfRepository
        );
        GoalController goalController = new GoalController(
            new InMemoryGoalRepository(),
            repository
        );
        JwtUtil jwtUtil = new JwtUtil("test-secret");
        InMemoryUserRepository userRepository = new InMemoryUserRepository();
        AuthController authController = new AuthController(userRepository, jwtUtil);
        AuthMiddleware authMiddleware = new AuthMiddleware(jwtUtil);
        Router router = App.createRouter(
            controller,
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
        // Wait for server to accept connections
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

    @BeforeEach
    void cleanRepository() {
        repository.clear();
        shelfRepository.clear();
    }

    // --- Helper methods ---

    private static void waitForServer(int port) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            try (Socket s = new Socket("localhost", port)) {
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

    private HttpResponse<String> get(String path)
        throws IOException, InterruptedException {
        return client.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Authorization", "Bearer " + authToken)
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

    private HttpResponse<String> sendMethod(String method, String path)
        throws IOException, InterruptedException {
        return client.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Authorization", "Bearer " + authToken)
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
    }

    private String createBookJson(
        String title,
        String author,
        String readStatus
    ) {
        return createBookJson(title, author, readStatus, null, null, null);
    }

    private String createBookJson(
        String title,
        String author,
        String readStatus,
        String genre,
        Integer rating,
        String isbn
    ) {
        JsonObject json = new JsonObject();
        if (title != null) json.addProperty("title", title);
        if (author != null) json.addProperty("author", author);
        if (readStatus != null) json.addProperty("readStatus", readStatus);
        if (genre != null) json.addProperty("genre", genre);
        if (rating != null) json.addProperty("rating", rating);
        if (isbn != null) json.addProperty("isbn", isbn);
        return json.toString();
    }

    private String getIdFromResponse(HttpResponse<String> response) {
        JsonObject json = JsonParser.parseString(
            response.body()
        ).getAsJsonObject();
        return json.get("id").getAsString();
    }

    // --- T1: Create and retrieve a book ---
    @Test
    void testT01_createAndRetrieveBook() throws Exception {
        String body = createBookJson(
            "Dune",
            "Frank Herbert",
            "READING",
            "sci-fi",
            5,
            "9780441013593"
        );
        HttpResponse<String> createResp = post("/books", body);
        assertEquals(201, createResp.statusCode());

        JsonObject created = JsonParser.parseString(
            createResp.body()
        ).getAsJsonObject();
        String id = created.get("id").getAsString();
        assertDoesNotThrow(() -> UUID.fromString(id));
        assertEquals("Dune", created.get("title").getAsString());
        assertEquals("Frank Herbert", created.get("author").getAsString());
        assertEquals("READING", created.get("readStatus").getAsString());
        assertEquals("sci-fi", created.get("genre").getAsString());
        assertEquals(5, created.get("rating").getAsInt());
        assertEquals("9780441013593", created.get("isbn").getAsString());

        HttpResponse<String> getResp = get("/books/" + id);
        assertEquals(200, getResp.statusCode());

        JsonObject fetched = JsonParser.parseString(
            getResp.body()
        ).getAsJsonObject();
        assertEquals("Dune", fetched.get("title").getAsString());
        assertEquals("Frank Herbert", fetched.get("author").getAsString());
        assertEquals(5, fetched.get("rating").getAsInt());
    }

    // --- T2: List all books ---
    @Test
    void testT02_listAllBooks() throws Exception {
        post("/books", createBookJson("Book 1", "Author 1", "READING"));
        post("/books", createBookJson("Book 2", "Author 2", "FINISHED"));
        post("/books", createBookJson("Book 3", "Author 3", "WANT_TO_READ"));

        HttpResponse<String> resp = get("/books");
        assertEquals(200, resp.statusCode());

        JsonArray books = JsonParser.parseString(resp.body()).getAsJsonArray();
        assertEquals(3, books.size());
    }

    // --- T3: Partial update a book ---
    @Test
    void testT03_partialUpdateBook() throws Exception {
        HttpResponse<String> createResp = post(
            "/books",
            createBookJson("Dune", "Frank Herbert", "WANT_TO_READ")
        );
        assertEquals(201, createResp.statusCode());
        String id = getIdFromResponse(createResp);

        String updateBody = "{\"readStatus\":\"FINISHED\",\"rating\":5}";
        HttpResponse<String> updateResp = put("/books/" + id, updateBody);
        assertEquals(200, updateResp.statusCode());

        HttpResponse<String> getResp = get("/books/" + id);
        JsonObject book = JsonParser.parseString(
            getResp.body()
        ).getAsJsonObject();
        assertEquals("Dune", book.get("title").getAsString());
        assertEquals("FINISHED", book.get("readStatus").getAsString());
        assertEquals(5, book.get("rating").getAsInt());
    }

    // --- T4: Delete a book ---
    @Test
    void testT04_deleteBook() throws Exception {
        HttpResponse<String> createResp = post(
            "/books",
            createBookJson("Dune", "Frank Herbert", "READING")
        );
        String id = getIdFromResponse(createResp);

        HttpResponse<String> deleteResp = delete("/books/" + id);
        assertEquals(204, deleteResp.statusCode());

        HttpResponse<String> getResp = get("/books/" + id);
        assertEquals(404, getResp.statusCode());

        HttpResponse<String> listResp = get("/books");
        JsonArray books = JsonParser.parseString(
            listResp.body()
        ).getAsJsonArray();
        assertEquals(0, books.size());
    }

    // --- T5: Look up by ISBN ---
    @Test
    void testT05_lookUpByIsbn() throws Exception {
        String body = createBookJson(
            "Dune",
            "Frank Herbert",
            "READING",
            null,
            null,
            "9780441013593"
        );
        post("/books", body);

        HttpResponse<String> resp = get("/books/isbn/9780441013593");
        assertEquals(200, resp.statusCode());

        JsonObject book = JsonParser.parseString(resp.body()).getAsJsonObject();
        assertEquals("Dune", book.get("title").getAsString());
    }

    // --- T8: Missing required field: title ---
    @Test
    void testT08_missingTitle() throws Exception {
        String body = "{\"author\":\"Someone\",\"readStatus\":\"READING\"}";
        HttpResponse<String> resp = post("/books", body);
        assertEquals(400, resp.statusCode());
        assertTrue(resp.body().toLowerCase().contains("title"));
    }

    // --- T9: Missing required field: author ---
    @Test
    void testT09_missingAuthor() throws Exception {
        String body = "{\"title\":\"Something\",\"readStatus\":\"READING\"}";
        HttpResponse<String> resp = post("/books", body);
        assertEquals(400, resp.statusCode());
    }

    // --- T10: Missing required field: readStatus ---
    @Test
    void testT10_missingReadStatus() throws Exception {
        String body = "{\"title\":\"Something\",\"author\":\"Someone\"}";
        HttpResponse<String> resp = post("/books", body);
        assertEquals(400, resp.statusCode());
    }

    // --- T11: Invalid rating (too low) ---
    @Test
    void testT11_ratingTooLow() throws Exception {
        String body =
            "{\"title\":\"Something\",\"author\":\"Someone\",\"readStatus\":\"READING\",\"rating\":0}";
        HttpResponse<String> resp = post("/books", body);
        assertEquals(400, resp.statusCode());
    }

    // --- T12: Invalid rating (too high) ---
    @Test
    void testT12_ratingTooHigh() throws Exception {
        String body =
            "{\"title\":\"Something\",\"author\":\"Someone\",\"readStatus\":\"READING\",\"rating\":6}";
        HttpResponse<String> resp = post("/books", body);
        assertEquals(400, resp.statusCode());
    }

    // --- T13: Invalid ISBN format ---
    @Test
    void testT13_invalidIsbnFormat() throws Exception {
        String body =
            "{\"title\":\"Something\",\"author\":\"Someone\",\"readStatus\":\"READING\",\"isbn\":\"123\"}";
        HttpResponse<String> resp = post("/books", body);
        assertEquals(400, resp.statusCode());
    }

    // --- T14: Malformed JSON ---
    @Test
    void testT14_malformedJson() throws Exception {
        HttpResponse<String> resp = post("/books", "not json at all");
        assertEquals(400, resp.statusCode());
    }

    // --- T15: Book not found ---
    @Test
    void testT15_bookNotFound() throws Exception {
        HttpResponse<String> resp = get(
            "/books/00000000-0000-0000-0000-000000000000"
        );
        assertEquals(404, resp.statusCode());
    }

    // --- T16: ISBN not found ---
    @Test
    void testT16_isbnNotFound() throws Exception {
        HttpResponse<String> resp = get("/books/isbn/0000000000");
        assertEquals(404, resp.statusCode());
    }

    // --- T17: Method not allowed ---
    @Test
    void testT17_methodNotAllowed() throws Exception {
        HttpResponse<String> resp = sendMethod("PATCH", "/books");
        assertEquals(405, resp.statusCode());
    }

    // --- T18: Create book with only required fields ---
    @Test
    void testT18_createWithOnlyRequiredFields() throws Exception {
        String body =
            "{\"title\":\"Minimal\",\"author\":\"Author\",\"readStatus\":\"READING\"}";
        HttpResponse<String> resp = post("/books", body);
        assertEquals(201, resp.statusCode());

        JsonObject book = JsonParser.parseString(resp.body()).getAsJsonObject();
        assertTrue(book.get("genre").isJsonNull());
        assertTrue(book.get("isbn").isJsonNull());
        assertEquals(0, book.get("rating").getAsInt());
    }

    // --- T19: Update with no changes ---
    @Test
    void testT19_updateWithNoChanges() throws Exception {
        HttpResponse<String> createResp = post(
            "/books",
            createBookJson("Dune", "Frank Herbert", "READING")
        );
        String id = getIdFromResponse(createResp);

        String updateBody =
            "{\"title\":\"Dune\",\"author\":\"Frank Herbert\",\"readStatus\":\"READING\"}";
        HttpResponse<String> updateResp = put("/books/" + id, updateBody);
        assertEquals(200, updateResp.statusCode());
    }

    // --- T20: Delete already deleted book ---
    @Test
    void testT20_deleteAlreadyDeleted() throws Exception {
        HttpResponse<String> createResp = post(
            "/books",
            createBookJson("Dune", "Frank Herbert", "READING")
        );
        String id = getIdFromResponse(createResp);

        delete("/books/" + id);
        HttpResponse<String> resp = delete("/books/" + id);
        assertEquals(404, resp.statusCode());
    }

    // --- T21: Concurrent creates ---
    @Test
    void testT21_concurrentCreates() throws Exception {
        List<CompletableFuture<HttpResponse<String>>> futures =
            new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final int idx = i;
            futures.add(
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return post(
                            "/books",
                            createBookJson(
                                "Book " + idx,
                                "Author " + idx,
                                "READING"
                            )
                        );
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
            );
        }

        for (var future : futures) {
            HttpResponse<String> resp = future.get();
            assertEquals(201, resp.statusCode());
        }

        HttpResponse<String> listResp = get("/books");
        JsonArray books = JsonParser.parseString(
            listResp.body()
        ).getAsJsonArray();
        assertEquals(10, books.size());
    }

    // --- T22: Partial update clears field with explicit null ---
    @Test
    void testT22_partialUpdateClearsField() throws Exception {
        String body = createBookJson(
            "Dune",
            "Frank Herbert",
            "READING",
            "sci-fi",
            null,
            null
        );
        HttpResponse<String> createResp = post("/books", body);
        String id = getIdFromResponse(createResp);

        HttpResponse<String> updateResp = put(
            "/books/" + id,
            "{\"genre\":null}"
        );
        assertEquals(200, updateResp.statusCode());

        HttpResponse<String> getResp = get("/books/" + id);
        JsonObject book = JsonParser.parseString(
            getResp.body()
        ).getAsJsonObject();
        assertTrue(book.get("genre").isJsonNull());
    }

    // --- T23: ISBN-10 with X check digit is accepted ---
    @Test
    void testT23_isbn10WithX() throws Exception {
        String body = createBookJson(
            "Book",
            "Author",
            "READING",
            null,
            null,
            "080442957X"
        );
        HttpResponse<String> resp = post("/books", body);
        assertEquals(201, resp.statusCode());

        HttpResponse<String> getResp = get("/books/isbn/080442957X");
        assertEquals(200, getResp.statusCode());
    }

    // --- T24: Duplicate ISBNs are allowed ---
    @Test
    void testT24_duplicateIsbnsAllowed() throws Exception {
        String body1 = createBookJson(
            "Dune 1",
            "Frank Herbert",
            "READING",
            null,
            null,
            "9780441013593"
        );
        String body2 = createBookJson(
            "Dune 2",
            "Frank Herbert",
            "FINISHED",
            null,
            null,
            "9780441013593"
        );

        HttpResponse<String> resp1 = post("/books", body1);
        HttpResponse<String> resp2 = post("/books", body2);
        assertEquals(201, resp1.statusCode());
        assertEquals(201, resp2.statusCode());

        HttpResponse<String> listResp = get("/books");
        JsonArray books = JsonParser.parseString(
            listResp.body()
        ).getAsJsonArray();
        assertEquals(2, books.size());
    }

    // --- V4 Tests ---

    // --- T43: readStatus filtering ---
    @Test
    void testT43_readStatusFilter() throws Exception {
        post("/books", createBookJson("Book 1", "Author 1", "READING"));
        post("/books", createBookJson("Book 2", "Author 2", "FINISHED"));
        post("/books", createBookJson("Book 3", "Author 3", "READING"));
        post("/books", createBookJson("Book 4", "Author 4", "WANT_TO_READ"));

        HttpResponse<String> resp = get("/books?readStatus=READING");
        assertEquals(200, resp.statusCode());
        JsonArray books = JsonParser.parseString(resp.body()).getAsJsonArray();
        assertEquals(2, books.size());

        resp = get("/books?readStatus=FINISHED");
        assertEquals(200, resp.statusCode());
        books = JsonParser.parseString(resp.body()).getAsJsonArray();
        assertEquals(1, books.size());

        resp = get("/books?readStatus=WANT_TO_READ");
        assertEquals(200, resp.statusCode());
        books = JsonParser.parseString(resp.body()).getAsJsonArray();
        assertEquals(1, books.size());
    }

    // --- T44: readStatus filter with no matches ---
    @Test
    void testT44_readStatusFilterNoMatches() throws Exception {
        post("/books", createBookJson("Book 1", "Author 1", "READING"));

        HttpResponse<String> resp = get("/books?readStatus=FINISHED");
        assertEquals(200, resp.statusCode());
        JsonArray books = JsonParser.parseString(resp.body()).getAsJsonArray();
        assertEquals(0, books.size());
    }

    // --- T45: Invalid readStatus returns 400 ---
    @Test
    void testT45_invalidReadStatusFilter() throws Exception {
        HttpResponse<String> resp = get("/books?readStatus=INVALID");
        assertEquals(400, resp.statusCode());
    }

    // --- T46: Combined genre + readStatus filter ---
    @Test
    void testT46_combinedGenreAndReadStatus() throws Exception {
        post(
            "/books",
            createBookJson(
                "Dune",
                "Frank Herbert",
                "READING",
                "sci-fi",
                null,
                null
            )
        );
        post(
            "/books",
            createBookJson(
                "Foundation",
                "Asimov",
                "FINISHED",
                "sci-fi",
                null,
                null
            )
        );
        post(
            "/books",
            createBookJson("1984", "Orwell", "READING", "dystopia", null, null)
        );

        HttpResponse<String> resp = get(
            "/books?genre=sci-fi&readStatus=READING"
        );
        assertEquals(200, resp.statusCode());
        JsonArray books = JsonParser.parseString(resp.body()).getAsJsonArray();
        assertEquals(1, books.size());
        assertEquals(
            "Dune",
            books.get(0).getAsJsonObject().get("title").getAsString()
        );
    }

    // --- T47: ISBN-only POST (no title/author) returns 201 ---
    @Test
    void testT47_isbnOnlyPost() throws Exception {
        String body =
            "{\"isbn\":\"9780441013593\",\"readStatus\":\"WANT_TO_READ\"}";
        HttpResponse<String> resp = post("/books", body);
        assertEquals(201, resp.statusCode());

        JsonObject book = JsonParser.parseString(resp.body()).getAsJsonObject();
        assertNotNull(book.get("id").getAsString());
        assertTrue(book.get("title").isJsonNull());
        assertTrue(book.get("author").isJsonNull());
        assertEquals("9780441013593", book.get("isbn").getAsString());
        assertEquals("WANT_TO_READ", book.get("readStatus").getAsString());
    }

    // --- T48: title/author still required when no ISBN ---
    @Test
    void testT48_titleAuthorRequiredWithoutIsbn() throws Exception {
        String body = "{\"readStatus\":\"READING\"}";
        HttpResponse<String> resp = post("/books", body);
        assertEquals(400, resp.statusCode());
        assertTrue(resp.body().toLowerCase().contains("title"));
    }

    // --- T6: Genre filtering (case-insensitive) ---
    @Test
    void testT06_genreFilter() throws Exception {
        post(
            "/books",
            createBookJson(
                "Dune",
                "Frank Herbert",
                "READING",
                "sci-fi",
                null,
                null
            )
        );
        post(
            "/books",
            createBookJson(
                "Foundation",
                "Isaac Asimov",
                "FINISHED",
                "Sci-Fi",
                null,
                null
            )
        );
        post(
            "/books",
            createBookJson(
                "1984",
                "George Orwell",
                "WANT_TO_READ",
                "dystopia",
                null,
                null
            )
        );

        HttpResponse<String> resp = get("/books?genre=sci-fi");
        assertEquals(200, resp.statusCode());

        JsonArray books = JsonParser.parseString(resp.body()).getAsJsonArray();
        assertEquals(2, books.size());
    }

    // --- T7: Genre filter with no matches ---
    @Test
    void testT07_genreFilterNoMatches() throws Exception {
        post(
            "/books",
            createBookJson(
                "Dune",
                "Frank Herbert",
                "READING",
                "sci-fi",
                null,
                null
            )
        );

        HttpResponse<String> resp = get("/books?genre=romance");
        assertEquals(200, resp.statusCode());

        JsonArray books = JsonParser.parseString(resp.body()).getAsJsonArray();
        assertEquals(0, books.size());
    }

    // --- Search tests ---

    @Test
    void testSearchByTitle() throws Exception {
        post("/books", createBookJson("Dune", "Frank Herbert", "WANT_TO_READ"));
        post(
            "/books",
            createBookJson("Neuromancer", "William Gibson", "READING")
        );

        HttpResponse<String> resp = get("/books?search=dune");

        assertEquals(200, resp.statusCode());
        JsonArray books = JsonParser.parseString(resp.body()).getAsJsonArray();
        assertEquals(1, books.size());
        assertEquals(
            "Dune",
            books.get(0).getAsJsonObject().get("title").getAsString()
        );
    }

    @Test
    void testSearchByAuthor() throws Exception {
        post("/books", createBookJson("Dune", "Frank Herbert", "WANT_TO_READ"));
        post(
            "/books",
            createBookJson("Neuromancer", "William Gibson", "READING")
        );

        HttpResponse<String> resp = get("/books?search=gibson");

        assertEquals(200, resp.statusCode());
        JsonArray books = JsonParser.parseString(resp.body()).getAsJsonArray();
        assertEquals(1, books.size());
        assertEquals(
            "Neuromancer",
            books.get(0).getAsJsonObject().get("title").getAsString()
        );
    }

    @Test
    void testSortByTitleAsc() throws Exception {
        post(
            "/books",
            createBookJson("Zorro", "Johnston McCulley", "WANT_TO_READ")
        );
        post("/books", createBookJson("Dune", "Frank Herbert", "WANT_TO_READ"));

        HttpResponse<String> resp = get("/books?sort=title,asc");

        assertEquals(200, resp.statusCode());
        JsonArray books = JsonParser.parseString(resp.body()).getAsJsonArray();
        assertEquals(2, books.size());
        assertEquals(
            "Dune",
            books.get(0).getAsJsonObject().get("title").getAsString()
        );
        assertEquals(
            "Zorro",
            books.get(1).getAsJsonObject().get("title").getAsString()
        );
    }

    @Test
    void testSortByTitleDesc() throws Exception {
        post(
            "/books",
            createBookJson("Zorro", "Johnston McCulley", "WANT_TO_READ")
        );
        post("/books", createBookJson("Dune", "Frank Herbert", "WANT_TO_READ"));

        HttpResponse<String> resp = get("/books?sort=title,desc");

        assertEquals(200, resp.statusCode());
        JsonArray books = JsonParser.parseString(resp.body()).getAsJsonArray();
        assertEquals(2, books.size());
        assertEquals(
            "Zorro",
            books.get(0).getAsJsonObject().get("title").getAsString()
        );
        assertEquals(
            "Dune",
            books.get(1).getAsJsonObject().get("title").getAsString()
        );
    }

    // --- readingProgress tests ---

    @Test
    void testReadingProgressSetOnCreate() throws Exception {
        String body =
            "{\"title\":\"Dune\",\"author\":\"Frank Herbert\",\"readStatus\":\"READING\",\"readingProgress\":42}";
        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/books"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + authToken)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(201, resp.statusCode());
        JsonObject book = JsonParser.parseString(resp.body()).getAsJsonObject();
        assertEquals(42, book.get("readingProgress").getAsInt());
    }

    @Test
    void testReadingProgressUpdated() throws Exception {
        String createBody =
            "{\"title\":\"Dune\",\"author\":\"Frank Herbert\",\"readStatus\":\"READING\"}";
        HttpResponse<String> createResp = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/books"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + authToken)
                .POST(HttpRequest.BodyPublishers.ofString(createBody))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );

        String id = JsonParser.parseString(createResp.body())
            .getAsJsonObject()
            .get("id")
            .getAsString();

        String updateBody = "{\"readingProgress\":75}";
        HttpResponse<String> updateResp = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/books/" + id))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + authToken)
                .method("PUT", HttpRequest.BodyPublishers.ofString(updateBody))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, updateResp.statusCode());
        JsonObject updated = JsonParser.parseString(
            updateResp.body()
        ).getAsJsonObject();
        assertEquals(75, updated.get("readingProgress").getAsInt());
    }

    @Test
    void testReadingProgressValidation() throws Exception {
        String body =
            "{\"title\":\"Dune\",\"author\":\"Frank Herbert\",\"readStatus\":\"READING\",\"readingProgress\":150}";
        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/books"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + authToken)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(400, resp.statusCode());
    }

    @Test
    void testReadingProgressClearedByNull() throws Exception {
        String createBody =
            "{\"title\":\"Dune\",\"author\":\"Frank Herbert\",\"readStatus\":\"READING\",\"readingProgress\":42}";
        HttpResponse<String> createResp = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/books"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + authToken)
                .POST(HttpRequest.BodyPublishers.ofString(createBody))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(201, createResp.statusCode());
        String id = JsonParser.parseString(createResp.body())
            .getAsJsonObject()
            .get("id")
            .getAsString();

        HttpResponse<String> updateResp = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/books/" + id))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + authToken)
                .PUT(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"readingProgress\":null}"
                    )
                )
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, updateResp.statusCode());
        assertTrue(
            JsonParser.parseString(updateResp.body())
                .getAsJsonObject()
                .get("readingProgress")
                .isJsonNull()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = { "rating", "pageCount", "readingProgress" })
    void testInvalidIntegerFieldReturns400(String field) throws Exception {
        String body = String.format(
            "{\"title\":\"Dune\",\"author\":\"Frank Herbert\",\"readStatus\":\"READING\",\"%s\":\"not-a-number\"}",
            field
        );
        HttpResponse<String> resp = post("/books", body);
        assertEquals(400, resp.statusCode());
    }

    @Test
    void testReadingProgressZeroIsAccepted() throws Exception {
        HttpResponse<String> resp = post(
            "/books",
            "{\"title\":\"Dune\",\"author\":\"Frank Herbert\",\"readStatus\":\"READING\",\"readingProgress\":0}"
        );
        assertEquals(201, resp.statusCode());
        JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
        assertEquals(0, body.get("readingProgress").getAsInt());
    }

    @Test
    void testSearchLiteralUnderscore() throws Exception {
        post(
            "/books",
            "{\"title\":\"Test_Book\",\"author\":\"Author A\",\"readStatus\":\"WANT_TO_READ\"}"
        );
        post(
            "/books",
            "{\"title\":\"TestXBook\",\"author\":\"Author B\",\"readStatus\":\"WANT_TO_READ\"}"
        );

        HttpResponse<String> resp = get("/books?search=Test_Book");
        assertEquals(200, resp.statusCode());
        JsonArray results = JsonParser.parseString(
            resp.body()
        ).getAsJsonArray();
        assertEquals(1, results.size());
        assertEquals(
            "Test_Book",
            results.get(0).getAsJsonObject().get("title").getAsString()
        );
    }

    @Test
    void testInvalidReadStatusOnCreate() throws Exception {
        String body =
            "{\"title\":\"Something\",\"author\":\"Someone\",\"readStatus\":\"DID_NOT_FINISH\"}";
        HttpResponse<String> resp = post("/books", body);
        assertEquals(400, resp.statusCode());
    }

    @Test
    void testInvalidReadStatusOnUpdate() throws Exception {
        HttpResponse<String> create = post(
            "/books",
            createBookJson("Dune", "Frank Herbert", "READING")
        );
        assertEquals(201, create.statusCode());
        String id = gson
            .fromJson(create.body(), JsonObject.class)
            .get("id")
            .getAsString();

        HttpResponse<String> update = put(
            "/books/" + id,
            "{\"readStatus\":\"DID_NOT_FINISH\"}"
        );
        assertEquals(400, update.statusCode());
    }

    @Test
    void testCreateBookWithDnfStatus() throws Exception {
        String body = createBookJson(
            "Flowers for Algernon",
            "Daniel Keyes",
            "DNF"
        );
        HttpResponse<String> resp = post("/books", body);
        assertEquals(201, resp.statusCode());
        JsonObject book = gson.fromJson(resp.body(), JsonObject.class);
        assertEquals("DNF", book.get("readStatus").getAsString());
    }

    @Test
    void testUpdateBookToDnfStatus() throws Exception {
        HttpResponse<String> create = post(
            "/books",
            createBookJson("Dune", "Frank Herbert", "READING")
        );
        String id = gson
            .fromJson(create.body(), JsonObject.class)
            .get("id")
            .getAsString();

        HttpResponse<String> update = put(
            "/books/" + id,
            "{\"readStatus\":\"DNF\"}"
        );
        assertEquals(200, update.statusCode());
        JsonObject book = gson.fromJson(update.body(), JsonObject.class);
        assertEquals("DNF", book.get("readStatus").getAsString());
    }

    // --- Rating edge cases on PUT ---

    @Test
    void testRatingZeroRejectedOnPut() throws Exception {
        HttpResponse<String> create = post(
            "/books",
            createBookJson("Dune", "Frank Herbert", "READING")
        );
        String id = getIdFromResponse(create);

        HttpResponse<String> update = put("/books/" + id, "{\"rating\":0}");
        assertEquals(400, update.statusCode());
    }

    @Test
    void testRatingNullClearsOnPut() throws Exception {
        HttpResponse<String> create = post(
            "/books",
            createBookJson("Dune", "Frank Herbert", "READING", null, 4, null)
        );
        String id = getIdFromResponse(create);
        assertEquals(
            4,
            gson
                .fromJson(create.body(), JsonObject.class)
                .get("rating")
                .getAsInt()
        );

        HttpResponse<String> update = put("/books/" + id, "{\"rating\":null}");
        assertEquals(200, update.statusCode());
        assertTrue(
            gson
                .fromJson(update.body(), JsonObject.class)
                .get("rating")
                .isJsonNull()
        );
    }

    // --- Sort: unknown field is silently ignored ---

    @Test
    void testUnknownSortFieldIgnored() throws Exception {
        post("/books", createBookJson("Dune", "Frank Herbert", "WANT_TO_READ"));
        post(
            "/books",
            createBookJson("Neuromancer", "William Gibson", "WANT_TO_READ")
        );

        HttpResponse<String> resp = get("/books?sort=invalid,asc");
        assertEquals(200, resp.statusCode());
        JsonArray books = JsonParser.parseString(resp.body()).getAsJsonArray();
        assertEquals(2, books.size()); // all books returned, just unsorted
    }

    // --- Search combined with readStatus filter ---

    @Test
    void testSearchCombinedWithReadStatusFilter() throws Exception {
        post("/books", createBookJson("Dune", "Frank Herbert", "READING"));
        post(
            "/books",
            createBookJson("Dune Messiah", "Frank Herbert", "FINISHED")
        );
        post(
            "/books",
            createBookJson("Neuromancer", "William Gibson", "READING")
        );

        HttpResponse<String> resp = get(
            "/books?search=dune&readStatus=READING"
        );
        assertEquals(200, resp.statusCode());
        JsonArray books = JsonParser.parseString(resp.body()).getAsJsonArray();
        assertEquals(1, books.size());
        assertEquals(
            "Dune",
            books.get(0).getAsJsonObject().get("title").getAsString()
        );
    }

    // --- POST /books/re-enrich ---

    @Test
    void testReEnrichReturns202WhenNoBooksWithIsbn() throws Exception {
        // No books at all → queued: 0
        HttpResponse<String> resp = post("/books/re-enrich", "");
        assertEquals(202, resp.statusCode());
        JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
        assertEquals(0, body.get("queued").getAsInt());
    }

    @Test
    void testReEnrichReturns202WhenBooksHaveNoIsbn() throws Exception {
        post("/books", createBookJson("Dune", "Frank Herbert", "READING")); // no ISBN

        HttpResponse<String> resp = post("/books/re-enrich", "");
        assertEquals(202, resp.statusCode());
        JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
        assertEquals(0, body.get("queued").getAsInt());
    }

    @Test
    void testReEnrichReturns500WhenServiceUnavailable() throws Exception {
        // BookApiTest has no OpenLibraryService — re-enrich with ISBN books should be 500
        post(
            "/books",
            createBookJson(
                "Dune",
                "Frank Herbert",
                "READING",
                null,
                null,
                "9780441013593"
            )
        );

        HttpResponse<String> resp = post("/books/re-enrich", "");
        assertEquals(500, resp.statusCode());
    }

    @Test
    void testBadRequestBodyWithControlCharsIsValidJson() {
        // Directly test HttpResponse.badRequest() with control characters in the message
        com.bookshelf.framework.http.HttpResponse resp = com.bookshelf.framework.http.HttpResponse.badRequest(
            "line1\nline2\ttab\r"
        );
        String body = resp.getBody();
        // Raw control characters must NOT appear in the JSON body — they must be escaped
        assertFalse(body.contains("\n"), "Body must not contain raw newline");
        assertFalse(
            body.contains("\r"),
            "Body must not contain raw carriage return"
        );
        assertFalse(body.contains("\t"), "Body must not contain raw tab");
        // The escaped sequences must be present
        assertTrue(body.contains("\\n"), "Body must contain escaped newline");
        assertTrue(body.contains("\\t"), "Body must contain escaped tab");
        // Gson must be able to parse the body and round-trip correctly
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        assertEquals("line1\nline2\ttab\r", json.get("error").getAsString());
    }

    @Test
    void testSubjectsNotArrayReturns400() throws Exception {
        HttpResponse<String> create = post(
            "/books",
            createBookJson("Dune", "Frank Herbert", "READING")
        );
        String id = getIdFromResponse(create);

        // "subjects" is a string, not an array — should be 400, not 500
        HttpResponse<String> update = put(
            "/books/" + id,
            "{\"subjects\": \"not-an-array\"}"
        );
        assertEquals(400, update.statusCode());
    }

    @Test
    void testGenreFilterWhitespaceOnlyReturnsAllBooks() throws Exception {
        post(
            "/books",
            createBookJson(
                "Dune",
                "Frank Herbert",
                "READING",
                "sci-fi",
                null,
                null
            )
        );
        post(
            "/books",
            createBookJson(
                "1984",
                "George Orwell",
                "WANT_TO_READ",
                "dystopia",
                null,
                null
            )
        );

        // ?genre=%20%20 decodes to "  " (two spaces); should be treated as "no genre filter"
        HttpResponse<String> resp = get("/books?genre=%20%20");
        assertEquals(200, resp.statusCode());
        JsonArray books = JsonParser.parseString(resp.body()).getAsJsonArray();
        assertEquals(
            2,
            books.size(),
            "Whitespace-only genre param should not filter out books"
        );
    }

    @Test
    void testUpdateReadStatusToNullReturns400() throws Exception {
        HttpResponse<String> create = post(
            "/books",
            createBookJson("Dune", "Frank Herbert", "READING")
        );
        String id = getIdFromResponse(create);

        HttpResponse<String> update = put(
            "/books/" + id,
            "{\"readStatus\": null}"
        );
        assertEquals(400, update.statusCode());
    }

    @Test
    void testIsbnChangePreservesUserProvidedFields() throws Exception {
        String body = createBookJson(
            "Dune",
            "Frank Herbert",
            "READING",
            null,
            null,
            "9780261103573"
        );
        HttpResponse<String> create = post("/books", body);
        String id = getIdFromResponse(create);

        HttpResponse<String> update = put(
            "/books/" + id,
            "{\"isbn\": \"9780140328721\", \"publisher\": \"Custom Publisher\"}"
        );
        assertEquals(200, update.statusCode());
        JsonObject updated = JsonParser.parseString(
            update.body()
        ).getAsJsonObject();
        assertEquals(
            "Custom Publisher",
            updated.get("publisher").getAsString()
        );
    }

    @Test
    void testCreateBookWithNonArraySubjectsReturns400() throws Exception {
        String body =
            "{\"title\":\"Test\",\"author\":\"Test\",\"readStatus\":\"WANT_TO_READ\",\"subjects\":\"not-an-array\"}";
        HttpResponse<String> resp = post("/books", body);
        assertEquals(400, resp.statusCode());
    }

    @Test
    void testGetBookWithNonUuidReturns404() throws Exception {
        HttpResponse<String> resp = get("/books/not-a-uuid");
        assertEquals(404, resp.statusCode());
    }

    @Test
    void testNegativeReadingProgressOnCreateReturns400() throws Exception {
        String body =
            "{\"title\":\"Test\",\"author\":\"Test\",\"readStatus\":\"READING\",\"readingProgress\":-1}";
        HttpResponse<String> resp = post("/books", body);
        assertEquals(400, resp.statusCode());
    }

    @Test
    void testNegativeReadingProgressOnUpdateReturns400() throws Exception {
        HttpResponse<String> create = post(
            "/books",
            createBookJson("Test", "Author", "READING")
        );
        String id = getIdFromResponse(create);

        HttpResponse<String> update = put(
            "/books/" + id,
            "{\"readingProgress\": -1}"
        );
        assertEquals(400, update.statusCode());
    }

    @Test
    void testSortWithoutDirectionDefaultsAsc() throws Exception {
        post("/books", createBookJson("Bravo", "Author B", "READING"));
        post("/books", createBookJson("Alpha", "Author A", "READING"));

        HttpResponse<String> resp = get("/books?sort=title");
        assertEquals(200, resp.statusCode());
        JsonArray books = JsonParser.parseString(resp.body()).getAsJsonArray();
        assertEquals(2, books.size());
        assertEquals(
            "Alpha",
            books.get(0).getAsJsonObject().get("title").getAsString()
        );
        assertEquals(
            "Bravo",
            books.get(1).getAsJsonObject().get("title").getAsString()
        );
    }

    @Test
    void testEmptyPutBodyReturns400() throws Exception {
        HttpResponse<String> create = post(
            "/books",
            createBookJson("Test", "Author", "READING")
        );
        String id = getIdFromResponse(create);

        HttpResponse<String> update = put("/books/" + id, "");
        assertEquals(400, update.statusCode());
    }

    @Test
    void testOversizedHeaderReturns400() throws Exception {
        // Send a raw HTTP request with a header line > 8 KB, bypassing HttpClient
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            // "X-Test: " (8 chars) + 8193 'a' chars = 8201-char line, exceeds the 8 KB cap
            String bigHeader = "X-Test: " + "a".repeat(8193);
            String request =
                "GET /books HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                bigHeader +
                "\r\n" +
                "\r\n";
            out.write(
                request.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            );
            out.flush();

            // Read the first chunk of the response
            byte[] buf = new byte[256];
            int n = socket.getInputStream().read(buf);
            assertTrue(n > 0, "Server must respond");
            String responseStart = new String(
                buf,
                0,
                n,
                java.nio.charset.StandardCharsets.UTF_8
            );
            assertTrue(
                responseStart.startsWith("HTTP/1.1 400"),
                "Expected 400 for oversized header, got: " + responseStart
            );
        }
    }

    @Test
    void testTooManyHeadersRejected() throws Exception {
        // Server should reject requests with >100 headers.
        // It may respond with 400 or simply close/reset the connection.
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            String request = "GET /books HTTP/1.1\r\nHost: localhost\r\n";
            out.write(request.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try {
                for (int i = 0; i < 200; i++) {
                    out.write(("X-Header-" + i + ": value\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                out.write("\r\n".getBytes());
                out.flush();
            } catch (IOException ignored) {
                // Server may reset connection before we finish writing — that's fine
                return;
            }
            try {
                byte[] buf = new byte[256];
                int n = socket.getInputStream().read(buf);
                if (n > 0) {
                    String resp = new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8);
                    assertTrue(resp.contains("400"),
                        "Expected 400 for too many headers, got: " + resp);
                }
            } catch (IOException ignored) {
                // Connection reset on read is also acceptable
            }
        }
    }

    // --- Review / Notes tests ---

    @Test
    void testCreateBookWithReview() throws Exception {
        JsonObject json = new JsonObject();
        json.addProperty("title", "Dune");
        json.addProperty("author", "Frank Herbert");
        json.addProperty("readStatus", "FINISHED");
        json.addProperty("review", "Great book!");
        var res = post("/books", json.toString());
        assertEquals(201, res.statusCode());
        JsonObject body = JsonParser.parseString(res.body()).getAsJsonObject();
        assertEquals("Great book!", body.get("review").getAsString());

        String id = body.get("id").getAsString();
        var getRes = get("/books/" + id);
        JsonObject getBody = JsonParser.parseString(getRes.body()).getAsJsonObject();
        assertEquals("Great book!", getBody.get("review").getAsString());
    }

    @Test
    void testCreateBookWithoutReview() throws Exception {
        var res = post("/books", createBookJson("Test", "Author", "READING"));
        assertEquals(201, res.statusCode());
        JsonObject body = JsonParser.parseString(res.body()).getAsJsonObject();
        assertTrue(body.get("review").isJsonNull());
    }

    @Test
    void testUpdateReviewViaPut() throws Exception {
        var createRes = post("/books", createBookJson("Test", "Author", "READING"));
        String id = getIdFromResponse(createRes);

        var updateRes = put("/books/" + id, "{\"review\": \"Added my thoughts\"}");
        assertEquals(200, updateRes.statusCode());
        JsonObject body = JsonParser.parseString(updateRes.body()).getAsJsonObject();
        assertEquals("Added my thoughts", body.get("review").getAsString());
        assertEquals("Test", body.get("title").getAsString());
    }

    @Test
    void testClearReviewWithNull() throws Exception {
        JsonObject json = new JsonObject();
        json.addProperty("title", "Test");
        json.addProperty("author", "Author");
        json.addProperty("readStatus", "FINISHED");
        json.addProperty("review", "Initial review");
        var createRes = post("/books", json.toString());
        String id = getIdFromResponse(createRes);

        var updateRes = put("/books/" + id, "{\"review\": null}");
        assertEquals(200, updateRes.statusCode());
        JsonObject body = JsonParser.parseString(updateRes.body()).getAsJsonObject();
        assertTrue(body.get("review").isJsonNull());
    }

    @Test
    void testLongReviewAccepted() throws Exception {
        String longReview = "x".repeat(5000);
        JsonObject json = new JsonObject();
        json.addProperty("title", "Test");
        json.addProperty("author", "Author");
        json.addProperty("readStatus", "READING");
        json.addProperty("review", longReview);
        var res = post("/books", json.toString());
        assertEquals(201, res.statusCode());

        String id = getIdFromResponse(res);
        var getRes = get("/books/" + id);
        JsonObject body = JsonParser.parseString(getRes.body()).getAsJsonObject();
        assertEquals(5000, body.get("review").getAsString().length());
    }

    @Test
    void testReviewInListEndpoint() throws Exception {
        JsonObject json = new JsonObject();
        json.addProperty("title", "Test");
        json.addProperty("author", "Author");
        json.addProperty("readStatus", "READING");
        json.addProperty("review", "My review");
        post("/books", json.toString());

        var listRes = get("/books");
        JsonArray arr = JsonParser.parseString(listRes.body()).getAsJsonArray();
        assertEquals(1, arr.size());
        assertEquals("My review", arr.get(0).getAsJsonObject().get("review").getAsString());
    }

    @Test
    void testReviewWithSpecialCharacters() throws Exception {
        String specialReview = "Contains \"quotes\", newlines\nand unicode: \u00e9\u00e0\u00fc \u2014 em dash";
        JsonObject json = new JsonObject();
        json.addProperty("title", "Test");
        json.addProperty("author", "Author");
        json.addProperty("readStatus", "READING");
        json.addProperty("review", specialReview);
        var res = post("/books", json.toString());
        assertEquals(201, res.statusCode());

        String id = getIdFromResponse(res);
        var getRes = get("/books/" + id);
        JsonObject body = JsonParser.parseString(getRes.body()).getAsJsonObject();
        assertEquals(specialReview, body.get("review").getAsString());
    }

    // --- Start / Finish Dates Tests ---

    @Test
    void testAutoSetStartedAtOnReading() throws Exception {
        JsonObject json = new JsonObject();
        json.addProperty("title", "Test");
        json.addProperty("author", "Author");
        json.addProperty("readStatus", "READING");
        var res = post("/books", json.toString());
        assertEquals(201, res.statusCode());
        JsonObject body = JsonParser.parseString(res.body()).getAsJsonObject();
        assertEquals(java.time.LocalDate.now().toString(), body.get("startedAt").getAsString());
        assertTrue(body.get("finishedAt").isJsonNull());
    }

    @Test
    void testAutoSetFinishedAtOnFinished() throws Exception {
        JsonObject json = new JsonObject();
        json.addProperty("title", "Test");
        json.addProperty("author", "Author");
        json.addProperty("readStatus", "FINISHED");
        var res = post("/books", json.toString());
        assertEquals(201, res.statusCode());
        JsonObject body = JsonParser.parseString(res.body()).getAsJsonObject();
        String today = java.time.LocalDate.now().toString();
        assertEquals(today, body.get("startedAt").getAsString());
        assertEquals(today, body.get("finishedAt").getAsString());
    }

    @Test
    void testUserProvidedDatesPreserved() throws Exception {
        JsonObject json = new JsonObject();
        json.addProperty("title", "Test");
        json.addProperty("author", "Author");
        json.addProperty("readStatus", "READING");
        json.addProperty("startedAt", "2026-01-15");
        var res = post("/books", json.toString());
        assertEquals(201, res.statusCode());
        JsonObject body = JsonParser.parseString(res.body()).getAsJsonObject();
        assertEquals("2026-01-15", body.get("startedAt").getAsString());
    }

    @Test
    void testStatusChangeToFinishedAutoSetsFinishedAt() throws Exception {
        JsonObject json = new JsonObject();
        json.addProperty("title", "Test");
        json.addProperty("author", "Author");
        json.addProperty("readStatus", "READING");
        json.addProperty("startedAt", "2026-02-01");
        var res = post("/books", json.toString());
        assertEquals(201, res.statusCode());
        String id = getIdFromResponse(res);

        JsonObject update = new JsonObject();
        update.addProperty("readStatus", "FINISHED");
        var putRes = put("/books/" + id, update.toString());
        assertEquals(200, putRes.statusCode());
        JsonObject body = JsonParser.parseString(putRes.body()).getAsJsonObject();
        assertEquals("2026-02-01", body.get("startedAt").getAsString());
        assertEquals(java.time.LocalDate.now().toString(), body.get("finishedAt").getAsString());
    }

    @Test
    void testStatusChangeToWantToReadClearsDates() throws Exception {
        JsonObject json = new JsonObject();
        json.addProperty("title", "Test");
        json.addProperty("author", "Author");
        json.addProperty("readStatus", "FINISHED");
        json.addProperty("startedAt", "2026-01-01");
        json.addProperty("finishedAt", "2026-02-01");
        var res = post("/books", json.toString());
        assertEquals(201, res.statusCode());
        String id = getIdFromResponse(res);

        JsonObject update = new JsonObject();
        update.addProperty("readStatus", "WANT_TO_READ");
        var putRes = put("/books/" + id, update.toString());
        assertEquals(200, putRes.statusCode());
        JsonObject body = JsonParser.parseString(putRes.body()).getAsJsonObject();
        assertTrue(body.get("startedAt").isJsonNull());
        assertTrue(body.get("finishedAt").isJsonNull());
    }

    @Test
    void testFinishedAtBeforeStartedAtRejected() throws Exception {
        JsonObject json = new JsonObject();
        json.addProperty("title", "Test");
        json.addProperty("author", "Author");
        json.addProperty("readStatus", "FINISHED");
        json.addProperty("startedAt", "2026-03-15");
        json.addProperty("finishedAt", "2026-03-01");
        var res = post("/books", json.toString());
        assertEquals(400, res.statusCode());
    }

    @Test
    void testFutureDateRejected() throws Exception {
        JsonObject json = new JsonObject();
        json.addProperty("title", "Test");
        json.addProperty("author", "Author");
        json.addProperty("readStatus", "READING");
        json.addProperty("startedAt", "2099-01-01");
        var res = post("/books", json.toString());
        assertEquals(400, res.statusCode());
    }

    @Test
    void testInvalidDateFormatRejected() throws Exception {
        JsonObject json = new JsonObject();
        json.addProperty("title", "Test");
        json.addProperty("author", "Author");
        json.addProperty("readStatus", "READING");
        json.addProperty("startedAt", "March 1, 2026");
        var res = post("/books", json.toString());
        assertEquals(400, res.statusCode());
    }

    @Test
    void testExplicitNullClearsDates() throws Exception {
        JsonObject json = new JsonObject();
        json.addProperty("title", "Test");
        json.addProperty("author", "Author");
        json.addProperty("readStatus", "READING");
        json.addProperty("startedAt", "2026-01-15");
        json.addProperty("finishedAt", "2026-02-15");
        var res = post("/books", json.toString());
        assertEquals(201, res.statusCode());
        String id = getIdFromResponse(res);

        // Clear only startedAt
        String updateBody = "{\"startedAt\": null}";
        var putRes = put("/books/" + id, updateBody);
        assertEquals(200, putRes.statusCode());
        JsonObject body = JsonParser.parseString(putRes.body()).getAsJsonObject();
        assertTrue(body.get("startedAt").isJsonNull());
        assertEquals("2026-02-15", body.get("finishedAt").getAsString());
    }

    @Test
    void testWantToReadGetsNoAutoDates() throws Exception {
        JsonObject json = new JsonObject();
        json.addProperty("title", "Test");
        json.addProperty("author", "Author");
        json.addProperty("readStatus", "WANT_TO_READ");
        var res = post("/books", json.toString());
        assertEquals(201, res.statusCode());
        JsonObject body = JsonParser.parseString(res.body()).getAsJsonObject();
        assertTrue(body.get("startedAt").isJsonNull());
        assertTrue(body.get("finishedAt").isJsonNull());
    }

    @Test
    void testDnfAutoSetsFinishedAt() throws Exception {
        JsonObject json = new JsonObject();
        json.addProperty("title", "Test");
        json.addProperty("author", "Author");
        json.addProperty("readStatus", "READING");
        json.addProperty("startedAt", "2026-02-01");
        var res = post("/books", json.toString());
        assertEquals(201, res.statusCode());
        String id = getIdFromResponse(res);

        JsonObject update = new JsonObject();
        update.addProperty("readStatus", "DNF");
        var putRes = put("/books/" + id, update.toString());
        assertEquals(200, putRes.statusCode());
        JsonObject body = JsonParser.parseString(putRes.body()).getAsJsonObject();
        assertEquals("2026-02-01", body.get("startedAt").getAsString());
        assertEquals(java.time.LocalDate.now().toString(), body.get("finishedAt").getAsString());
    }

    // --- Half-star rating tests ---

    @Test
    void testHalfStar_createWithHalfStarRating() throws Exception {
        JsonObject json = new JsonObject();
        json.addProperty("title", "Half Star Book");
        json.addProperty("author", "Author");
        json.addProperty("readStatus", "READING");
        json.addProperty("rating", 3.5);
        var res = post("/books", json.toString());
        assertEquals(201, res.statusCode());
        JsonObject body = JsonParser.parseString(res.body()).getAsJsonObject();
        assertEquals(3.5, body.get("rating").getAsDouble(), 0.001);

        // Verify on GET
        String id = body.get("id").getAsString();
        var getRes = get("/books/" + id);
        assertEquals(200, getRes.statusCode());
        JsonObject fetched = JsonParser.parseString(getRes.body()).getAsJsonObject();
        assertEquals(3.5, fetched.get("rating").getAsDouble(), 0.001);
    }

    @Test
    void testHalfStar_createWithIntegerRatingStillWorks() throws Exception {
        JsonObject json = new JsonObject();
        json.addProperty("title", "Integer Rating Book");
        json.addProperty("author", "Author");
        json.addProperty("readStatus", "READING");
        json.addProperty("rating", 4);
        var res = post("/books", json.toString());
        assertEquals(201, res.statusCode());
        JsonObject body = JsonParser.parseString(res.body()).getAsJsonObject();
        // Whole-number ratings should come back as integers
        assertEquals(4, body.get("rating").getAsInt());
    }

    @Test
    void testHalfStar_updateToHalfStarRating() throws Exception {
        JsonObject json = new JsonObject();
        json.addProperty("title", "Update Test");
        json.addProperty("author", "Author");
        json.addProperty("readStatus", "READING");
        json.addProperty("rating", 3);
        var res = post("/books", json.toString());
        assertEquals(201, res.statusCode());
        String id = getIdFromResponse(res);

        JsonObject update = new JsonObject();
        update.addProperty("rating", 4.5);
        var putRes = put("/books/" + id, update.toString());
        assertEquals(200, putRes.statusCode());
        JsonObject body = JsonParser.parseString(putRes.body()).getAsJsonObject();
        assertEquals(4.5, body.get("rating").getAsDouble(), 0.001);
    }

    @Test
    void testHalfStar_invalidValuesRejected() throws Exception {
        // 2.7 is not a valid 0.5 increment
        var res1 = post("/books",
            "{\"title\":\"T\",\"author\":\"A\",\"readStatus\":\"READING\",\"rating\":2.7}");
        assertEquals(400, res1.statusCode());

        // 0.3 is not a valid 0.5 increment
        var res2 = post("/books",
            "{\"title\":\"T\",\"author\":\"A\",\"readStatus\":\"READING\",\"rating\":0.3}");
        assertEquals(400, res2.statusCode());

        // 5.5 is out of range
        var res3 = post("/books",
            "{\"title\":\"T\",\"author\":\"A\",\"readStatus\":\"READING\",\"rating\":5.5}");
        assertEquals(400, res3.statusCode());
    }

    @Test
    void testHalfStar_minimumRating() throws Exception {
        JsonObject json = new JsonObject();
        json.addProperty("title", "Min Rating");
        json.addProperty("author", "Author");
        json.addProperty("readStatus", "READING");
        json.addProperty("rating", 0.5);
        var res = post("/books", json.toString());
        assertEquals(201, res.statusCode());
        JsonObject body = JsonParser.parseString(res.body()).getAsJsonObject();
        assertEquals(0.5, body.get("rating").getAsDouble(), 0.001);
    }

    @Test
    void testHalfStar_sortByRatingStillWorks() throws Exception {
        // Create books with half-star ratings
        for (double r : new double[]{2.5, 4.0, 3.5}) {
            JsonObject json = new JsonObject();
            json.addProperty("title", "Book " + r);
            json.addProperty("author", "Author");
            json.addProperty("readStatus", "READING");
            json.addProperty("rating", r);
            post("/books", json.toString());
        }

        var res = get("/books?sort=rating,desc");
        assertEquals(200, res.statusCode());
        JsonArray books = JsonParser.parseString(res.body()).getAsJsonArray();
        assertEquals(3, books.size());
        assertEquals(4.0, books.get(0).getAsJsonObject().get("rating").getAsDouble(), 0.001);
        assertEquals(3.5, books.get(1).getAsJsonObject().get("rating").getAsDouble(), 0.001);
        assertEquals(2.5, books.get(2).getAsJsonObject().get("rating").getAsDouble(), 0.001);
    }

    @Test
    void testHalfStar_zeroStillRejected() throws Exception {
        // Rating 0 should still be rejected as invalid user input
        var res = post("/books",
            "{\"title\":\"T\",\"author\":\"A\",\"readStatus\":\"READING\",\"rating\":0}");
        assertEquals(400, res.statusCode());
    }

    // --- Subject filter tests ---

    private String createBookWithSubjects(String title, java.util.List<String> subjects) throws Exception {
        return createBookWithSubjects(title, subjects, "WANT_TO_READ");
    }

    private String createBookWithSubjects(String title, java.util.List<String> subjects, String readStatus) throws Exception {
        var createResp = post("/books", gson.toJson(java.util.Map.of(
            "title", title, "author", "Test Author", "readStatus", readStatus)));
        String id = gson.fromJson(createResp.body(), JsonObject.class).get("id").getAsString();
        JsonObject update = new JsonObject();
        update.add("subjects", gson.toJsonTree(subjects));
        put("/books/" + id, gson.toJson(update));
        return id;
    }

    @Test
    void testFilterBySubject() throws Exception {
        createBookWithSubjects("Cosmos", java.util.List.of("Science", "Astronomy"));
        createBookWithSubjects("A Brief History of Time", java.util.List.of("Science", "Physics"));
        createBookWithSubjects("Pride and Prejudice", java.util.List.of("Fiction", "Romance"));

        var res = get("/books?subject=science");
        assertEquals(200, res.statusCode());
        JsonArray books = JsonParser.parseString(res.body()).getAsJsonArray();
        assertEquals(2, books.size());
    }

    @Test
    void testFilterBySubjectCaseInsensitive() throws Exception {
        createBookWithSubjects("Cosmos", java.util.List.of("Science Fiction"));
        createBookWithSubjects("Neuromancer", java.util.List.of("science fiction"));

        var res = get("/books?subject=SCIENCE");
        assertEquals(200, res.statusCode());
        JsonArray books = JsonParser.parseString(res.body()).getAsJsonArray();
        assertEquals(2, books.size());
    }

    @Test
    void testFilterBySubjectNoMatches() throws Exception {
        createBookWithSubjects("Cosmos", java.util.List.of("Science", "Astronomy"));
        createBookWithSubjects("Dune", java.util.List.of("Science Fiction"));

        var res = get("/books?subject=romance");
        assertEquals(200, res.statusCode());
        JsonArray books = JsonParser.parseString(res.body()).getAsJsonArray();
        assertEquals(0, books.size());
    }

    @Test
    void testFilterBySubjectWithReadStatus() throws Exception {
        createBookWithSubjects("Cosmos", java.util.List.of("Science"), "FINISHED");
        createBookWithSubjects("A Brief History of Time", java.util.List.of("Science"), "READING");
        createBookWithSubjects("Physics", java.util.List.of("Science"), "WANT_TO_READ");

        var res = get("/books?subject=science&readStatus=FINISHED");
        assertEquals(200, res.statusCode());
        JsonArray books = JsonParser.parseString(res.body()).getAsJsonArray();
        assertEquals(1, books.size());
        assertEquals("Cosmos", books.get(0).getAsJsonObject().get("title").getAsString());
    }

    @Test
    void testFilterBySubjectWithSort() throws Exception {
        createBookWithSubjects("Zebra Book", java.util.List.of("Science"));
        createBookWithSubjects("Alpha Book", java.util.List.of("Science"));
        createBookWithSubjects("Middle Book", java.util.List.of("Science"));

        var res = get("/books?subject=science&sort=title,asc");
        assertEquals(200, res.statusCode());
        JsonArray books = JsonParser.parseString(res.body()).getAsJsonArray();
        assertEquals(3, books.size());
        assertEquals("Alpha Book", books.get(0).getAsJsonObject().get("title").getAsString());
        assertEquals("Middle Book", books.get(1).getAsJsonObject().get("title").getAsString());
        assertEquals("Zebra Book", books.get(2).getAsJsonObject().get("title").getAsString());
    }

    // --- Pagination tests ---

    @Test
    void testPaginationBasic() throws Exception {
        for (int i = 1; i <= 25; i++) {
            post("/books", gson.toJson(java.util.Map.of(
                "title", "Book " + String.format("%02d", i),
                "author", "Author", "readStatus", "WANT_TO_READ")));
        }
        var response = get("/books?page=1&size=10");
        assertEquals(200, response.statusCode());
        var obj = gson.fromJson(response.body(), JsonObject.class);
        assertEquals(10, obj.getAsJsonArray("books").size());
        assertEquals(1, obj.get("page").getAsInt());
        assertEquals(10, obj.get("size").getAsInt());
        assertEquals(25, obj.get("totalItems").getAsInt());
        assertEquals(3, obj.get("totalPages").getAsInt());
    }

    @Test
    void testPaginationDefaultSize() throws Exception {
        for (int i = 1; i <= 25; i++) {
            post("/books", gson.toJson(java.util.Map.of(
                "title", "Book " + i, "author", "Author", "readStatus", "WANT_TO_READ")));
        }
        var response = get("/books?page=1");
        assertEquals(200, response.statusCode());
        var obj = gson.fromJson(response.body(), JsonObject.class);
        assertEquals(20, obj.getAsJsonArray("books").size());
        assertEquals(20, obj.get("size").getAsInt());
    }

    @Test
    void testPaginationSizeClampedTo100() throws Exception {
        for (int i = 1; i <= 5; i++) {
            post("/books", gson.toJson(java.util.Map.of(
                "title", "Book " + i, "author", "Author", "readStatus", "WANT_TO_READ")));
        }
        var response = get("/books?page=1&size=999");
        assertEquals(200, response.statusCode());
        var obj = gson.fromJson(response.body(), JsonObject.class);
        assertEquals(100, obj.get("size").getAsInt());
    }

    @Test
    void testPaginationInvalidPage() throws Exception {
        var resp1 = get("/books?page=0");
        assertEquals(400, resp1.statusCode());
        var resp2 = get("/books?page=-1");
        assertEquals(400, resp2.statusCode());
        var resp3 = get("/books?page=abc");
        assertEquals(400, resp3.statusCode());
    }

    @Test
    void testPaginationBeyondRange() throws Exception {
        for (int i = 1; i <= 5; i++) {
            post("/books", gson.toJson(java.util.Map.of(
                "title", "Book " + i, "author", "Author", "readStatus", "WANT_TO_READ")));
        }
        var response = get("/books?page=99&size=10");
        assertEquals(200, response.statusCode());
        var obj = gson.fromJson(response.body(), JsonObject.class);
        assertEquals(0, obj.getAsJsonArray("books").size());
        assertEquals(5, obj.get("totalItems").getAsInt());
    }

    @Test
    void testNoPaginationParamReturnsArray() throws Exception {
        post("/books", gson.toJson(java.util.Map.of(
            "title", "Book 1", "author", "Author", "readStatus", "WANT_TO_READ")));
        var response = get("/books");
        assertEquals(200, response.statusCode());
        var arr = gson.fromJson(response.body(), JsonArray.class);
        assertNotNull(arr);
        assertTrue(arr.size() > 0);
    }

    @Test
    void testPaginationInvalidSizeReturns400() throws Exception {
        var response = get("/books?page=1&size=abc");
        assertEquals(400, response.statusCode());
    }

    // --- Stats endpoint tests ---

    @Test
    void testStatsEmpty() throws Exception {
        var response = get("/books/stats");
        assertEquals(200, response.statusCode());
        var stats = gson.fromJson(response.body(), JsonObject.class);
        assertEquals(0, stats.get("totalBooks").getAsInt());
        assertEquals(0, stats.getAsJsonObject("byStatus").get("WANT_TO_READ").getAsInt());
        assertEquals(0, stats.getAsJsonObject("byStatus").get("READING").getAsInt());
        assertEquals(0, stats.getAsJsonObject("byStatus").get("FINISHED").getAsInt());
        assertEquals(0, stats.getAsJsonObject("byStatus").get("DNF").getAsInt());
        assertEquals(0, stats.getAsJsonObject("ratings").get("unrated").getAsInt());
        assertEquals(0, stats.getAsJsonObject("pages").get("totalRead").getAsInt());
    }

    @Test
    void testStatsWithData() throws Exception {
        post("/books", gson.toJson(java.util.Map.of(
            "title", "Book A", "author", "Author X", "readStatus", "FINISHED",
            "rating", 4.0, "pageCount", 300, "genre", "Fiction",
            "finishedAt", "2025-06-15")));
        post("/books", gson.toJson(java.util.Map.of(
            "title", "Book B", "author", "Author Y", "readStatus", "READING",
            "rating", 3.5, "pageCount", 200, "genre", "Science")));
        post("/books", gson.toJson(java.util.Map.of(
            "title", "Book C", "author", "Author X", "readStatus", "WANT_TO_READ",
            "genre", "Fiction")));

        var response = get("/books/stats");
        assertEquals(200, response.statusCode());
        var stats = gson.fromJson(response.body(), JsonObject.class);

        assertEquals(3, stats.get("totalBooks").getAsInt());
        var byStatus = stats.getAsJsonObject("byStatus");
        assertEquals(1, byStatus.get("FINISHED").getAsInt());
        assertEquals(1, byStatus.get("READING").getAsInt());
        assertEquals(1, byStatus.get("WANT_TO_READ").getAsInt());
    }

    @Test
    void testStatsGenreBreakdown() throws Exception {
        post("/books", gson.toJson(java.util.Map.of(
            "title", "A", "author", "A", "readStatus", "FINISHED", "genre", "Fiction")));
        post("/books", gson.toJson(java.util.Map.of(
            "title", "B", "author", "B", "readStatus", "READING", "genre", "Fiction")));
        post("/books", gson.toJson(java.util.Map.of(
            "title", "C", "author", "C", "readStatus", "FINISHED", "genre", "Science")));

        var response = get("/books/stats");
        var stats = gson.fromJson(response.body(), JsonObject.class);
        var byGenre = stats.getAsJsonObject("byGenre");
        assertEquals(2, byGenre.get("Fiction").getAsInt());
        assertEquals(1, byGenre.get("Science").getAsInt());
    }

    @Test
    void testStatsTopAuthors() throws Exception {
        for (int i = 0; i < 3; i++) {
            post("/books", gson.toJson(java.util.Map.of(
                "title", "Book " + i, "author", "Popular Author", "readStatus", "FINISHED")));
        }
        post("/books", gson.toJson(java.util.Map.of(
            "title", "Solo", "author", "One-Hit Wonder", "readStatus", "FINISHED")));

        var response = get("/books/stats");
        var stats = gson.fromJson(response.body(), JsonObject.class);
        var topAuthors = stats.getAsJsonArray("topAuthors");
        assertTrue(topAuthors.size() >= 1);
        var first = topAuthors.get(0).getAsJsonObject();
        assertEquals("Popular Author", first.get("author").getAsString());
        assertEquals(3, first.get("count").getAsInt());
    }

    @Test
    void testStatsFinishedByMonth() throws Exception {
        post("/books", gson.toJson(java.util.Map.of(
            "title", "Jan Book", "author", "A", "readStatus", "FINISHED",
            "finishedAt", "2025-01-15")));
        post("/books", gson.toJson(java.util.Map.of(
            "title", "Jan Book 2", "author", "B", "readStatus", "FINISHED",
            "finishedAt", "2025-01-20")));
        post("/books", gson.toJson(java.util.Map.of(
            "title", "Mar Book", "author", "C", "readStatus", "FINISHED",
            "finishedAt", "2025-03-10")));

        var response = get("/books/stats");
        var stats = gson.fromJson(response.body(), JsonObject.class);
        var byMonth = stats.getAsJsonObject("finishedByMonth");
        assertEquals(2, byMonth.get("2025-01").getAsInt());
        assertEquals(1, byMonth.get("2025-03").getAsInt());
    }

    // --- CSV Export/Import tests ---

    private HttpResponse<String> postCsv(String path, String csvBody)
        throws IOException, InterruptedException {
        return client.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Content-Type", "text/csv")
                .header("Authorization", "Bearer " + authToken)
                .POST(HttpRequest.BodyPublishers.ofString(csvBody))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
    }

    @Test
    void testExportEmpty() throws Exception {
        var response = get("/books/export");
        assertEquals(200, response.statusCode());
        String body = response.body();
        assertTrue(body.startsWith("id,title,author,genre,rating,isbn"));
        // Only header row, no data
        String[] lines = body.trim().split("\r\n|\n");
        assertEquals(1, lines.length);
    }

    @Test
    void testExportBooks() throws Exception {
        post("/books", gson.toJson(java.util.Map.of(
            "title", "Dune", "author", "Frank Herbert", "readStatus", "FINISHED",
            "isbn", "9780441013593", "rating", 4.5, "genre", "Science Fiction")));
        post("/books", gson.toJson(java.util.Map.of(
            "title", "Neuromancer", "author", "William Gibson", "readStatus", "READING")));
        post("/books", gson.toJson(java.util.Map.of(
            "title", "Snow Crash", "author", "Neal Stephenson", "readStatus", "WANT_TO_READ")));

        var response = get("/books/export");
        assertEquals(200, response.statusCode());
        String body = response.body();
        String[] lines = body.trim().split("\r\n|\n");
        assertEquals(4, lines.length); // header + 3 books
        assertTrue(body.contains("Dune"));
        assertTrue(body.contains("Neuromancer"));
        assertTrue(body.contains("Snow Crash"));
    }

    @Test
    void testExportSpecialCharacters() throws Exception {
        post("/books", gson.toJson(java.util.Map.of(
            "title", "The \"Big\" Book, Vol. 1", "author", "Some Author",
            "readStatus", "WANT_TO_READ")));

        var response = get("/books/export");
        assertEquals(200, response.statusCode());
        // The title should be properly CSV-escaped
        assertTrue(response.body().contains("\"The \"\"Big\"\" Book, Vol. 1\""));
    }

    @Test
    void testImportMyBookShelfCsv() throws Exception {
        // Create books, export, clear, re-import
        post("/books", gson.toJson(java.util.Map.of(
            "title", "Book A", "author", "Author A", "readStatus", "FINISHED",
            "rating", 4.0, "genre", "Fiction")));
        post("/books", gson.toJson(java.util.Map.of(
            "title", "Book B", "author", "Author B", "readStatus", "READING")));
        post("/books", gson.toJson(java.util.Map.of(
            "title", "Book C", "author", "Author C", "readStatus", "WANT_TO_READ")));

        var exportResp = get("/books/export");
        String csv = exportResp.body();

        repository.clear();

        var importResp = postCsv("/books/import", csv);
        assertEquals(200, importResp.statusCode());
        var result = gson.fromJson(importResp.body(), JsonObject.class);
        assertEquals(3, result.get("imported").getAsInt());
        assertEquals(0, result.get("skipped").getAsInt());

        var booksResp = get("/books");
        var arr = gson.fromJson(booksResp.body(), JsonArray.class);
        assertEquals(3, arr.size());
    }

    @Test
    void testImportGoodreadsCsv() throws Exception {
        String csv = "Title,Author,ISBN,ISBN13,My Rating,Publisher,Number of Pages,Year Published,Exclusive Shelf,Date Read,My Review\r\n"
            + "Dune,Frank Herbert,,=\"9780441013593\",5,Ace,,1965,read,2025/01/15,Great book\r\n"
            + "Neuromancer,William Gibson,,=\"9780441569595\",4,Ace,,1984,currently-reading,,\r\n";

        var response = postCsv("/books/import", csv);
        assertEquals(200, response.statusCode());
        var result = gson.fromJson(response.body(), JsonObject.class);
        assertEquals(2, result.get("imported").getAsInt());

        var booksResp = get("/books");
        var books = gson.fromJson(booksResp.body(), JsonArray.class);
        assertEquals(2, books.size());

        // Check status mapping
        boolean foundFinished = false, foundReading = false;
        for (var el : books) {
            var b = el.getAsJsonObject();
            if ("Dune".equals(b.get("title").getAsString())) {
                assertEquals("FINISHED", b.get("readStatus").getAsString());
                foundFinished = true;
            }
            if ("Neuromancer".equals(b.get("title").getAsString())) {
                assertEquals("READING", b.get("readStatus").getAsString());
                foundReading = true;
            }
        }
        assertTrue(foundFinished);
        assertTrue(foundReading);
    }

    @Test
    void testImportSkipsDuplicateIsbn() throws Exception {
        post("/books", gson.toJson(java.util.Map.of(
            "title", "Existing Book", "author", "Author", "readStatus", "FINISHED",
            "isbn", "9780441013593")));

        String csv = "Title,Author,ISBN,ISBN13,My Rating,Exclusive Shelf\r\n"
            + "Dune,Frank Herbert,,=\"9780441013593\",5,read\r\n";

        var response = postCsv("/books/import", csv);
        assertEquals(200, response.statusCode());
        var result = gson.fromJson(response.body(), JsonObject.class);
        assertEquals(0, result.get("imported").getAsInt());
        assertEquals(1, result.get("skipped").getAsInt());
    }

    @Test
    void testImportRejectsEmptyBody() throws Exception {
        var response = postCsv("/books/import", "");
        assertEquals(400, response.statusCode());
    }

    @Test
    void testImportGoodreadsIsbnCleanup() throws Exception {
        String csv = "Title,Author,ISBN,ISBN13,My Rating,Exclusive Shelf\r\n"
            + "Dune,Frank Herbert,=\"0441013597\",=\"9780441013593\",5,read\r\n";

        var response = postCsv("/books/import", csv);
        assertEquals(200, response.statusCode());

        // Verify ISBN was cleaned
        var booksResp = get("/books");
        var books = gson.fromJson(booksResp.body(), JsonArray.class);
        assertEquals(1, books.size());
        assertEquals("9780441013593", books.get(0).getAsJsonObject().get("isbn").getAsString());
    }

    @Test
    void testImportRoundTrip() throws Exception {
        post("/books", gson.toJson(java.util.Map.of(
            "title", "Book 1", "author", "Author 1", "readStatus", "FINISHED",
            "rating", 3.5, "genre", "Fantasy", "review", "Loved it")));
        post("/books", gson.toJson(java.util.Map.of(
            "title", "Book 2", "author", "Author 2", "readStatus", "READING",
            "readingProgress", 42)));

        var exportResp = get("/books/export");
        String csv = exportResp.body();

        repository.clear();
        assertEquals(0, repository.findAll().size());

        var importResp = postCsv("/books/import", csv);
        assertEquals(200, importResp.statusCode());
        var result = gson.fromJson(importResp.body(), JsonObject.class);
        assertEquals(2, result.get("imported").getAsInt());

        var booksResp = get("/books");
        var books = gson.fromJson(booksResp.body(), JsonArray.class);
        assertEquals(2, books.size());

        // Check fields preserved
        boolean found1 = false, found2 = false;
        for (var el : books) {
            var b = el.getAsJsonObject();
            if ("Book 1".equals(b.get("title").getAsString())) {
                assertEquals("FINISHED", b.get("readStatus").getAsString());
                assertEquals(3.5, b.get("rating").getAsDouble(), 0.01);
                assertEquals("Fantasy", b.get("genre").getAsString());
                found1 = true;
            }
            if ("Book 2".equals(b.get("title").getAsString())) {
                assertEquals("READING", b.get("readStatus").getAsString());
                assertEquals(42, b.get("readingProgress").getAsInt());
                found2 = true;
            }
        }
        assertTrue(found1);
        assertTrue(found2);
    }

    @Test
    void testCreateBookTitleTooLong() throws Exception {
        String longTitle = "A".repeat(501);
        HttpResponse<String> resp = post("/books",
            "{\"title\":\"" + longTitle + "\",\"author\":\"Test\",\"readStatus\":\"WANT_TO_READ\"}");
        assertEquals(400, resp.statusCode());
        assertTrue(resp.body().contains("title exceeds maximum length"));
    }

    @Test
    void testCreateBookReviewTooLong() throws Exception {
        String longReview = "A".repeat(5001);
        String body = "{\"title\":\"Test\",\"author\":\"Test\",\"readStatus\":\"WANT_TO_READ\",\"review\":\"" + longReview + "\"}";
        HttpResponse<String> resp = post("/books", body);
        assertEquals(400, resp.statusCode());
        assertTrue(resp.body().contains("review exceeds maximum length"));
    }

    @Test
    void testCreateBookTooManySubjects() throws Exception {
        StringBuilder subjects = new StringBuilder("[");
        for (int i = 0; i < 51; i++) {
            if (i > 0) subjects.append(",");
            subjects.append("\"subject").append(i).append("\"");
        }
        subjects.append("]");
        String body = "{\"title\":\"Test\",\"author\":\"Test\",\"readStatus\":\"WANT_TO_READ\",\"subjects\":" + subjects + "}";
        HttpResponse<String> resp = post("/books", body);
        assertEquals(400, resp.statusCode());
        assertTrue(resp.body().contains("maximum 50 subjects"));
    }

    @Test
    void testCreateBookSubjectTooLong() throws Exception {
        String longSubject = "A".repeat(201);
        String body = "{\"title\":\"Test\",\"author\":\"Test\",\"readStatus\":\"WANT_TO_READ\",\"subjects\":[\"" + longSubject + "\"]}";
        HttpResponse<String> resp = post("/books", body);
        assertEquals(400, resp.statusCode());
        assertTrue(resp.body().contains("each subject must be 200 characters"));
    }

    @Test
    void testCsvExportSanitizesFormulaInjection() throws Exception {
        post("/books", "{\"title\":\"=SUM(A1)\",\"author\":\"Test\",\"readStatus\":\"WANT_TO_READ\"}");
        HttpResponse<String> resp = get("/books/export");
        assertEquals(200, resp.statusCode());
        // The '=' should be prefixed with ' and the whole field quoted
        assertTrue(resp.body().contains("\"'=SUM(A1)\""));
    }

    @Test
    void testCreateBookWhitespaceOnlyTitleRejected() throws Exception {
        HttpResponse<String> resp = post("/books",
            "{\"title\":\"   \",\"author\":\"Test\",\"readStatus\":\"WANT_TO_READ\"}");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void testCreateBookWhitespaceOnlyAuthorRejected() throws Exception {
        HttpResponse<String> resp = post("/books",
            "{\"title\":\"Test\",\"author\":\"   \",\"readStatus\":\"WANT_TO_READ\"}");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void testCreateBookEmptySubjectsArrayAllowed() throws Exception {
        HttpResponse<String> resp = post("/books",
            "{\"title\":\"Test\",\"author\":\"Test\",\"readStatus\":\"WANT_TO_READ\",\"subjects\":[]}");
        assertEquals(201, resp.statusCode());
    }

    @Test
    void testCreateBookUnknownReadStatusRejected() throws Exception {
        HttpResponse<String> resp = post("/books",
            "{\"title\":\"Test\",\"author\":\"Test\",\"readStatus\":\"PAUSED\"}");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void testSortWithEmptyParam() throws Exception {
        post("/books", createBookJson("SortEmpty", "Author", "WANT_TO_READ"));
        HttpResponse<String> resp = get("/books?sort=");
        assertEquals(200, resp.statusCode());
    }

    @Test
    void testSortWithInvalidField() throws Exception {
        post("/books", createBookJson("SortInvalid", "Author", "WANT_TO_READ"));
        HttpResponse<String> resp = get("/books?sort=invalid,asc");
        // Invalid sort field is silently ignored (no comparator applied), returns 200
        assertEquals(200, resp.statusCode());
    }

    @Test
    void testSortCaseInsensitiveDirection() throws Exception {
        post("/books", createBookJson("Beta Sort", "Author", "WANT_TO_READ"));
        post("/books", createBookJson("Alpha Sort", "Author", "WANT_TO_READ"));
        HttpResponse<String> resp = get("/books?sort=title,ASC");
        assertEquals(200, resp.statusCode());
    }

    @Test
    void testPaginationPageZero() throws Exception {
        post("/books", createBookJson("PageZero", "Author", "WANT_TO_READ"));
        HttpResponse<String> resp = get("/books?page=0");
        // page=0 should either return 400 or be treated as page=1
        assertTrue(resp.statusCode() == 200 || resp.statusCode() == 400);
    }

    @Test
    void testPaginationNegativeSize() throws Exception {
        post("/books", createBookJson("NegSize", "Author", "WANT_TO_READ"));
        HttpResponse<String> resp = get("/books?page=1&size=-1");
        assertEquals(200, resp.statusCode());
        // Negative size should be clamped to default (20)
        JsonObject result = JsonParser.parseString(resp.body()).getAsJsonObject();
        assertEquals(20, result.get("size").getAsInt());
    }

    @Test
    void testSizeWithoutPageReturnsRawArray() throws Exception {
        post("/books", createBookJson("NoPage", "Author", "WANT_TO_READ"));
        HttpResponse<String> resp = get("/books?size=50");
        assertEquals(200, resp.statusCode());
        // Without ?page=, response is raw JSON array (not paginated wrapper)
        assertTrue(resp.body().trim().startsWith("["));
    }

    @Test
    void testGetCoverForBookWithNoCover() throws Exception {
        HttpResponse<String> createResp = post("/books",
            createBookJson("No Cover Book", "Author", "WANT_TO_READ"));
        String id = JsonParser.parseString(createResp.body()).getAsJsonObject().get("id").getAsString();
        HttpResponse<String> resp = get("/books/" + id + "/cover");
        assertEquals(404, resp.statusCode());
    }

    @Test
    void testGetCoverForNonExistentBook() throws Exception {
        HttpResponse<String> resp = get("/books/" + java.util.UUID.randomUUID() + "/cover");
        assertEquals(404, resp.statusCode());
    }

    @Test
    void testGetStatsReturnsValidJson() throws Exception {
        HttpResponse<String> resp = get("/books/stats");
        assertEquals(200, resp.statusCode());
        // Should return valid JSON with stats fields
        JsonObject stats = JsonParser.parseString(resp.body()).getAsJsonObject();
        assertTrue(stats.has("totalBooks"));
    }

    @Test
    void testCsvImportEmptyBody() throws Exception {
        // Send empty body — should handle gracefully
        HttpResponse<String> resp = postCsv("/books/import", "");
        assertTrue(resp.statusCode() == 200 || resp.statusCode() == 400);
    }

    @Test
    void testCsvImportHeaderOnly() throws Exception {
        HttpResponse<String> resp = postCsv("/books/import",
            "title,author,readStatus\n");
        assertEquals(200, resp.statusCode());
    }

    @Test
    void testCsvImportWithQuotedCommas() throws Exception {
        HttpResponse<String> resp = postCsv("/books/import",
            "title,author,readStatus\n\"Title, With Comma\",Author,WANT_TO_READ\n");
        assertEquals(200, resp.statusCode());
    }
}
