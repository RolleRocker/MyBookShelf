package com.bookshelf;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class OpenLibraryService {

    private final BookRepository repository;
    private final Path coversDir;
    private final ExecutorService executor;
    private final HttpClient httpClient;

    public OpenLibraryService(BookRepository repository, Path coversDir) {
        this.repository = repository;
        this.coversDir = coversDir;
        this.executor = Executors.newSingleThreadExecutor();
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public void enrichBookAsync(UUID bookId, String isbn) {
        executor.submit(() -> {
            try {
                BookMetadata metadata = fetchMetadata(isbn);
                String coverPath = downloadCover(bookId, isbn);
                repository.updateFromOpenLibrary(bookId, metadata, coverPath);
            } catch (Exception e) {
                System.err.println("Enrichment failed for ISBN " + isbn + ": " + e.getMessage());
            }
        });
    }

    BookMetadata fetchMetadata(String isbn) throws IOException, InterruptedException {
        String url = "https://openlibrary.org/api/books?bibkeys=ISBN:" + isbn + "&jscmd=data&format=json";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
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

    String downloadCover(UUID bookId, String isbn) throws IOException, InterruptedException {
        String url = "https://covers.openlibrary.org/b/isbn/" + isbn + "-L.jpg";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            return null;
        }

        Path coverFile = coversDir.resolve(bookId + ".jpg");
        try (InputStream body = response.body()) {
            Files.copy(body, coverFile, StandardCopyOption.REPLACE_EXISTING);
        }

        // Check for 1x1 pixel placeholder (< 1KB)
        long fileSize = Files.size(coverFile);
        if (fileSize < 1024) {
            Files.deleteIfExists(coverFile);
            return null;
        }

        return coverFile.toAbsolutePath().toString();
    }

    public void deleteCover(UUID bookId) {
        try {
            Path coverFile = coversDir.resolve(bookId + ".jpg");
            Files.deleteIfExists(coverFile);
        } catch (IOException e) {
            System.err.println("Failed to delete cover for " + bookId + ": " + e.getMessage());
        }
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
