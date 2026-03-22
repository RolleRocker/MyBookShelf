package com.bookshelf.adapter.in.http;

import com.bookshelf.adapter.out.enrichment.BookEnrichmentService;
import com.bookshelf.domain.model.Book;
import com.bookshelf.domain.model.BookMetadata;
import com.bookshelf.domain.model.ReadStatus;
import com.bookshelf.domain.model.Shelf;
import com.bookshelf.domain.port.out.BookRepository;
import com.bookshelf.domain.port.out.ShelfRepository;
import com.bookshelf.framework.http.HttpRequest;
import com.bookshelf.framework.http.HttpResponse;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

public class BookController {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(BookController.class);

    private final BookRepository repository;
    private final BookEnrichmentService openLibraryService;
    private final ShelfRepository shelfRepository;
    private final Gson gson;

    public BookController(BookRepository repository) {
        this(repository, null, null);
    }

    public BookController(
        BookRepository repository,
        BookEnrichmentService openLibraryService
    ) {
        this(repository, openLibraryService, null);
    }

    public BookController(
        BookRepository repository,
        BookEnrichmentService openLibraryService,
        ShelfRepository shelfRepository
    ) {
        this.repository = repository;
        this.openLibraryService = openLibraryService;
        this.shelfRepository = shelfRepository;
        this.gson = GsonFactory.create();
    }

    public HttpResponse handleGetBooks(HttpRequest request) {
        try {
            String genre = request.getQueryParams().get("genre");
            String readStatusParam = request.getQueryParams().get("readStatus");
            String search = request.getQueryParams().get("search");
            String subject = request.getQueryParams().get("subject");

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
            } else if (subject != null && !subject.isBlank()) {
                books = repository.findBySubject(subject.trim());
            } else if (genre != null && !genre.isBlank()) {
                books = repository.findByGenre(genre);
            } else if (readStatus != null) {
                books = repository.findByReadStatus(readStatus);
            } else {
                books = repository.findAll();
            }

            // Apply readStatus filter on top of search, subject, or genre result
            if (
                readStatus != null &&
                ((search != null && !search.isBlank()) ||
                    (subject != null && !subject.isBlank()) ||
                    (genre != null && !genre.isBlank()))
            ) {
                ReadStatus finalReadStatus = readStatus;
                books = books
                    .stream()
                    .filter(b -> b.getReadStatus() == finalReadStatus)
                    .toList();
            }

            String sortParam = request.getQueryParams().get("sort"); // e.g. "title,asc"
            if (sortParam != null && !sortParam.isBlank()) {
                String[] parts = sortParam.split(",", 2);
                String field = parts[0].trim();
                boolean desc =
                    parts.length > 1 &&
                    "desc".equalsIgnoreCase(parts[1].trim());

                Comparator<Book> comparator = switch (field) {
                    case "title" -> Comparator.comparing(b ->
                        b.getTitle() != null ? b.getTitle().toLowerCase() : ""
                    );
                    case "author" -> Comparator.comparing(b ->
                        b.getAuthor() != null ? b.getAuthor().toLowerCase() : ""
                    );
                    case "rating" -> Comparator.comparing(b ->
                        b.getRating() != null ? b.getRating() : 0
                    );
                    case "created" -> Comparator.comparing(b ->
                        b.getCreatedAt() != null
                            ? b.getCreatedAt()
                            : Instant.EPOCH
                    );
                    default -> null;
                };

                if (comparator != null) {
                    if (desc) comparator = comparator.reversed();
                    books = books.stream().sorted(comparator).toList();
                }
            }

            // Pagination
            String pageParam = request.getQueryParams().get("page");
            String sizeParam = request.getQueryParams().get("size");

            if (pageParam != null) {
                int page;
                try {
                    page = Integer.parseInt(pageParam);
                } catch (NumberFormatException e) {
                    return HttpResponse.badRequest("invalid page parameter");
                }
                if (page < 1) {
                    return HttpResponse.badRequest("page must be >= 1");
                }

                int size = 20;
                if (sizeParam != null) {
                    try {
                        size = Integer.parseInt(sizeParam);
                    } catch (NumberFormatException e) {
                        return HttpResponse.badRequest("invalid size parameter");
                    }
                }
                if (size < 1) size = 20;
                if (size > 100) size = 100;

                int totalItems = books.size();
                int totalPages = (int) Math.ceil((double) totalItems / size);
                int offset = (page - 1) * size;
                int end = Math.min(offset + size, totalItems);
                List<Book> pageItems = offset < totalItems ? books.subList(offset, end) : List.of();

                JsonArray pageArray = new JsonArray();
                for (Book book : pageItems) {
                    pageArray.add(enrichBookJson(book));
                }

                JsonObject wrapper = new JsonObject();
                wrapper.add("books", pageArray);
                wrapper.addProperty("page", page);
                wrapper.addProperty("size", size);
                wrapper.addProperty("totalItems", totalItems);
                wrapper.addProperty("totalPages", totalPages);
                return HttpResponse.ok(wrapper.toString());
            }

            JsonArray booksArray = new JsonArray();
            for (Book book : books) {
                booksArray.add(enrichBookJson(book));
            }
            return HttpResponse.ok(booksArray.toString());
        } catch (RuntimeException e) {
            logger.error("Error in handleGetBooks", e);
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

            return repository
                .findById(id)
                .map(book -> HttpResponse.ok(enrichBookJson(book).toString()))
                .orElse(HttpResponse.notFound("Book not found"));
        } catch (RuntimeException e) {
            logger.error("Error in handleGetBook", e);
            return HttpResponse.internalServerError("Internal server error");
        }
    }

    public HttpResponse handleGetBookByIsbn(HttpRequest request) {
        try {
            String isbn = request.getPathParams().get("isbn");
            return repository
                .findByIsbn(isbn)
                .map(book -> HttpResponse.ok(enrichBookJson(book).toString()))
                .orElse(HttpResponse.notFound("Book not found"));
        } catch (RuntimeException e) {
            logger.error("Error in handleGetBookByIsbn", e);
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
            readStatus = ReadStatus.valueOf(
                json.get("readStatus").getAsString()
            );
        } catch (IllegalArgumentException e) {
            return HttpResponse.badRequest("Invalid readStatus");
        }

        // Validate rating if provided
        if (json.has("rating") && !json.get("rating").isJsonNull()) {
            try {
                safeGetRating(json);
            } catch (IllegalArgumentException e) {
                return HttpResponse.badRequest(e.getMessage());
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
                book.setRating(safeGetRating(json));
            } else {
                book.setRating(0);
            }

            if (json.has("pageCount") && !json.get("pageCount").isJsonNull()) {
                book.setPageCount(safeGetInt(json, "pageCount"));
            }

            if (
                json.has("readingProgress") &&
                !json.get("readingProgress").isJsonNull()
            ) {
                try {
                    int progress = json.get("readingProgress").getAsInt();
                    if (progress < 0 || progress > 100) {
                        return HttpResponse.badRequest(
                            "readingProgress must be between 0 and 100"
                        );
                    }
                    book.setReadingProgress(progress);
                } catch (
                    NumberFormatException
                    | UnsupportedOperationException e
                ) {
                    return HttpResponse.badRequest(
                        "'readingProgress' must be a valid integer"
                    );
                }
            }

            if (json.has("subjects") && !json.get("subjects").isJsonNull()) {
                if (!json.get("subjects").isJsonArray()) {
                    return HttpResponse.badRequest("subjects must be an array");
                }
                List<String> subjects = new ArrayList<>();
                for (var el : json.getAsJsonArray("subjects")) {
                    subjects.add(el.getAsString());
                }
                book.setSubjects(subjects);
            }

            book.setReview(getStringField(json, "review"));

            // Parse and validate dates
            String startedAt = getStringField(json, "startedAt");
            String finishedAt = getStringField(json, "finishedAt");
            if (startedAt != null) {
                if (!isValidDate(startedAt)) return HttpResponse.badRequest("Invalid startedAt date format (must be YYYY-MM-DD)");
                if (isFutureDate(startedAt)) return HttpResponse.badRequest("startedAt cannot be in the future");
            }
            if (finishedAt != null) {
                if (!isValidDate(finishedAt)) return HttpResponse.badRequest("Invalid finishedAt date format (must be YYYY-MM-DD)");
                if (isFutureDate(finishedAt)) return HttpResponse.badRequest("finishedAt cannot be in the future");
            }
            if (startedAt != null && finishedAt != null && finishedAt.compareTo(startedAt) < 0) {
                return HttpResponse.badRequest("finishedAt cannot be before startedAt");
            }

            book.setStartedAt(startedAt);
            book.setFinishedAt(finishedAt);

            // Auto-set dates based on readStatus
            String today = LocalDate.now().toString();
            if (readStatus == ReadStatus.READING) {
                if (book.getStartedAt() == null) book.setStartedAt(today);
            } else if (readStatus == ReadStatus.FINISHED) {
                if (book.getStartedAt() == null) book.setStartedAt(today);
                if (book.getFinishedAt() == null) book.setFinishedAt(today);
            } else if (readStatus == ReadStatus.DNF) {
                if (book.getFinishedAt() == null) book.setFinishedAt(today);
            }

            Instant now = Instant.now();
            book.setCreatedAt(now);
            book.setUpdatedAt(now);

            repository.save(book);

            // Fire async enrichment if ISBN is present
            if (book.getIsbn() != null && openLibraryService != null) {
                openLibraryService.enrichBookAsync(
                    book.getId(),
                    book.getIsbn()
                );
            }

            return HttpResponse.created(enrichBookJson(book).toString());
        } catch (IllegalArgumentException e) {
            return HttpResponse.badRequest(e.getMessage());
        } catch (RuntimeException e) {
            logger.error("Error in handleCreateBook", e);
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
                safeGetRating(json);
            } catch (IllegalArgumentException e) {
                return HttpResponse.badRequest(e.getMessage());
            }
        }

        // Validate ISBN if provided
        if (json.has("isbn") && !json.get("isbn").isJsonNull()) {
            String isbnVal = json.get("isbn").getAsString();
            if (!isValidIsbn(isbnVal)) {
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
                book.setTitle(
                    json.get("title").isJsonNull()
                        ? null
                        : json.get("title").getAsString()
                );
            }
            if (json.has("author")) {
                book.setAuthor(
                    json.get("author").isJsonNull()
                        ? null
                        : json.get("author").getAsString()
                );
            }
            if (json.has("genre")) {
                book.setGenre(
                    json.get("genre").isJsonNull()
                        ? null
                        : json.get("genre").getAsString()
                );
            }
            if (json.has("isbn")) {
                book.setIsbn(
                    json.get("isbn").isJsonNull()
                        ? null
                        : json.get("isbn").getAsString()
                );
            }
            if (json.has("publisher")) {
                book.setPublisher(
                    json.get("publisher").isJsonNull()
                        ? null
                        : json.get("publisher").getAsString()
                );
            }
            if (json.has("publishDate")) {
                book.setPublishDate(
                    json.get("publishDate").isJsonNull()
                        ? null
                        : json.get("publishDate").getAsString()
                );
            }
            if (json.has("rating")) {
                book.setRating(
                    json.get("rating").isJsonNull()
                        ? null
                        : safeGetRating(json)
                );
            }
            if (json.has("pageCount")) {
                book.setPageCount(
                    json.get("pageCount").isJsonNull()
                        ? null
                        : safeGetInt(json, "pageCount")
                );
            }
            if (json.has("readStatus")) {
                if (json.get("readStatus").isJsonNull()) {
                    return HttpResponse.badRequest("readStatus cannot be null");
                }
                try {
                    book.setReadStatus(
                        ReadStatus.valueOf(json.get("readStatus").getAsString())
                    );
                } catch (IllegalArgumentException e) {
                    return HttpResponse.badRequest("Invalid readStatus");
                }
            }
            if (json.has("subjects")) {
                if (json.get("subjects").isJsonNull()) {
                    book.setSubjects(null);
                } else {
                    List<String> subjects = new ArrayList<>();
                    try {
                        json
                            .get("subjects")
                            .getAsJsonArray()
                            .forEach(e -> subjects.add(e.getAsString()));
                    } catch (
                        IllegalStateException
                        | UnsupportedOperationException e
                    ) {
                        return HttpResponse.badRequest(
                            "'subjects' must be an array of strings"
                        );
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
                            return HttpResponse.badRequest(
                                "readingProgress must be between 0 and 100"
                            );
                        }
                        book.setReadingProgress(progress);
                    } catch (
                        NumberFormatException
                        | UnsupportedOperationException e
                    ) {
                        return HttpResponse.badRequest(
                            "'readingProgress' must be a valid integer"
                        );
                    }
                }
            }

            if (json.has("review")) {
                book.setReview(
                    json.get("review").isJsonNull()
                        ? null
                        : json.get("review").getAsString()
                );
            }

            // Handle startedAt / finishedAt
            if (json.has("startedAt")) {
                if (json.get("startedAt").isJsonNull()) {
                    book.setStartedAt(null);
                } else {
                    String val = json.get("startedAt").getAsString();
                    if (!isValidDate(val)) return HttpResponse.badRequest("Invalid startedAt date format (must be YYYY-MM-DD)");
                    if (isFutureDate(val)) return HttpResponse.badRequest("startedAt cannot be in the future");
                    book.setStartedAt(val);
                }
            }
            if (json.has("finishedAt")) {
                if (json.get("finishedAt").isJsonNull()) {
                    book.setFinishedAt(null);
                } else {
                    String val = json.get("finishedAt").getAsString();
                    if (!isValidDate(val)) return HttpResponse.badRequest("Invalid finishedAt date format (must be YYYY-MM-DD)");
                    if (isFutureDate(val)) return HttpResponse.badRequest("finishedAt cannot be in the future");
                    book.setFinishedAt(val);
                }
            }

            // Auto-set dates on status change
            if (json.has("readStatus")) {
                String today = LocalDate.now().toString();
                ReadStatus newStatus = book.getReadStatus();
                if (newStatus == ReadStatus.READING) {
                    if (!json.has("startedAt") && book.getStartedAt() == null) book.setStartedAt(today);
                    if (!json.has("finishedAt")) book.setFinishedAt(null);
                } else if (newStatus == ReadStatus.FINISHED) {
                    if (!json.has("finishedAt") && book.getFinishedAt() == null) book.setFinishedAt(today);
                    if (!json.has("startedAt") && book.getStartedAt() == null) book.setStartedAt(today);
                } else if (newStatus == ReadStatus.DNF) {
                    if (!json.has("finishedAt") && book.getFinishedAt() == null) book.setFinishedAt(today);
                } else if (newStatus == ReadStatus.WANT_TO_READ) {
                    if (!json.has("startedAt")) book.setStartedAt(null);
                    if (!json.has("finishedAt")) book.setFinishedAt(null);
                }
            }

            // Validate date ordering after all changes applied
            if (book.getStartedAt() != null && book.getFinishedAt() != null
                    && book.getFinishedAt().compareTo(book.getStartedAt()) < 0) {
                return HttpResponse.badRequest("finishedAt cannot be before startedAt");
            }

            // Check if ISBN changed — trigger re-enrichment
            String newIsbn = book.getIsbn();
            boolean isbnChanged =
                openLibraryService != null &&
                json.has("isbn") &&
                ((newIsbn == null && oldIsbn != null) ||
                    (newIsbn != null && !newIsbn.equals(oldIsbn)));

            if (isbnChanged && newIsbn != null) {
                // Clear previously-enriched fields before re-enrichment
                // but preserve fields explicitly provided in this request
                if (!json.has("publisher")) book.setPublisher(null);
                if (!json.has("publishDate")) book.setPublishDate(null);
                if (!json.has("pageCount")) book.setPageCount(null);
                if (!json.has("subjects")) book.setSubjects(null);
                if (!json.has("coverUrl")) book.setCoverUrl(null);
                book.setCoverData(null);
            }

            book.setUpdatedAt(Instant.now());
            repository.update(id, book);

            if (isbnChanged && newIsbn != null) {
                openLibraryService.enrichBookAsync(id, newIsbn);
            }

            return HttpResponse.ok(enrichBookJson(book).toString());
        } catch (IllegalArgumentException e) {
            return HttpResponse.badRequest(e.getMessage());
        } catch (RuntimeException e) {
            logger.error("Error in handleUpdateBook", e);
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
                if (shelfRepository != null) {
                    shelfRepository.removeBookFromAllShelves(id);
                }
                return HttpResponse.noContent();
            }
            return HttpResponse.notFound("Book not found");
        } catch (RuntimeException e) {
            logger.error("Error in handleDeleteBook", e);
            return HttpResponse.internalServerError("Internal server error");
        }
    }

    public HttpResponse handleReEnrich(HttpRequest request) {
        try {
            List<Book> booksWithIsbn = repository
                .findAll()
                .stream()
                .filter(b -> b.getIsbn() != null && !b.getIsbn().isEmpty())
                .toList();

            if (booksWithIsbn.isEmpty()) {
                return HttpResponse.accepted("{\"queued\":0}");
            }

            if (openLibraryService == null) {
                return HttpResponse.internalServerError(
                    "Open Library service not available"
                );
            }

            int queued = openLibraryService.reEnrichAll(booksWithIsbn);
            return HttpResponse.accepted("{\"queued\":" + queued + "}");
        } catch (RuntimeException e) {
            logger.error("Error in handleReEnrich", e);
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

            return HttpResponse.binary(
                bookOpt.get().getCoverData(),
                "image/jpeg"
            );
        } catch (RuntimeException e) {
            logger.error("Error in handleGetCover", e);
            return HttpResponse.internalServerError("Internal server error");
        }
    }

    private String getStringField(JsonObject json, String field) {
        return GsonFactory.getStringField(json, field);
    }

    private Integer safeGetInt(JsonObject json, String field) {
        if (!json.has(field) || json.get(field).isJsonNull()) return null;
        try {
            return json.get(field).getAsInt();
        } catch (NumberFormatException | UnsupportedOperationException e) {
            throw new IllegalArgumentException(
                "'" + field + "' must be a valid integer"
            );
        }
    }

    /**
     * Parses a rating from JSON (accepts decimals like 3.5), validates it as a 0.5-increment
     * between 0.5 and 5.0, and returns the internal representation (value * 2, so 1-10).
     */
    private Integer safeGetRating(JsonObject json) {
        if (!json.has("rating") || json.get("rating").isJsonNull()) return null;
        try {
            double val = json.get("rating").getAsNumber().doubleValue();
            int internal = (int) Math.round(val * 2);
            if (internal < 1 || internal > 10 || Math.abs(val * 2 - internal) > 0.01) {
                throw new IllegalArgumentException(
                    "Rating must be between 0.5 and 5 in 0.5 increments"
                );
            }
            return internal;
        } catch (NumberFormatException | UnsupportedOperationException e) {
            throw new IllegalArgumentException(
                "Rating must be between 0.5 and 5 in 0.5 increments"
            );
        }
    }

    private boolean isValidDate(String date) {
        if (date == null) return false;
        if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) return false;
        try {
            LocalDate.parse(date);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private boolean isFutureDate(String date) {
        return LocalDate.parse(date).isAfter(LocalDate.now());
    }

    private boolean isValidIsbn(String isbn) {
        if (isbn == null) return true;
        if (isbn.length() == 13) return isbn.matches("\\d{13}") &&
            (isbn.startsWith("978") || isbn.startsWith("979"));
        if (isbn.length() == 10) return isbn.matches("\\d{9}[\\dX]");
        return false;
    }

    public HttpResponse handleGetStats(HttpRequest request) {
        try {
            List<Book> allBooks = repository.findAll();
            JsonObject stats = new JsonObject();

            // totalBooks
            stats.addProperty("totalBooks", allBooks.size());

            // byStatus
            JsonObject byStatus = new JsonObject();
            for (ReadStatus s : ReadStatus.values()) {
                byStatus.addProperty(s.name(),
                    allBooks.stream().filter(b -> b.getReadStatus() == s).count());
            }
            stats.add("byStatus", byStatus);

            // byGenre
            JsonObject byGenre = new JsonObject();
            Map<String, Long> genreCounts = allBooks.stream()
                .collect(Collectors.groupingBy(
                    b -> b.getGenre() != null ? b.getGenre() : "null",
                    Collectors.counting()));
            genreCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> byGenre.addProperty(e.getKey(), e.getValue()));
            stats.add("byGenre", byGenre);

            // ratings
            JsonObject ratings = new JsonObject();
            List<Book> rated = allBooks.stream()
                .filter(b -> b.getRating() != null && b.getRating() > 0)
                .collect(Collectors.toList());
            double avg = rated.isEmpty() ? 0 :
                rated.stream().mapToDouble(b -> b.getRating() / 2.0).average().orElse(0);
            ratings.addProperty("average", Math.round(avg * 10.0) / 10.0);

            JsonObject dist = new JsonObject();
            Map<String, Long> ratingCounts = rated.stream()
                .collect(Collectors.groupingBy(b -> {
                    int r = b.getRating();
                    return r % 2 == 0 ? String.valueOf(r / 2) : String.valueOf(r / 2.0);
                }, Collectors.counting()));
            ratingCounts.entrySet().stream()
                .sorted(Comparator.comparingDouble(e -> Double.parseDouble(e.getKey())))
                .forEach(e -> dist.addProperty(e.getKey(), e.getValue()));
            ratings.add("distribution", dist);
            ratings.addProperty("unrated",
                allBooks.stream().filter(b -> b.getRating() == null || b.getRating() == 0).count());
            stats.add("ratings", ratings);

            // pages
            JsonObject pages = new JsonObject();
            List<Book> finishedWithPages = allBooks.stream()
                .filter(b -> b.getReadStatus() == ReadStatus.FINISHED
                    && b.getPageCount() != null && b.getPageCount() > 0)
                .collect(Collectors.toList());
            int totalRead = finishedWithPages.stream().mapToInt(Book::getPageCount).sum();
            pages.addProperty("totalRead", totalRead);
            pages.addProperty("averagePerBook",
                finishedWithPages.isEmpty() ? 0 : totalRead / finishedWithPages.size());
            stats.add("pages", pages);

            // finishedByMonth
            JsonObject finishedByMonth = new JsonObject();
            Map<String, Long> monthCounts = new TreeMap<>();
            allBooks.stream()
                .filter(b -> b.getReadStatus() == ReadStatus.FINISHED)
                .forEach(b -> {
                    String month;
                    if (b.getFinishedAt() != null && !b.getFinishedAt().isEmpty()) {
                        month = b.getFinishedAt().substring(0, 7); // YYYY-MM
                    } else if (b.getCreatedAt() != null) {
                        month = YearMonth.from(
                            b.getCreatedAt().atZone(java.time.ZoneId.systemDefault())).toString();
                    } else {
                        return;
                    }
                    monthCounts.merge(month, 1L, Long::sum);
                });
            monthCounts.forEach(finishedByMonth::addProperty);
            stats.add("finishedByMonth", finishedByMonth);

            // topAuthors (top 5)
            JsonArray topAuthors = new JsonArray();
            allBooks.stream()
                .filter(b -> b.getAuthor() != null && !b.getAuthor().isEmpty())
                .collect(Collectors.groupingBy(Book::getAuthor, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .forEach(e -> {
                    JsonObject a = new JsonObject();
                    a.addProperty("author", e.getKey());
                    a.addProperty("count", e.getValue());
                    topAuthors.add(a);
                });
            stats.add("topAuthors", topAuthors);

            // recentlyFinished (last 5)
            JsonArray recentlyFinished = new JsonArray();
            allBooks.stream()
                .filter(b -> b.getReadStatus() == ReadStatus.FINISHED)
                .sorted((a, b) -> {
                    String aDate = a.getFinishedAt() != null ? a.getFinishedAt() : "";
                    String bDate = b.getFinishedAt() != null ? b.getFinishedAt() : "";
                    int cmp = bDate.compareTo(aDate);
                    if (cmp != 0) return cmp;
                    if (a.getCreatedAt() != null && b.getCreatedAt() != null)
                        return b.getCreatedAt().compareTo(a.getCreatedAt());
                    return 0;
                })
                .limit(5)
                .forEach(b -> {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("id", b.getId().toString());
                    obj.addProperty("title", b.getTitle());
                    obj.addProperty("author", b.getAuthor());
                    int r = b.getRating() != null ? b.getRating() : 0;
                    if (r > 0) {
                        obj.addProperty("rating", r % 2 == 0 ? r / 2 : r / 2.0);
                    } else {
                        obj.addProperty("rating", 0);
                    }
                    if (b.getCreatedAt() != null)
                        obj.addProperty("createdAt", b.getCreatedAt().toString());
                    if (b.getFinishedAt() != null)
                        obj.addProperty("finishedAt", b.getFinishedAt());
                    recentlyFinished.add(obj);
                });
            stats.add("recentlyFinished", recentlyFinished);

            return HttpResponse.ok(gson.toJson(stats));
        } catch (RuntimeException e) {
            return HttpResponse.internalServerError("Internal server error");
        }
    }

    // ---- CSV Export ----

    private static final String[] CSV_COLUMNS = {
        "id", "title", "author", "genre", "rating", "isbn", "publisher",
        "publishDate", "pageCount", "subjects", "readStatus", "readingProgress",
        "review", "startedAt", "finishedAt", "coverUrl", "createdAt"
    };

    public HttpResponse handleExportBooks(HttpRequest request) {
        try {
            List<Book> books = repository.findAll();
            StringBuilder csv = new StringBuilder();
            csv.append(String.join(",", CSV_COLUMNS)).append("\r\n");
            for (Book b : books) {
                csv.append(csvField(b.getId() != null ? b.getId().toString() : "")).append(',');
                csv.append(csvField(b.getTitle())).append(',');
                csv.append(csvField(b.getAuthor())).append(',');
                csv.append(csvField(b.getGenre())).append(',');
                int r = b.getRating() != null ? b.getRating() : 0;
                csv.append(r > 0 ? (r % 2 == 0 ? String.valueOf(r / 2) : String.valueOf(r / 2.0)) : "0").append(',');
                csv.append(csvField(b.getIsbn())).append(',');
                csv.append(csvField(b.getPublisher())).append(',');
                csv.append(csvField(b.getPublishDate())).append(',');
                csv.append(b.getPageCount() != null ? b.getPageCount() : "").append(',');
                csv.append(csvField(b.getSubjects() != null ? gson.toJson(b.getSubjects()) : "")).append(',');
                csv.append(b.getReadStatus() != null ? b.getReadStatus().name() : "").append(',');
                csv.append(b.getReadingProgress() != null ? b.getReadingProgress() : "").append(',');
                csv.append(csvField(b.getReview())).append(',');
                csv.append(csvField(b.getStartedAt())).append(',');
                csv.append(csvField(b.getFinishedAt())).append(',');
                csv.append(csvField(b.getCoverUrl())).append(',');
                csv.append(b.getCreatedAt() != null ? b.getCreatedAt().toString() : "");
                csv.append("\r\n");
            }
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "text/csv");
            headers.put("Content-Disposition", "attachment; filename=\"mybookshelf-export.csv\"");
            return new HttpResponse(200, "OK", headers, csv.toString());
        } catch (RuntimeException e) {
            logger.error("Error in handleExportBooks", e);
            return HttpResponse.internalServerError("Internal server error");
        }
    }

    private String csvField(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    // ---- CSV Import ----

    public HttpResponse handleImportBooks(HttpRequest request) {
        String body = request.getBody();
        if (body == null || body.isBlank()) {
            return HttpResponse.badRequest("request body is empty");
        }
        // Strip UTF-8 BOM
        if (body.charAt(0) == '\uFEFF') {
            body = body.substring(1);
        }
        // Check size (5MB)
        if (body.length() > 5 * 1024 * 1024) {
            return HttpResponse.payloadTooLarge("CSV file too large (max 5MB)");
        }

        List<String[]> rows = parseCsv(body);
        if (rows.isEmpty()) {
            return HttpResponse.badRequest("CSV has no header row");
        }

        String[] headerRow = rows.get(0);
        boolean isGoodreads = isGoodreadsFormat(headerRow);
        boolean isMyBookShelf = isMyBookShelfFormat(headerRow);
        if (!isGoodreads && !isMyBookShelf) {
            return HttpResponse.badRequest("unrecognized CSV format");
        }

        Map<String, Integer> colIndex = new HashMap<>();
        for (int i = 0; i < headerRow.length; i++) {
            colIndex.put(headerRow[i].trim(), i);
        }

        int imported = 0;
        int skipped = 0;
        int errors = 0;
        JsonArray details = new JsonArray();
        List<Book> importedBooks = new ArrayList<>();

        for (int rowNum = 1; rowNum < rows.size(); rowNum++) {
            String[] cols = rows.get(rowNum);
            try {
                Book book;
                if (isGoodreads) {
                    book = parseGoodreadsRow(cols, colIndex);
                } else {
                    book = parseMyBookShelfRow(cols, colIndex);
                }

                // Check for duplicate ISBN
                if (book.getIsbn() != null && !book.getIsbn().isEmpty()) {
                    if (repository.findByIsbn(book.getIsbn()).isPresent()) {
                        skipped++;
                        JsonObject detail = new JsonObject();
                        detail.addProperty("row", rowNum + 1);
                        detail.addProperty("isbn", book.getIsbn());
                        detail.addProperty("reason", "duplicate ISBN, skipped");
                        details.add(detail);
                        continue;
                    }
                }

                // Validate minimum fields
                String titleVal = book.getTitle();
                String isbnVal = book.getIsbn();
                if ((titleVal == null || titleVal.isBlank()) && (isbnVal == null || isbnVal.isBlank())) {
                    errors++;
                    JsonObject detail = new JsonObject();
                    detail.addProperty("row", rowNum + 1);
                    detail.addProperty("reason", "missing title and ISBN");
                    details.add(detail);
                    continue;
                }

                if (book.getReadStatus() == null) {
                    book.setReadStatus(ReadStatus.WANT_TO_READ);
                }
                book.setId(UUID.randomUUID());
                if (book.getCreatedAt() == null) {
                    book.setCreatedAt(Instant.now());
                }
                repository.save(book);
                importedBooks.add(book);
                imported++;
            } catch (Exception e) {
                errors++;
                JsonObject detail = new JsonObject();
                detail.addProperty("row", rowNum + 1);
                detail.addProperty("reason", e.getMessage());
                details.add(detail);
            }
        }

        // Trigger enrichment for imported books with ISBNs
        if (openLibraryService != null && !importedBooks.isEmpty()) {
            List<Book> booksWithIsbn = importedBooks.stream()
                .filter(b -> b.getIsbn() != null && !b.getIsbn().isEmpty())
                .collect(Collectors.toList());
            if (!booksWithIsbn.isEmpty()) {
                openLibraryService.reEnrichAll(booksWithIsbn);
            }
        }

        JsonObject result = new JsonObject();
        result.addProperty("imported", imported);
        result.addProperty("skipped", skipped);
        result.addProperty("errors", errors);
        result.add("details", details);
        return HttpResponse.ok(gson.toJson(result));
    }

    private boolean isGoodreadsFormat(String[] header) {
        for (String col : header) {
            if (col.trim().equals("Exclusive Shelf")) return true;
        }
        return false;
    }

    private boolean isMyBookShelfFormat(String[] header) {
        for (String col : header) {
            if (col.trim().equals("readStatus")) return true;
        }
        return false;
    }

    private Book parseGoodreadsRow(String[] cols, Map<String, Integer> colIndex) {
        Book book = new Book();
        book.setTitle(getCsvCol(cols, colIndex, "Title"));
        book.setAuthor(getCsvCol(cols, colIndex, "Author"));

        // ISBN: prefer ISBN13, fallback to ISBN. Strip ="..." wrapper
        String isbn13 = cleanGoodreadsIsbn(getCsvCol(cols, colIndex, "ISBN13"));
        String isbn10 = cleanGoodreadsIsbn(getCsvCol(cols, colIndex, "ISBN"));
        if (isbn13 != null && !isbn13.isEmpty()) {
            book.setIsbn(isbn13);
        } else if (isbn10 != null && !isbn10.isEmpty()) {
            book.setIsbn(isbn10);
        }

        // Rating
        String ratingStr = getCsvCol(cols, colIndex, "My Rating");
        if (ratingStr != null && !ratingStr.isEmpty()) {
            try {
                int rating = Integer.parseInt(ratingStr.trim());
                // Goodreads uses 0-5 integer, we store 0-10 internally
                book.setRating(rating * 2);
            } catch (NumberFormatException ignored) {}
        }

        book.setPublisher(getCsvCol(cols, colIndex, "Publisher"));

        String pagesStr = getCsvCol(cols, colIndex, "Number of Pages");
        if (pagesStr != null && !pagesStr.isEmpty()) {
            try {
                book.setPageCount(Integer.parseInt(pagesStr.trim()));
            } catch (NumberFormatException ignored) {}
        }

        String yearPub = getCsvCol(cols, colIndex, "Year Published");
        if (yearPub != null && !yearPub.isEmpty()) {
            book.setPublishDate(yearPub.trim());
        }

        // Status mapping
        String shelf = getCsvCol(cols, colIndex, "Exclusive Shelf");
        if (shelf != null) {
            switch (shelf.trim().toLowerCase()) {
                case "read": book.setReadStatus(ReadStatus.FINISHED); break;
                case "currently-reading": book.setReadStatus(ReadStatus.READING); break;
                case "to-read": book.setReadStatus(ReadStatus.WANT_TO_READ); break;
                default: book.setReadStatus(ReadStatus.WANT_TO_READ); break;
            }
        }

        // Date Read -> finishedAt
        String dateRead = getCsvCol(cols, colIndex, "Date Read");
        if (dateRead != null && !dateRead.isEmpty()) {
            book.setFinishedAt(dateRead.trim().replace("/", "-"));
        }

        // My Review
        String review = getCsvCol(cols, colIndex, "My Review");
        if (review != null && !review.isEmpty()) {
            book.setReview(review);
        }

        return book;
    }

    private Book parseMyBookShelfRow(String[] cols, Map<String, Integer> colIndex) {
        Book book = new Book();
        // id is ignored — new UUID generated
        book.setTitle(getCsvCol(cols, colIndex, "title"));
        book.setAuthor(getCsvCol(cols, colIndex, "author"));
        book.setGenre(getCsvCol(cols, colIndex, "genre"));

        String ratingStr = getCsvCol(cols, colIndex, "rating");
        if (ratingStr != null && !ratingStr.isEmpty()) {
            try {
                double rating = Double.parseDouble(ratingStr.trim());
                book.setRating((int) Math.round(rating * 2));
            } catch (NumberFormatException ignored) {}
        }

        book.setIsbn(getCsvCol(cols, colIndex, "isbn"));
        book.setPublisher(getCsvCol(cols, colIndex, "publisher"));
        book.setPublishDate(getCsvCol(cols, colIndex, "publishDate"));

        String pagesStr = getCsvCol(cols, colIndex, "pageCount");
        if (pagesStr != null && !pagesStr.isEmpty()) {
            try {
                book.setPageCount(Integer.parseInt(pagesStr.trim()));
            } catch (NumberFormatException ignored) {}
        }

        String subjects = getCsvCol(cols, colIndex, "subjects");
        if (subjects != null && !subjects.isEmpty()) {
            try {
                JsonArray arr = JsonParser.parseString(subjects).getAsJsonArray();
                List<String> list = new ArrayList<>();
                for (JsonElement el : arr) list.add(el.getAsString());
                book.setSubjects(list);
            } catch (Exception ignored) {}
        }

        String status = getCsvCol(cols, colIndex, "readStatus");
        if (status != null && !status.isEmpty()) {
            try {
                book.setReadStatus(ReadStatus.valueOf(status.trim()));
            } catch (IllegalArgumentException ignored) {
                book.setReadStatus(ReadStatus.WANT_TO_READ);
            }
        }

        String progress = getCsvCol(cols, colIndex, "readingProgress");
        if (progress != null && !progress.isEmpty()) {
            try {
                book.setReadingProgress(Integer.parseInt(progress.trim()));
            } catch (NumberFormatException ignored) {}
        }

        String review = getCsvCol(cols, colIndex, "review");
        if (review != null && !review.isEmpty()) {
            book.setReview(review);
        }

        book.setStartedAt(getCsvCol(cols, colIndex, "startedAt"));
        book.setFinishedAt(getCsvCol(cols, colIndex, "finishedAt"));
        book.setCoverUrl(getCsvCol(cols, colIndex, "coverUrl"));

        String createdAt = getCsvCol(cols, colIndex, "createdAt");
        if (createdAt != null && !createdAt.isEmpty()) {
            try {
                book.setCreatedAt(Instant.parse(createdAt.trim()));
            } catch (DateTimeParseException ignored) {}
        }

        return book;
    }

    private String getCsvCol(String[] cols, Map<String, Integer> colIndex, String colName) {
        Integer idx = colIndex.get(colName);
        if (idx == null || idx >= cols.length) return null;
        String val = cols[idx].trim();
        return val.isEmpty() ? null : val;
    }

    private String cleanGoodreadsIsbn(String isbn) {
        if (isbn == null) return null;
        isbn = isbn.trim();
        // Strip ="..." wrapper (full form)
        if (isbn.startsWith("=\"") && isbn.endsWith("\"")) {
            isbn = isbn.substring(2, isbn.length() - 1);
        }
        // After CSV parsing, ="" wrapper becomes = prefix (quotes consumed by parser)
        if (isbn.startsWith("=")) {
            isbn = isbn.substring(1);
        }
        // Strip any remaining quotes
        isbn = isbn.replace("\"", "");
        return isbn.isEmpty() ? null : isbn;
    }

    private List<String[]> parseCsv(String csv) {
        List<String[]> rows = new ArrayList<>();
        List<String> currentRow = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int len = csv.length();

        for (int i = 0; i < len; i++) {
            char c = csv.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < len && csv.charAt(i + 1) == '"') {
                        field.append('"');
                        i++; // skip escaped quote
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    currentRow.add(field.toString());
                    field.setLength(0);
                } else if (c == '\r') {
                    if (i + 1 < len && csv.charAt(i + 1) == '\n') {
                        i++; // skip \n in \r\n
                    }
                    currentRow.add(field.toString());
                    field.setLength(0);
                    if (!currentRow.isEmpty()) {
                        rows.add(currentRow.toArray(new String[0]));
                    }
                    currentRow = new ArrayList<>();
                } else if (c == '\n') {
                    currentRow.add(field.toString());
                    field.setLength(0);
                    if (!currentRow.isEmpty()) {
                        rows.add(currentRow.toArray(new String[0]));
                    }
                    currentRow = new ArrayList<>();
                } else {
                    field.append(c);
                }
            }
        }
        // Last field/row
        if (field.length() > 0 || !currentRow.isEmpty()) {
            currentRow.add(field.toString());
            rows.add(currentRow.toArray(new String[0]));
        }
        return rows;
    }

    private JsonObject enrichBookJson(Book book) {
        JsonObject obj = JsonParser.parseString(
            gson.toJson(book)
        ).getAsJsonObject();
        // Convert internal rating (0-10) to display rating (0-5, half-star increments)
        if (book.getRating() == null) {
            // Keep null as-is (Gson already serialized it as null)
        } else if (book.getRating() > 0) {
            int internal = book.getRating();
            if (internal % 2 == 0) {
                obj.addProperty("rating", internal / 2);
            } else {
                obj.addProperty("rating", internal / 2.0);
            }
        } else {
            obj.addProperty("rating", 0);
        }
        JsonArray shelvesArr = new JsonArray();
        if (shelfRepository != null) {
            List<Shelf> bookShelves = shelfRepository.findShelvesForBook(
                book.getId()
            );
            for (Shelf s : bookShelves) {
                JsonObject shelfObj = new JsonObject();
                shelfObj.addProperty("id", s.getId().toString());
                shelfObj.addProperty("name", s.getName());
                shelfObj.addProperty("color", s.getColor());
                shelvesArr.add(shelfObj);
            }
        }
        obj.add("shelves", shelvesArr);
        return obj;
    }
}
