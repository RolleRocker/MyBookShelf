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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class OpenLibraryService {

    private final BookRepository repository;
    private final ExecutorService executor;
    private final HttpClient httpClient;
    private final String userAgent;

    public OpenLibraryService(BookRepository repository) {
        this.repository = repository;
        this.executor = Executors.newSingleThreadExecutor();
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.userAgent = "MyBookShelf/1.0 (personal project)";
    }

    public void enrichBookAsync(UUID bookId, String isbn) {
        executor.submit(() -> {
            try {
                BookMetadata metadata = fetchMetadata(isbn);
                byte[] coverData = downloadCover(isbn);
                repository.updateFromOpenLibrary(bookId, metadata, coverData);
            } catch (Exception e) {
                System.err.println("Enrichment failed for ISBN " + isbn + ": " + e.getMessage());
            }
        });
    }

    BookMetadata fetchMetadata(String isbn) throws IOException, InterruptedException {
        String url = "https://openlibrary.org/api/books?bibkeys=ISBN:" + isbn + "&jscmd=data&format=json";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", userAgent)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }

        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
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
                if (first.isJsonObject() && first.getAsJsonObject().has("name")) {
                    metadata.setAuthor(first.getAsJsonObject().get("name").getAsString());
                }
            }
        }

        // Publisher — first entry in publishers array
        if (bookData.has("publishers") && bookData.get("publishers").isJsonArray()) {
            JsonArray publishers = bookData.getAsJsonArray("publishers");
            if (!publishers.isEmpty()) {
                JsonElement first = publishers.get(0);
                if (first.isJsonObject() && first.getAsJsonObject().has("name")) {
                    metadata.setPublisher(first.getAsJsonObject().get("name").getAsString());
                }
            }
        }

        // Publish date
        if (bookData.has("publish_date") && !bookData.get("publish_date").isJsonNull()) {
            metadata.setPublishDate(bookData.get("publish_date").getAsString());
        }

        // Page count
        if (bookData.has("number_of_pages") && !bookData.get("number_of_pages").isJsonNull()) {
            metadata.setPageCount(bookData.get("number_of_pages").getAsInt());
        }

        // Subjects — extract name field from each object, limit to 10
        if (bookData.has("subjects") && bookData.get("subjects").isJsonArray()) {
            JsonArray subjectsArray = bookData.getAsJsonArray("subjects");
            List<String> subjects = new ArrayList<>();
            int limit = Math.min(subjectsArray.size(), 10);
            for (int i = 0; i < limit; i++) {
                JsonElement elem = subjectsArray.get(i);
                if (elem.isJsonObject() && elem.getAsJsonObject().has("name")) {
                    subjects.add(elem.getAsJsonObject().get("name").getAsString());
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
                .GET()
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
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
        sorted.sort(Comparator
                .comparing((Book b) -> b.getTitle() != null) // false (null) before true
                .thenComparing(b -> b.getCreatedAt() != null ? b.getCreatedAt() : java.time.Instant.EPOCH));

        int count = sorted.size();
        executor.submit(() -> {
            for (int i = 0; i < sorted.size(); i++) {
                Book book = sorted.get(i);
                try {
                    BookMetadata metadata = fetchMetadata(book.getIsbn());
                    byte[] coverData = downloadCover(book.getIsbn());
                    repository.updateFromOpenLibrary(book.getId(), metadata, coverData);
                    System.out.println("Re-enriched: " + book.getIsbn() + " (" + (i + 1) + "/" + sorted.size() + ")");
                } catch (Exception e) {
                    System.err.println("Re-enrichment failed for ISBN " + book.getIsbn() + ": " + e.getMessage());
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
