package com.bookshelf;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.MethodName.class)
public class BookApiTest {

    private static HttpServer server;
    private static HttpClient client;
    private static InMemoryBookRepository repository;
    private static int port;
    private static final Gson gson = new GsonBuilder().serializeNulls().create();

    @BeforeAll
    static void startServer() throws IOException, InterruptedException {
        repository = new InMemoryBookRepository();
        BookController controller = new BookController(repository);
        Router router = App.createRouter(controller);
        server = new HttpServer(0, router);
        server.start();
        port = server.getPort();
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        // Give the server a moment to start accepting
        Thread.sleep(100);
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop();
    }

    @BeforeEach
    void cleanRepository() {
        repository.clear();
    }

    // --- Helper methods ---

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder().uri(URI.create(baseUrl() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private HttpResponse<String> post(String path, String body) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl() + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private HttpResponse<String> put(String path, String body) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl() + path))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private HttpResponse<String> delete(String path) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder().uri(URI.create(baseUrl() + path)).DELETE().build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private HttpResponse<String> sendMethod(String method, String path) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl() + path))
                        .method(method, HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private String createBookJson(String title, String author, String readStatus) {
        return createBookJson(title, author, readStatus, null, null, null);
    }

    private String createBookJson(String title, String author, String readStatus,
                                  String genre, Integer rating, String isbn) {
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
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        return json.get("id").getAsString();
    }

    // --- T1: Create and retrieve a book ---
    @Test
    void testT01_createAndRetrieveBook() throws Exception {
        String body = createBookJson("Dune", "Frank Herbert", "READING", "sci-fi", 5, "9780441013593");
        HttpResponse<String> createResp = post("/books", body);
        assertEquals(201, createResp.statusCode());

        JsonObject created = JsonParser.parseString(createResp.body()).getAsJsonObject();
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

        JsonObject fetched = JsonParser.parseString(getResp.body()).getAsJsonObject();
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
        HttpResponse<String> createResp = post("/books", createBookJson("Dune", "Frank Herbert", "WANT_TO_READ"));
        assertEquals(201, createResp.statusCode());
        String id = getIdFromResponse(createResp);

        String updateBody = "{\"readStatus\":\"FINISHED\",\"rating\":5}";
        HttpResponse<String> updateResp = put("/books/" + id, updateBody);
        assertEquals(200, updateResp.statusCode());

        HttpResponse<String> getResp = get("/books/" + id);
        JsonObject book = JsonParser.parseString(getResp.body()).getAsJsonObject();
        assertEquals("Dune", book.get("title").getAsString());
        assertEquals("FINISHED", book.get("readStatus").getAsString());
        assertEquals(5, book.get("rating").getAsInt());
    }

    // --- T4: Delete a book ---
    @Test
    void testT04_deleteBook() throws Exception {
        HttpResponse<String> createResp = post("/books", createBookJson("Dune", "Frank Herbert", "READING"));
        String id = getIdFromResponse(createResp);

        HttpResponse<String> deleteResp = delete("/books/" + id);
        assertEquals(204, deleteResp.statusCode());

        HttpResponse<String> getResp = get("/books/" + id);
        assertEquals(404, getResp.statusCode());

        HttpResponse<String> listResp = get("/books");
        JsonArray books = JsonParser.parseString(listResp.body()).getAsJsonArray();
        assertEquals(0, books.size());
    }

    // --- T5: Look up by ISBN ---
    @Test
    void testT05_lookUpByIsbn() throws Exception {
        String body = createBookJson("Dune", "Frank Herbert", "READING", null, null, "9780441013593");
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
        String body = "{\"title\":\"Something\",\"author\":\"Someone\",\"readStatus\":\"READING\",\"rating\":0}";
        HttpResponse<String> resp = post("/books", body);
        assertEquals(400, resp.statusCode());
    }

    // --- T12: Invalid rating (too high) ---
    @Test
    void testT12_ratingTooHigh() throws Exception {
        String body = "{\"title\":\"Something\",\"author\":\"Someone\",\"readStatus\":\"READING\",\"rating\":6}";
        HttpResponse<String> resp = post("/books", body);
        assertEquals(400, resp.statusCode());
    }

    // --- T13: Invalid ISBN format ---
    @Test
    void testT13_invalidIsbnFormat() throws Exception {
        String body = "{\"title\":\"Something\",\"author\":\"Someone\",\"readStatus\":\"READING\",\"isbn\":\"123\"}";
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
        HttpResponse<String> resp = get("/books/00000000-0000-0000-0000-000000000000");
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
        String body = "{\"title\":\"Minimal\",\"author\":\"Author\",\"readStatus\":\"READING\"}";
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
        HttpResponse<String> createResp = post("/books", createBookJson("Dune", "Frank Herbert", "READING"));
        String id = getIdFromResponse(createResp);

        String updateBody = "{\"title\":\"Dune\",\"author\":\"Frank Herbert\",\"readStatus\":\"READING\"}";
        HttpResponse<String> updateResp = put("/books/" + id, updateBody);
        assertEquals(200, updateResp.statusCode());
    }

    // --- T20: Delete already deleted book ---
    @Test
    void testT20_deleteAlreadyDeleted() throws Exception {
        HttpResponse<String> createResp = post("/books", createBookJson("Dune", "Frank Herbert", "READING"));
        String id = getIdFromResponse(createResp);

        delete("/books/" + id);
        HttpResponse<String> resp = delete("/books/" + id);
        assertEquals(404, resp.statusCode());
    }

    // --- T21: Concurrent creates ---
    @Test
    void testT21_concurrentCreates() throws Exception {
        List<CompletableFuture<HttpResponse<String>>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final int idx = i;
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return post("/books", createBookJson("Book " + idx, "Author " + idx, "READING"));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        for (var future : futures) {
            HttpResponse<String> resp = future.get();
            assertEquals(201, resp.statusCode());
        }

        HttpResponse<String> listResp = get("/books");
        JsonArray books = JsonParser.parseString(listResp.body()).getAsJsonArray();
        assertEquals(10, books.size());
    }

    // --- T22: Partial update clears field with explicit null ---
    @Test
    void testT22_partialUpdateClearsField() throws Exception {
        String body = createBookJson("Dune", "Frank Herbert", "READING", "sci-fi", null, null);
        HttpResponse<String> createResp = post("/books", body);
        String id = getIdFromResponse(createResp);

        HttpResponse<String> updateResp = put("/books/" + id, "{\"genre\":null}");
        assertEquals(200, updateResp.statusCode());

        HttpResponse<String> getResp = get("/books/" + id);
        JsonObject book = JsonParser.parseString(getResp.body()).getAsJsonObject();
        assertTrue(book.get("genre").isJsonNull());
    }

    // --- T23: ISBN-10 with X check digit is accepted ---
    @Test
    void testT23_isbn10WithX() throws Exception {
        String body = createBookJson("Book", "Author", "READING", null, null, "080442957X");
        HttpResponse<String> resp = post("/books", body);
        assertEquals(201, resp.statusCode());

        HttpResponse<String> getResp = get("/books/isbn/080442957X");
        assertEquals(200, getResp.statusCode());
    }

    // --- T24: Duplicate ISBNs are allowed ---
    @Test
    void testT24_duplicateIsbnsAllowed() throws Exception {
        String body1 = createBookJson("Dune 1", "Frank Herbert", "READING", null, null, "9780441013593");
        String body2 = createBookJson("Dune 2", "Frank Herbert", "FINISHED", null, null, "9780441013593");

        HttpResponse<String> resp1 = post("/books", body1);
        HttpResponse<String> resp2 = post("/books", body2);
        assertEquals(201, resp1.statusCode());
        assertEquals(201, resp2.statusCode());

        HttpResponse<String> listResp = get("/books");
        JsonArray books = JsonParser.parseString(listResp.body()).getAsJsonArray();
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
        post("/books", createBookJson("Dune", "Frank Herbert", "READING", "sci-fi", null, null));
        post("/books", createBookJson("Foundation", "Asimov", "FINISHED", "sci-fi", null, null));
        post("/books", createBookJson("1984", "Orwell", "READING", "dystopia", null, null));

        HttpResponse<String> resp = get("/books?genre=sci-fi&readStatus=READING");
        assertEquals(200, resp.statusCode());
        JsonArray books = JsonParser.parseString(resp.body()).getAsJsonArray();
        assertEquals(1, books.size());
        assertEquals("Dune", books.get(0).getAsJsonObject().get("title").getAsString());
    }

    // --- T47: ISBN-only POST (no title/author) returns 201 ---
    @Test
    void testT47_isbnOnlyPost() throws Exception {
        String body = "{\"isbn\":\"9780441013593\",\"readStatus\":\"WANT_TO_READ\"}";
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
        post("/books", createBookJson("Dune", "Frank Herbert", "READING", "sci-fi", null, null));
        post("/books", createBookJson("Foundation", "Isaac Asimov", "FINISHED", "Sci-Fi", null, null));
        post("/books", createBookJson("1984", "George Orwell", "WANT_TO_READ", "dystopia", null, null));

        HttpResponse<String> resp = get("/books?genre=sci-fi");
        assertEquals(200, resp.statusCode());

        JsonArray books = JsonParser.parseString(resp.body()).getAsJsonArray();
        assertEquals(2, books.size());
    }

    // --- T7: Genre filter with no matches ---
    @Test
    void testT07_genreFilterNoMatches() throws Exception {
        post("/books", createBookJson("Dune", "Frank Herbert", "READING", "sci-fi", null, null));

        HttpResponse<String> resp = get("/books?genre=romance");
        assertEquals(200, resp.statusCode());

        JsonArray books = JsonParser.parseString(resp.body()).getAsJsonArray();
        assertEquals(0, books.size());
    }

    // --- Search tests ---

    @Test
    void testSearchByTitle() throws Exception {
        post("/books", createBookJson("Dune", "Frank Herbert", "WANT_TO_READ"));
        post("/books", createBookJson("Neuromancer", "William Gibson", "READING"));

        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder().uri(URI.create(baseUrl() + "/books?search=dune")).GET().build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        JsonArray books = JsonParser.parseString(resp.body()).getAsJsonArray();
        assertEquals(1, books.size());
        assertEquals("Dune", books.get(0).getAsJsonObject().get("title").getAsString());
    }

    @Test
    void testSearchByAuthor() throws Exception {
        post("/books", createBookJson("Dune", "Frank Herbert", "WANT_TO_READ"));
        post("/books", createBookJson("Neuromancer", "William Gibson", "READING"));

        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder().uri(URI.create(baseUrl() + "/books?search=gibson")).GET().build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        JsonArray books = JsonParser.parseString(resp.body()).getAsJsonArray();
        assertEquals(1, books.size());
        assertEquals("Neuromancer", books.get(0).getAsJsonObject().get("title").getAsString());
    }

    @Test
    void testSortByTitleAsc() throws Exception {
        post("/books", createBookJson("Zorro", "Johnston McCulley", "WANT_TO_READ"));
        post("/books", createBookJson("Dune", "Frank Herbert", "WANT_TO_READ"));

        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder().uri(URI.create(baseUrl() + "/books?sort=title,asc")).GET().build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        JsonArray books = JsonParser.parseString(resp.body()).getAsJsonArray();
        assertEquals(2, books.size());
        assertEquals("Dune", books.get(0).getAsJsonObject().get("title").getAsString());
        assertEquals("Zorro", books.get(1).getAsJsonObject().get("title").getAsString());
    }

    @Test
    void testSortByTitleDesc() throws Exception {
        post("/books", createBookJson("Zorro", "Johnston McCulley", "WANT_TO_READ"));
        post("/books", createBookJson("Dune", "Frank Herbert", "WANT_TO_READ"));

        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder().uri(URI.create(baseUrl() + "/books?sort=title,desc")).GET().build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        JsonArray books = JsonParser.parseString(resp.body()).getAsJsonArray();
        assertEquals(2, books.size());
        assertEquals("Zorro", books.get(0).getAsJsonObject().get("title").getAsString());
        assertEquals("Dune", books.get(1).getAsJsonObject().get("title").getAsString());
    }

    // --- readingProgress tests ---

    @Test
    void testReadingProgressSetOnCreate() throws Exception {
        String body = "{\"title\":\"Dune\",\"author\":\"Frank Herbert\",\"readStatus\":\"READING\",\"readingProgress\":42}";
        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder().uri(URI.create(baseUrl() + "/books"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(201, resp.statusCode());
        JsonObject book = JsonParser.parseString(resp.body()).getAsJsonObject();
        assertEquals(42, book.get("readingProgress").getAsInt());
    }

    @Test
    void testReadingProgressUpdated() throws Exception {
        String createBody = "{\"title\":\"Dune\",\"author\":\"Frank Herbert\",\"readStatus\":\"READING\"}";
        HttpResponse<String> createResp = client.send(
            HttpRequest.newBuilder().uri(URI.create(baseUrl() + "/books"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(createBody))
                .build(),
            HttpResponse.BodyHandlers.ofString());

        String id = JsonParser.parseString(createResp.body()).getAsJsonObject().get("id").getAsString();

        String updateBody = "{\"readingProgress\":75}";
        HttpResponse<String> updateResp = client.send(
            HttpRequest.newBuilder().uri(URI.create(baseUrl() + "/books/" + id))
                .header("Content-Type", "application/json")
                .method("PUT", HttpRequest.BodyPublishers.ofString(updateBody))
                .build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(200, updateResp.statusCode());
        JsonObject updated = JsonParser.parseString(updateResp.body()).getAsJsonObject();
        assertEquals(75, updated.get("readingProgress").getAsInt());
    }

    @Test
    void testReadingProgressValidation() throws Exception {
        String body = "{\"title\":\"Dune\",\"author\":\"Frank Herbert\",\"readStatus\":\"READING\",\"readingProgress\":150}";
        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder().uri(URI.create(baseUrl() + "/books"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(400, resp.statusCode());
    }

    @Test
    void testReadingProgressClearedByNull() throws Exception {
        String createBody = "{\"title\":\"Dune\",\"author\":\"Frank Herbert\",\"readStatus\":\"READING\",\"readingProgress\":42}";
        HttpResponse<String> createResp = client.send(
            HttpRequest.newBuilder().uri(URI.create(baseUrl() + "/books"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(createBody))
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(201, createResp.statusCode());
        String id = JsonParser.parseString(createResp.body()).getAsJsonObject().get("id").getAsString();

        HttpResponse<String> updateResp = client.send(
            HttpRequest.newBuilder().uri(URI.create(baseUrl() + "/books/" + id))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"readingProgress\":null}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, updateResp.statusCode());
        assertTrue(JsonParser.parseString(updateResp.body()).getAsJsonObject().get("readingProgress").isJsonNull());
    }

    @ParameterizedTest
    @ValueSource(strings = {"rating", "pageCount", "readingProgress"})
    void testInvalidIntegerFieldReturns400(String field) throws Exception {
        String body = String.format(
                "{\"title\":\"Dune\",\"author\":\"Frank Herbert\",\"readStatus\":\"READING\",\"%s\":\"not-a-number\"}", field);
        HttpResponse<String> resp = post("/books", body);
        assertEquals(400, resp.statusCode());
    }

    @Test
    void testReadingProgressZeroIsAccepted() throws Exception {
        HttpResponse<String> resp = post("/books",
                "{\"title\":\"Dune\",\"author\":\"Frank Herbert\",\"readStatus\":\"READING\",\"readingProgress\":0}");
        assertEquals(201, resp.statusCode());
        JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
        assertEquals(0, body.get("readingProgress").getAsInt());
    }

    @Test
    void testSearchLiteralUnderscore() throws Exception {
        post("/books", "{\"title\":\"Test_Book\",\"author\":\"Author A\",\"readStatus\":\"WANT_TO_READ\"}");
        post("/books", "{\"title\":\"TestXBook\",\"author\":\"Author B\",\"readStatus\":\"WANT_TO_READ\"}");

        HttpResponse<String> resp = get("/books?search=Test_Book");
        assertEquals(200, resp.statusCode());
        JsonArray results = JsonParser.parseString(resp.body()).getAsJsonArray();
        assertEquals(1, results.size());
        assertEquals("Test_Book", results.get(0).getAsJsonObject().get("title").getAsString());
    }


    @Test
    void testInvalidReadStatusOnCreate() throws Exception {
        String body = "{\"title\":\"Something\",\"author\":\"Someone\",\"readStatus\":\"DID_NOT_FINISH\"}";
        HttpResponse<String> resp = post("/books", body);
        assertEquals(400, resp.statusCode());
    }

    @Test
    void testInvalidReadStatusOnUpdate() throws Exception {
        HttpResponse<String> create = post("/books", createBookJson("Dune", "Frank Herbert", "READING"));
        assertEquals(201, create.statusCode());
        String id = gson.fromJson(create.body(), JsonObject.class).get("id").getAsString();

        HttpResponse<String> update = put("/books/" + id, "{\"readStatus\":\"DID_NOT_FINISH\"}");
        assertEquals(400, update.statusCode());
    }

    @Test
    void testCreateBookWithDnfStatus() throws Exception {
        String body = createBookJson("Flowers for Algernon", "Daniel Keyes", "DNF");
        HttpResponse<String> resp = post("/books", body);
        assertEquals(201, resp.statusCode());
        JsonObject book = gson.fromJson(resp.body(), JsonObject.class);
        assertEquals("DNF", book.get("readStatus").getAsString());
    }

    @Test
    void testUpdateBookToDnfStatus() throws Exception {
        HttpResponse<String> create = post("/books", createBookJson("Dune", "Frank Herbert", "READING"));
        String id = gson.fromJson(create.body(), JsonObject.class).get("id").getAsString();

        HttpResponse<String> update = put("/books/" + id, "{\"readStatus\":\"DNF\"}");
        assertEquals(200, update.statusCode());
        JsonObject book = gson.fromJson(update.body(), JsonObject.class);
        assertEquals("DNF", book.get("readStatus").getAsString());
    }
}
