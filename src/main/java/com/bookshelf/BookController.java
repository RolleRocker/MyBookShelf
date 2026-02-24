package com.bookshelf;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import com.google.gson.JsonSyntaxException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public class BookController {

    private final BookRepository repository;
    private final OpenLibraryService openLibraryService;
    private final Gson gson;

    public BookController(BookRepository repository) {
        this(repository, null);
    }

    public BookController(BookRepository repository, OpenLibraryService openLibraryService) {
        this.repository = repository;
        this.openLibraryService = openLibraryService;
        this.gson = new GsonBuilder()
                .serializeNulls()
                .registerTypeAdapter(Instant.class,
                        (JsonSerializer<Instant>) (src, type, ctx) -> new JsonPrimitive(src.toString()))
                .create();
    }

    public HttpResponse handleGetBooks(HttpRequest request) {
        try {
            String genre = request.getQueryParams().get("genre");
            String readStatusParam = request.getQueryParams().get("readStatus");
            String search = request.getQueryParams().get("search");

            ReadStatus readStatus = null;
            if (readStatusParam != null && !readStatusParam.isEmpty()) {
                try {
                    readStatus = ReadStatus.valueOf(readStatusParam);
                } catch (IllegalArgumentException e) {
                    return HttpResponse.badRequest("Invalid readStatus value");
                }
            }

            List<Book> books;
            if (search != null && !search.isBlank()) {
                books = repository.findBySearch(search.trim());
            } else if (genre != null && !genre.isBlank()) {
                books = repository.findByGenre(genre);
            } else if (readStatus != null) {
                books = repository.findByReadStatus(readStatus);
            } else {
                books = repository.findAll();
            }

            // Apply readStatus filter on top of search or genre result
            if (readStatus != null && (search != null && !search.isBlank() || (genre != null && !genre.isBlank()))) {
                ReadStatus finalReadStatus = readStatus;
                books = books.stream().filter(b -> b.getReadStatus() == finalReadStatus).toList();
            }

            String sortParam = request.getQueryParams().get("sort"); // e.g. "title,asc"
            if (sortParam != null && !sortParam.isBlank()) {
                String[] parts = sortParam.split(",", 2);
                String field = parts[0].trim();
                boolean desc = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim());

                Comparator<Book> comparator = switch (field) {
                    case "title"   -> Comparator.comparing(b -> b.getTitle() != null ? b.getTitle().toLowerCase() : "");
                    case "author"  -> Comparator.comparing(b -> b.getAuthor() != null ? b.getAuthor().toLowerCase() : "");
                    case "rating"  -> Comparator.comparing(b -> b.getRating() != null ? b.getRating() : 0);
                    case "created" -> Comparator.comparing(b -> b.getCreatedAt() != null ? b.getCreatedAt() : Instant.EPOCH);
                    default        -> null;
                };

                if (comparator != null) {
                    if (desc) comparator = comparator.reversed();
                    books = books.stream().sorted(comparator).toList();
                }
            }

            return HttpResponse.ok(gson.toJson(books));
        } catch (RuntimeException e) {
            return HttpResponse.internalServerError("Internal server error");
        }
    }

    public HttpResponse handleGetBook(HttpRequest request) {
        try {
            UUID id;
            try {
                id = UUID.fromString(request.getPathParams().get("id"));
            } catch (IllegalArgumentException e) {
                return HttpResponse.notFound("Book not found");
            }

            return repository.findById(id)
                    .map(book -> HttpResponse.ok(gson.toJson(book)))
                    .orElse(HttpResponse.notFound("Book not found"));
        } catch (RuntimeException e) {
            return HttpResponse.internalServerError("Internal server error");
        }
    }

    public HttpResponse handleGetBookByIsbn(HttpRequest request) {
        try {
            String isbn = request.getPathParams().get("isbn");
            return repository.findByIsbn(isbn)
                    .map(book -> HttpResponse.ok(gson.toJson(book)))
                    .orElse(HttpResponse.notFound("Book not found"));
        } catch (RuntimeException e) {
            return HttpResponse.internalServerError("Internal server error");
        }
    }

    public HttpResponse handleCreateBook(HttpRequest request) {
        JsonObject json;
        try {
            JsonElement parsed = JsonParser.parseString(request.getBody());
            if (!parsed.isJsonObject()) {
                return HttpResponse.badRequest("Invalid JSON");
            }
            json = parsed.getAsJsonObject();
        } catch (JsonSyntaxException | NullPointerException e) {
            return HttpResponse.badRequest("Invalid JSON");
        }

        // Validate required fields
        String title = getStringField(json, "title");
        String author = getStringField(json, "author");
        String isbn = getStringField(json, "isbn");
        boolean hasIsbn = isbn != null && !isbn.isEmpty();

        if (!hasIsbn && (title == null || title.isEmpty())) {
            return HttpResponse.badRequest("title is required");
        }

        if (!hasIsbn && (author == null || author.isEmpty())) {
            return HttpResponse.badRequest("author is required");
        }

        if (!json.has("readStatus") || json.get("readStatus").isJsonNull()) {
            return HttpResponse.badRequest("readStatus is required");
        }

        ReadStatus readStatus;
        try {
            readStatus = ReadStatus.valueOf(json.get("readStatus").getAsString());
        } catch (IllegalArgumentException e) {
            return HttpResponse.badRequest("Invalid readStatus");
        }

        // Validate rating if provided
        if (json.has("rating") && !json.get("rating").isJsonNull()) {
            try {
                int rating = json.get("rating").getAsInt();
                if (rating < 1 || rating > 5) {
                    return HttpResponse.badRequest("Rating must be between 1 and 5");
                }
            } catch (NumberFormatException | UnsupportedOperationException e) {
                return HttpResponse.badRequest("'rating' must be a valid integer");
            }
        }

        // Validate ISBN if provided
        if (hasIsbn && !isValidIsbn(isbn)) {
            return HttpResponse.badRequest("Invalid ISBN format");
        }

        try {
            // Build the book
            Book book = new Book();
            book.setId(UUID.randomUUID());
            book.setTitle(title);
            book.setAuthor(author);
            book.setReadStatus(readStatus);
            book.setGenre(getStringField(json, "genre"));
            book.setIsbn(hasIsbn ? isbn : null);
            book.setPublisher(getStringField(json, "publisher"));
            book.setPublishDate(getStringField(json, "publishDate"));

            if (json.has("rating") && !json.get("rating").isJsonNull()) {
                book.setRating(safeGetInt(json, "rating"));
            } else {
                book.setRating(0);
            }

            if (json.has("pageCount") && !json.get("pageCount").isJsonNull()) {
                book.setPageCount(safeGetInt(json, "pageCount"));
            }

            if (json.has("readingProgress") && !json.get("readingProgress").isJsonNull()) {
                try {
                    int progress = json.get("readingProgress").getAsInt();
                    if (progress < 0 || progress > 100) {
                        return HttpResponse.badRequest("readingProgress must be between 0 and 100");
                    }
                    book.setReadingProgress(progress);
                } catch (NumberFormatException | UnsupportedOperationException e) {
                    return HttpResponse.badRequest("'readingProgress' must be a valid integer");
                }
            }

            Instant now = Instant.now();
            book.setCreatedAt(now);
            book.setUpdatedAt(now);

            repository.save(book);

            // Fire async enrichment if ISBN is present
            if (book.getIsbn() != null && openLibraryService != null) {
                openLibraryService.enrichBookAsync(book.getId(), book.getIsbn());
            }

            return HttpResponse.created(gson.toJson(book));
        } catch (IllegalArgumentException e) {
            return HttpResponse.badRequest(e.getMessage());
        } catch (RuntimeException e) {
            return HttpResponse.internalServerError("Internal server error");
        }
    }

    public HttpResponse handleUpdateBook(HttpRequest request) {
        UUID id;
        try {
            id = UUID.fromString(request.getPathParams().get("id"));
        } catch (IllegalArgumentException e) {
            return HttpResponse.notFound("Book not found");
        }

        var existing = repository.findById(id);
        if (existing.isEmpty()) {
            return HttpResponse.notFound("Book not found");
        }

        Book book = existing.get();

        JsonObject json;
        try {
            JsonElement parsed = JsonParser.parseString(request.getBody());
            if (!parsed.isJsonObject()) {
                return HttpResponse.badRequest("Invalid JSON");
            }
            json = parsed.getAsJsonObject();
        } catch (JsonSyntaxException | NullPointerException e) {
            return HttpResponse.badRequest("Invalid JSON");
        }

        // Validate rating if provided
        if (json.has("rating") && !json.get("rating").isJsonNull()) {
            try {
                int rating = json.get("rating").getAsInt();
                if (rating < 1 || rating > 5) {
                    return HttpResponse.badRequest("Rating must be between 1 and 5");
                }
            } catch (NumberFormatException | UnsupportedOperationException e) {
                return HttpResponse.badRequest("'rating' must be a valid integer");
            }
        }

        // Validate ISBN if provided
        if (json.has("isbn") && !json.get("isbn").isJsonNull()) {
            String isbn = json.get("isbn").getAsString();
            if (!isValidIsbn(isbn)) {
                return HttpResponse.badRequest("Invalid ISBN format");
            }
        }

        // Validate readStatus if provided
        if (json.has("readStatus") && !json.get("readStatus").isJsonNull()) {
            try {
                ReadStatus.valueOf(json.get("readStatus").getAsString());
            } catch (IllegalArgumentException e) {
                return HttpResponse.badRequest("Invalid readStatus");
            }
        }

        try {
            // Save old ISBN before applying updates (book is same object as existing.get())
            String oldIsbn = book.getIsbn();

            // Apply partial updates — only fields present in the JSON
            if (json.has("title")) {
                book.setTitle(json.get("title").isJsonNull() ? null : json.get("title").getAsString());
            }
            if (json.has("author")) {
                book.setAuthor(json.get("author").isJsonNull() ? null : json.get("author").getAsString());
            }
            if (json.has("genre")) {
                book.setGenre(json.get("genre").isJsonNull() ? null : json.get("genre").getAsString());
            }
            if (json.has("isbn")) {
                book.setIsbn(json.get("isbn").isJsonNull() ? null : json.get("isbn").getAsString());
            }
            if (json.has("publisher")) {
                book.setPublisher(json.get("publisher").isJsonNull() ? null : json.get("publisher").getAsString());
            }
            if (json.has("publishDate")) {
                book.setPublishDate(json.get("publishDate").isJsonNull() ? null : json.get("publishDate").getAsString());
            }
            if (json.has("rating")) {
                book.setRating(json.get("rating").isJsonNull() ? null : safeGetInt(json, "rating"));
            }
            if (json.has("pageCount")) {
                book.setPageCount(json.get("pageCount").isJsonNull() ? null : safeGetInt(json, "pageCount"));
            }
            if (json.has("readStatus")) {
                book.setReadStatus(json.get("readStatus").isJsonNull() ? null : ReadStatus.valueOf(json.get("readStatus").getAsString()));
            }
            if (json.has("subjects")) {
                if (json.get("subjects").isJsonNull()) {
                    book.setSubjects(null);
                } else {
                    List<String> subjects = new ArrayList<>();
                    try {
                        json.get("subjects").getAsJsonArray().forEach(e -> subjects.add(e.getAsString()));
                    } catch (IllegalStateException | UnsupportedOperationException e) {
                        return HttpResponse.badRequest("'subjects' must be an array of strings");
                    }
                    book.setSubjects(subjects);
                }
            }
            if (json.has("readingProgress")) {
                if (json.get("readingProgress").isJsonNull()) {
                    book.setReadingProgress(null);
                } else {
                    try {
                        int progress = json.get("readingProgress").getAsInt();
                        if (progress < 0 || progress > 100) {
                            return HttpResponse.badRequest("readingProgress must be between 0 and 100");
                        }
                        book.setReadingProgress(progress);
                    } catch (NumberFormatException | UnsupportedOperationException e) {
                        return HttpResponse.badRequest("'readingProgress' must be a valid integer");
                    }
                }
            }

            // Check if ISBN changed — trigger re-enrichment
            String newIsbn = book.getIsbn();
            boolean isbnChanged = openLibraryService != null && json.has("isbn")
                    && ((newIsbn == null && oldIsbn != null) || (newIsbn != null && !newIsbn.equals(oldIsbn)));

            if (isbnChanged && newIsbn != null) {
                // Clear previously-enriched fields before re-enrichment
                book.setPublisher(null);
                book.setPublishDate(null);
                book.setPageCount(null);
                book.setSubjects(null);
                book.setCoverUrl(null);
                book.setCoverData(null);
            }

            book.setUpdatedAt(Instant.now());
            repository.update(id, book);

            if (isbnChanged && newIsbn != null) {
                openLibraryService.enrichBookAsync(id, newIsbn);
            }

            return HttpResponse.ok(gson.toJson(book));
        } catch (IllegalArgumentException e) {
            return HttpResponse.badRequest(e.getMessage());
        } catch (RuntimeException e) {
            return HttpResponse.internalServerError("Internal server error");
        }
    }

    public HttpResponse handleDeleteBook(HttpRequest request) {
        try {
            UUID id;
            try {
                id = UUID.fromString(request.getPathParams().get("id"));
            } catch (IllegalArgumentException e) {
                return HttpResponse.notFound("Book not found");
            }

            if (repository.delete(id)) {
                return HttpResponse.noContent();
            }
            return HttpResponse.notFound("Book not found");
        } catch (RuntimeException e) {
            return HttpResponse.internalServerError("Internal server error");
        }
    }

    public HttpResponse handleReEnrich(HttpRequest request) {
        try {
            List<Book> booksWithIsbn = repository.findAll().stream()
                    .filter(b -> b.getIsbn() != null && !b.getIsbn().isEmpty())
                    .toList();

            if (booksWithIsbn.isEmpty()) {
                return HttpResponse.accepted("{\"queued\":0}");
            }

            if (openLibraryService == null) {
                return HttpResponse.internalServerError("Open Library service not available");
            }

            int queued = openLibraryService.reEnrichAll(booksWithIsbn);
            return HttpResponse.accepted("{\"queued\":" + queued + "}");
        } catch (RuntimeException e) {
            return HttpResponse.internalServerError("Internal server error");
        }
    }

    public HttpResponse handleGetCover(HttpRequest request) {
        try {
            UUID id;
            try {
                id = UUID.fromString(request.getPathParams().get("id"));
            } catch (IllegalArgumentException e) {
                return HttpResponse.notFound("Book not found");
            }

            var bookOpt = repository.findById(id);
            if (bookOpt.isEmpty() || bookOpt.get().getCoverData() == null) {
                return HttpResponse.notFound("Cover not available");
            }

            return HttpResponse.binary(bookOpt.get().getCoverData(), "image/jpeg");
        } catch (RuntimeException e) {
            return HttpResponse.internalServerError("Internal server error");
        }
    }

    private String getStringField(JsonObject json, String field) {
        if (!json.has(field) || json.get(field).isJsonNull()) {
            return null;
        }
        return json.get(field).getAsString();
    }

    private Integer safeGetInt(JsonObject json, String field) {
        if (!json.has(field) || json.get(field).isJsonNull()) return null;
        try {
            return json.get(field).getAsInt();
        } catch (NumberFormatException | UnsupportedOperationException e) {
            throw new IllegalArgumentException("'" + field + "' must be a valid integer");
        }
    }

    private boolean isValidIsbn(String isbn) {
        if (isbn == null) return true;
        if (isbn.length() == 13) return isbn.matches("\\d{13}");
        if (isbn.length() == 10) return isbn.matches("\\d{9}[\\dX]");
        return false;
    }
}
