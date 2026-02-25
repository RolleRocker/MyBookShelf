package com.bookshelf;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@TestMethodOrder(MethodOrderer.MethodName.class)
public class OpenLibraryTest {

    private static HttpServer server;
    private static HttpClient client;
    private static InMemoryBookRepository repository;
    private static OpenLibraryService openLibraryService;
    private static int port;

    @BeforeAll
    static void startServer() throws IOException, InterruptedException {
        repository = new InMemoryBookRepository();
        openLibraryService = new OpenLibraryService(repository);
        BookController controller = new BookController(repository, openLibraryService);
        Router router = App.createRouter(controller);
        server = new HttpServer(0, router);
        server.start();
        port = server.getPort();
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        Thread.sleep(100);
    }

    @AfterAll
    static void stopServer() {
        if (openLibraryService != null) openLibraryService.shutdown();
        if (server != null) server.stop();
    }

    @BeforeEach
    void cleanRepository() {
        repository.clear();
    }

    // --- Helpers ---

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder().uri(URI.create(baseUrl() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private HttpResponse<byte[]> getBytes(String path) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder().uri(URI.create(baseUrl() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()
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

    private String getIdFromResponse(HttpResponse<String> response) {
        return JsonParser.parseString(response.body()).getAsJsonObject().get("id").getAsString();
    }

    /**
     * Polls GET /books/{id} until the given field is non-null, up to maxWaitSeconds.
     * Returns the final book JSON, or fails the test if timeout.
     */
    private JsonObject pollUntilFieldPopulated(String bookId, String fieldName, int maxWaitSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + maxWaitSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> resp = get("/books/" + bookId);
            assertEquals(200, resp.statusCode());
            JsonObject book = JsonParser.parseString(resp.body()).getAsJsonObject();
            if (book.has(fieldName) && !book.get(fieldName).isJsonNull()) {
                return book;
            }
            Thread.sleep(2000);
        }
        fail("Timed out waiting for field '" + fieldName + "' to be populated on book " + bookId);
        return null;
    }

    /**
     * Polls GET /books/{id}/cover until it returns 200, up to maxWaitSeconds.
     */
    private void pollUntilCoverAvailable(String bookId, int maxWaitSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + maxWaitSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<byte[]> resp = getBytes("/books/" + bookId + "/cover");
            if (resp.statusCode() == 200) {
                return;
            }
            Thread.sleep(2000);
        }
        fail("Timed out waiting for cover to become available on book " + bookId);
    }

    // --- T25: Enrichment fills in metadata ---
    @Test
    void testT25_enrichmentFillsMetadata() throws Exception {
        String body = "{\"title\":\"Dune\",\"author\":\"Frank Herbert\",\"isbn\":\"9780441013593\",\"readStatus\":\"READING\"}";
        HttpResponse<String> resp = post("/books", body);
        assertEquals(201, resp.statusCode());
        String id = getIdFromResponse(resp);

        JsonObject book = pollUntilFieldPopulated(id, "publisher", 30);
        assertNotNull(book.get("publisher").getAsString());
        assertFalse(book.get("publisher").getAsString().isEmpty());
        assertFalse(book.get("publishDate").isJsonNull());
        assertFalse(book.get("pageCount").isJsonNull());
        assertTrue(book.get("pageCount").getAsInt() > 0);
        assertFalse(book.get("subjects").isJsonNull());
    }

    // --- T26: Cover image is downloaded ---
    @Test
    void testT26_coverImageDownloaded() throws Exception {
        String body = "{\"title\":\"Dune\",\"author\":\"Frank Herbert\",\"isbn\":\"9780441013593\",\"readStatus\":\"READING\"}";
        HttpResponse<String> resp = post("/books", body);
        String id = getIdFromResponse(resp);

        pollUntilCoverAvailable(id, 30);

        HttpResponse<byte[]> coverResp = getBytes("/books/" + id + "/cover");
        assertEquals(200, coverResp.statusCode());
        assertTrue(coverResp.headers().firstValue("Content-Type").orElse("").contains("image/jpeg"));
        assertTrue(coverResp.body().length > 1024, "Cover should be larger than 1KB (not a placeholder)");
    }

    // --- T27: Cover endpoint returns 404 when no cover yet ---
    @Test
    void testT27_coverNotYetAvailable() throws Exception {
        String body = "{\"title\":\"Dune\",\"author\":\"Frank Herbert\",\"isbn\":\"9780441013593\",\"readStatus\":\"READING\"}";
        HttpResponse<String> resp = post("/books", body);
        String id = getIdFromResponse(resp);

        // Immediately request cover before enrichment completes
        HttpResponse<String> coverResp = get("/books/" + id + "/cover");
        assertEquals(404, coverResp.statusCode());
    }

    // --- T28: Book without ISBN gets no enrichment ---
    @Test
    void testT28_noIsbnNoEnrichment() throws Exception {
        String body = "{\"title\":\"My Book\",\"author\":\"Me\",\"readStatus\":\"READING\"}";
        HttpResponse<String> resp = post("/books", body);
        String id = getIdFromResponse(resp);

        Thread.sleep(5000);

        HttpResponse<String> getResp = get("/books/" + id);
        JsonObject book = JsonParser.parseString(getResp.body()).getAsJsonObject();
        assertTrue(book.get("publisher").isJsonNull());
        assertTrue(book.get("pageCount").isJsonNull());
        assertTrue(book.get("subjects").isJsonNull());
        assertTrue(book.get("coverUrl").isJsonNull());
    }

    // --- T29: User-provided fields are not overwritten ---
    @Test
    void testT29_userFieldsPreserved() throws Exception {
        String body = "{\"title\":\"Dune\",\"author\":\"Frank Herbert\",\"isbn\":\"9780441013593\"," +
                "\"publisher\":\"My Custom Publisher\",\"readStatus\":\"READING\"}";
        HttpResponse<String> resp = post("/books", body);
        String id = getIdFromResponse(resp);

        // Wait for enrichment to complete (pageCount will be filled)
        pollUntilFieldPopulated(id, "pageCount", 30);

        HttpResponse<String> getResp = get("/books/" + id);
        JsonObject book = JsonParser.parseString(getResp.body()).getAsJsonObject();
        assertEquals("My Custom Publisher", book.get("publisher").getAsString(),
                "User-provided publisher should not be overwritten by Open Library");
    }

    // --- T30: Book with ISBN that Open Library can't enrich still exists ---
    @Test
    void testT30_bookWithIsbnStillExistsAfterEnrichmentAttempt() throws Exception {
        // Use an ISBN unlikely to exist on Open Library
        String body = "{\"title\":\"Ghost Book\",\"author\":\"Nobody\",\"isbn\":\"9780000000000\",\"readStatus\":\"READING\"}";
        HttpResponse<String> resp = post("/books", body);
        assertEquals(201, resp.statusCode());
        String id = getIdFromResponse(resp);

        // Wait for enrichment attempt to complete (whether it found data or not)
        Thread.sleep(10000);

        // Core assertion: book still exists with user-provided data intact
        HttpResponse<String> getResp = get("/books/" + id);
        assertEquals(200, getResp.statusCode());
        JsonObject book = JsonParser.parseString(getResp.body()).getAsJsonObject();
        assertEquals("Ghost Book", book.get("title").getAsString());
        assertEquals("Nobody", book.get("author").getAsString());
        assertEquals("READING", book.get("readStatus").getAsString());
    }

    // --- T31: Cover deleted when book is deleted ---
    @Test
    void testT31_coverDeletedWithBook() throws Exception {
        String body = "{\"title\":\"Dune\",\"author\":\"Frank Herbert\",\"isbn\":\"9780441013593\",\"readStatus\":\"READING\"}";
        HttpResponse<String> resp = post("/books", body);
        String id = getIdFromResponse(resp);

        pollUntilCoverAvailable(id, 30);

        HttpResponse<String> deleteResp = delete("/books/" + id);
        assertEquals(204, deleteResp.statusCode());

        HttpResponse<String> coverResp = get("/books/" + id + "/cover");
        assertEquals(404, coverResp.statusCode());
    }

    // --- T33: POST /books/re-enrich queues books and returns count ---
    @Test
    void testT33_reEnrichQueuesBooks() throws Exception {
        post("/books", "{\"title\":\"Dune\",\"author\":\"Frank Herbert\",\"isbn\":\"9780441013593\",\"readStatus\":\"READING\"}");
        post("/books", "{\"title\":\"Hobbit\",\"author\":\"Tolkien\",\"isbn\":\"9780261102217\",\"readStatus\":\"WANT_TO_READ\"}");
        post("/books", "{\"title\":\"No ISBN\",\"author\":\"Someone\",\"readStatus\":\"READING\"}"); // no ISBN, not queued

        HttpResponse<String> resp = post("/books/re-enrich", "");
        assertEquals(202, resp.statusCode());
        JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
        assertEquals(2, body.get("queued").getAsInt());
    }

    // --- T34: Changing ISBN clears previously-enriched fields immediately ---
    @Test
    void testT34_isbnChangeClearsEnrichedFields() throws Exception {
        // Create a book with a user-provided publisher
        String createBody = "{\"title\":\"Dune\",\"author\":\"Frank Herbert\",\"isbn\":\"9780441013593\"," +
                "\"publisher\":\"My Publisher\",\"readStatus\":\"READING\"}";
        HttpResponse<String> createResp = post("/books", createBody);
        assertEquals(201, createResp.statusCode());
        String id = getIdFromResponse(createResp);

        // Change ISBN — the handler must clear enriched fields immediately in the PUT response
        HttpResponse<String> updateResp = put("/books/" + id, "{\"isbn\":\"9780261102217\"}");
        assertEquals(200, updateResp.statusCode());

        JsonObject updated = JsonParser.parseString(updateResp.body()).getAsJsonObject();
        assertTrue(updated.get("publisher").isJsonNull(), "publisher should be cleared on ISBN change");
        assertTrue(updated.get("publishDate").isJsonNull(), "publishDate should be cleared on ISBN change");
        assertTrue(updated.get("pageCount").isJsonNull(), "pageCount should be cleared on ISBN change");
        assertTrue(updated.get("subjects").isJsonNull(), "subjects should be cleared on ISBN change");
        assertTrue(updated.get("coverUrl").isJsonNull(), "coverUrl should be cleared on ISBN change");
    }

    // --- T32: Re-enrichment on ISBN change ---
    @Test
    void testT32_reEnrichmentOnIsbnChange() throws Exception {
        // Create with Dune ISBN
        String body = "{\"title\":\"Dune\",\"author\":\"Frank Herbert\",\"isbn\":\"9780441013593\",\"readStatus\":\"READING\"}";
        HttpResponse<String> resp = post("/books", body);
        String id = getIdFromResponse(resp);

        JsonObject duneBook = pollUntilFieldPopulated(id, "publisher", 30);
        String dunePublisher = duneBook.get("publisher").getAsString();

        // Change ISBN to The Hobbit
        String updateBody = "{\"isbn\":\"9780261102217\"}";
        HttpResponse<String> updateResp = put("/books/" + id, updateBody);
        assertEquals(200, updateResp.statusCode());

        // Poll until publisher changes from Dune's publisher
        long deadline = System.currentTimeMillis() + 30_000;
        JsonObject updatedBook = null;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> getResp = get("/books/" + id);
            updatedBook = JsonParser.parseString(getResp.body()).getAsJsonObject();
            if (!updatedBook.get("publisher").isJsonNull()
                    && !updatedBook.get("publisher").getAsString().equals(dunePublisher)) {
                break;
            }
            Thread.sleep(2000);
        }

        assertNotNull(updatedBook);
        assertFalse(updatedBook.get("publisher").isJsonNull(), "Publisher should be re-enriched");
        assertNotEquals(dunePublisher, updatedBook.get("publisher").getAsString(),
                "Publisher should now match the new ISBN, not Dune");
    }
}
