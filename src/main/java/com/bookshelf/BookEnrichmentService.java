package com.bookshelf;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class BookEnrichmentService {

    private final BookRepository repository;
    private final ExecutorService executor;
    private final HttpClient httpClient;
    private final String userAgent;
    private final String googleBooksApiKey;

    public BookEnrichmentService(BookRepository repository) {
        this.repository = repository;
        this.executor = Executors.newSingleThreadExecutor();
        this.httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        this.userAgent = "MyBookShelf/1.0 (personal project)";
        String key = System.getenv("GOOGLE_BOOKS_API_KEY");
        this.googleBooksApiKey = (key != null && !key.isEmpty()) ? key : null;
    }

    public void enrichBookAsync(UUID bookId, String isbn) {
        executor.submit(() -> {
            try {
                BookMetadata metadata = fetchMetadata(isbn);
                byte[] coverData = downloadCover(isbn);

                // Fallback to Google Books if Open Library returned nothing useful
                if (metadata == null || metadata.getTitle() == null) {
                    System.out.println(
                        "Falling back to Google Books for ISBN " + isbn
                    );
                    BookMetadata googleMetadata = fetchGoogleBooksMetadata(
                        isbn
                    );
                    if (googleMetadata != null) {
                        metadata = mergeMetadata(metadata, googleMetadata);
                    }
                }
                if (
                    coverData == null &&
                    metadata != null &&
                    metadata.getCoverUrl() != null
                ) {
                    coverData = downloadImageBytes(metadata.getCoverUrl());
                }

                repository.updateFromOpenLibrary(bookId, metadata, coverData);
            } catch (Exception e) {
                System.err.println(
                    "Enrichment failed for ISBN " + isbn + ": " + e.getMessage()
                );
            }
        });
    }

    BookMetadata fetchMetadata(String isbn)
        throws IOException, InterruptedException {
        String url =
            "https://openlibrary.org/api/books?bibkeys=ISBN:" +
            isbn +
            "&jscmd=data&format=json";
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", userAgent)
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(
            request,
            HttpResponse.BodyHandlers.ofString()
        );
        if (response.statusCode() != 200) {
            return null;
        }

        JsonObject root = JsonParser.parseString(
            response.body()
        ).getAsJsonObject();
        String key = "ISBN:" + isbn;
        if (!root.has(key)) {
            return null;
        }

        JsonObject bookData = root.getAsJsonObject(key);
        BookMetadata metadata = new BookMetadata();

        // Title
        if (bookData.has("title") && !bookData.get("title").isJsonNull()) {
            metadata.setTitle(bookData.get("title").getAsString());
        }

        // Authors — first entry in authors array
        if (bookData.has("authors") && bookData.get("authors").isJsonArray()) {
            JsonArray authors = bookData.getAsJsonArray("authors");
            if (!authors.isEmpty()) {
                JsonElement first = authors.get(0);
                if (
                    first.isJsonObject() && first.getAsJsonObject().has("name")
                ) {
                    metadata.setAuthor(
                        first.getAsJsonObject().get("name").getAsString()
                    );
                }
            }
        }

        // Publisher — first entry in publishers array
        if (
            bookData.has("publishers") &&
            bookData.get("publishers").isJsonArray()
        ) {
            JsonArray publishers = bookData.getAsJsonArray("publishers");
            if (!publishers.isEmpty()) {
                JsonElement first = publishers.get(0);
                if (
                    first.isJsonObject() && first.getAsJsonObject().has("name")
                ) {
                    metadata.setPublisher(
                        first.getAsJsonObject().get("name").getAsString()
                    );
                }
            }
        }

        // Publish date
        if (
            bookData.has("publish_date") &&
            !bookData.get("publish_date").isJsonNull()
        ) {
            metadata.setPublishDate(bookData.get("publish_date").getAsString());
        }

        // Page count
        if (
            bookData.has("number_of_pages") &&
            !bookData.get("number_of_pages").isJsonNull()
        ) {
            metadata.setPageCount(bookData.get("number_of_pages").getAsInt());
        }

        // Subjects — extract name field from each object, limit to 10
        if (
            bookData.has("subjects") && bookData.get("subjects").isJsonArray()
        ) {
            JsonArray subjectsArray = bookData.getAsJsonArray("subjects");
            List<String> subjects = new ArrayList<>();
            int limit = Math.min(subjectsArray.size(), 10);
            for (int i = 0; i < limit; i++) {
                JsonElement elem = subjectsArray.get(i);
                if (elem.isJsonObject() && elem.getAsJsonObject().has("name")) {
                    subjects.add(
                        elem.getAsJsonObject().get("name").getAsString()
                    );
                }
            }
            if (!subjects.isEmpty()) {
                metadata.setSubjects(subjects);
                metadata.setGenre(BookMetadata.deriveGenre(subjects));
            }
        }

        // Cover URL — use large size
        if (bookData.has("cover") && bookData.get("cover").isJsonObject()) {
            JsonObject cover = bookData.getAsJsonObject("cover");
            if (cover.has("large") && !cover.get("large").isJsonNull()) {
                metadata.setCoverUrl(cover.get("large").getAsString());
            }
        }

        return metadata;
    }

    byte[] downloadCover(String isbn) throws IOException, InterruptedException {
        String url = "https://covers.openlibrary.org/b/isbn/" + isbn + "-L.jpg";
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", userAgent)
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();

        HttpResponse<byte[]> response = httpClient.send(
            request,
            HttpResponse.BodyHandlers.ofByteArray()
        );
        if (response.statusCode() != 200) {
            return null;
        }

        byte[] data = response.body();

        // Check for 1x1 pixel placeholder (< 1KB)
        if (data.length < 1024) {
            return null;
        }

        return data;
    }

    public int reEnrichAll(List<Book> books) {
        // Sort: null-title first (stuck books), then by createdAt
        List<Book> sorted = new ArrayList<>(books);
        sorted.sort(
            Comparator.comparing(
                (Book b) -> b.getTitle() != null
            ).thenComparing(
                b -> // false (null) before true
                    b.getCreatedAt() != null
                        ? b.getCreatedAt()
                        : java.time.Instant.EPOCH
            )
        );

        int count = sorted.size();
        executor.submit(() -> {
            for (int i = 0; i < sorted.size(); i++) {
                Book book = sorted.get(i);
                try {
                    BookMetadata metadata = fetchMetadata(book.getIsbn());
                    byte[] coverData = downloadCover(book.getIsbn());

                    // Fallback to Google Books
                    if (metadata == null || metadata.getTitle() == null) {
                        System.out.println(
                            "Falling back to Google Books for ISBN " +
                                book.getIsbn()
                        );
                        BookMetadata googleMetadata = fetchGoogleBooksMetadata(
                            book.getIsbn()
                        );
                        if (googleMetadata != null) {
                            metadata = mergeMetadata(metadata, googleMetadata);
                        }
                        // Brief delay to be polite to Google's API
                        Thread.sleep(1000);
                    }
                    if (
                        coverData == null &&
                        metadata != null &&
                        metadata.getCoverUrl() != null
                    ) {
                        coverData = downloadImageBytes(metadata.getCoverUrl());
                    }

                    repository.updateFromOpenLibrary(
                        book.getId(),
                        metadata,
                        coverData
                    );
                    System.out.println(
                        "Re-enriched: " +
                            book.getIsbn() +
                            " (" +
                            (i + 1) +
                            "/" +
                            sorted.size() +
                            ")"
                    );
                } catch (Exception e) {
                    System.err.println(
                        "Re-enrichment failed for ISBN " +
                            book.getIsbn() +
                            ": " +
                            e.getMessage()
                    );
                }
                // Rate-limit: wait 3 seconds between books (except after last)
                if (i < sorted.size() - 1) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            System.out.println("Re-enrichment complete.");
        });
        return count;
    }

    // ---- Google Books API fallback ----

    BookMetadata fetchGoogleBooksMetadata(String isbn)
        throws IOException, InterruptedException {
        String url =
            "https://www.googleapis.com/books/v1/volumes?q=isbn:" +
            isbn +
            (googleBooksApiKey != null ? "&key=" + googleBooksApiKey : "");
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", userAgent)
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(
            request,
            HttpResponse.BodyHandlers.ofString()
        );
        if (response.statusCode() != 200) {
            System.err.println(
                "Google Books API returned " +
                    response.statusCode() +
                    " for ISBN " +
                    isbn
            );
            return null;
        }
        return parseGoogleBooksResponse(response.body());
    }

    // Package-private for testability
    BookMetadata parseGoogleBooksResponse(String jsonBody) {
        JsonObject root = JsonParser.parseString(jsonBody).getAsJsonObject();
        if (!root.has("items") || !root.get("items").isJsonArray()) {
            return null;
        }
        JsonArray items = root.getAsJsonArray("items");
        if (items.isEmpty()) {
            return null;
        }

        JsonObject volumeInfo = items
            .get(0)
            .getAsJsonObject()
            .getAsJsonObject("volumeInfo");
        if (volumeInfo == null) {
            return null;
        }

        BookMetadata metadata = new BookMetadata();

        if (volumeInfo.has("title") && !volumeInfo.get("title").isJsonNull()) {
            metadata.setTitle(volumeInfo.get("title").getAsString());
        }

        if (
            volumeInfo.has("authors") && volumeInfo.get("authors").isJsonArray()
        ) {
            JsonArray authors = volumeInfo.getAsJsonArray("authors");
            if (!authors.isEmpty()) {
                metadata.setAuthor(authors.get(0).getAsString());
            }
        }

        if (
            volumeInfo.has("publisher") &&
            !volumeInfo.get("publisher").isJsonNull()
        ) {
            metadata.setPublisher(volumeInfo.get("publisher").getAsString());
        }

        if (
            volumeInfo.has("publishedDate") &&
            !volumeInfo.get("publishedDate").isJsonNull()
        ) {
            metadata.setPublishDate(
                volumeInfo.get("publishedDate").getAsString()
            );
        }

        if (
            volumeInfo.has("pageCount") &&
            !volumeInfo.get("pageCount").isJsonNull()
        ) {
            metadata.setPageCount(volumeInfo.get("pageCount").getAsInt());
        }

        if (
            volumeInfo.has("categories") &&
            volumeInfo.get("categories").isJsonArray()
        ) {
            JsonArray categoriesArray = volumeInfo.getAsJsonArray("categories");
            List<String> subjects = new ArrayList<>();
            int limit = Math.min(categoriesArray.size(), 10);
            for (int i = 0; i < limit; i++) {
                JsonElement elem = categoriesArray.get(i);
                if (elem.isJsonPrimitive()) {
                    subjects.add(elem.getAsString());
                }
            }
            if (!subjects.isEmpty()) {
                metadata.setSubjects(subjects);
                metadata.setGenre(BookMetadata.deriveGenre(subjects));
            }
        }

        if (
            volumeInfo.has("imageLinks") &&
            volumeInfo.get("imageLinks").isJsonObject()
        ) {
            JsonObject imageLinks = volumeInfo.getAsJsonObject("imageLinks");
            if (
                imageLinks.has("thumbnail") &&
                !imageLinks.get("thumbnail").isJsonNull()
            ) {
                String coverUrl = imageLinks.get("thumbnail").getAsString();
                // Upgrade to larger image and HTTPS
                coverUrl = coverUrl.replace("zoom=1", "zoom=3");
                coverUrl = coverUrl.replace("http://", "https://");
                metadata.setCoverUrl(coverUrl);
            }
        }

        return metadata;
    }

    byte[] downloadImageBytes(String imageUrl)
        throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(imageUrl))
            .header("User-Agent", userAgent)
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();

        HttpResponse<byte[]> response = httpClient.send(
            request,
            HttpResponse.BodyHandlers.ofByteArray()
        );
        if (response.statusCode() != 200) {
            return null;
        }

        byte[] data = response.body();
        if (data.length < 1024) {
            return null;
        }
        return data;
    }

    // Merge two metadata objects. Primary (Open Library) wins; fallback fills gaps.
    static BookMetadata mergeMetadata(
        BookMetadata primary,
        BookMetadata fallback
    ) {
        if (primary == null) return fallback;
        if (fallback == null) return primary;

        if (primary.getTitle() == null) primary.setTitle(fallback.getTitle());
        if (primary.getAuthor() == null) primary.setAuthor(
            fallback.getAuthor()
        );
        if (primary.getPublisher() == null) primary.setPublisher(
            fallback.getPublisher()
        );
        if (primary.getPublishDate() == null) primary.setPublishDate(
            fallback.getPublishDate()
        );
        if (primary.getPageCount() == null) primary.setPageCount(
            fallback.getPageCount()
        );
        if (primary.getSubjects() == null) primary.setSubjects(
            fallback.getSubjects()
        );
        if (primary.getCoverUrl() == null) primary.setCoverUrl(
            fallback.getCoverUrl()
        );
        if (primary.getGenre() == null) primary.setGenre(fallback.getGenre());

        return primary;
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }
}
