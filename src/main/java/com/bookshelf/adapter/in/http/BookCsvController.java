package com.bookshelf.adapter.in.http;

import com.bookshelf.adapter.out.enrichment.BookEnrichmentService;
import com.bookshelf.domain.model.Book;
import com.bookshelf.domain.model.ReadStatus;
import com.bookshelf.domain.port.out.BookRepository;
import com.bookshelf.framework.http.HttpRequest;
import com.bookshelf.framework.http.HttpResponse;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class BookCsvController {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(BookCsvController.class);

    private static final String[] CSV_COLUMNS = {
        "id", "title", "author", "genre", "rating", "isbn", "publisher",
        "publishDate", "pageCount", "subjects", "readStatus", "readingProgress",
        "review", "startedAt", "finishedAt", "coverUrl", "createdAt"
    };

    private final BookRepository repository;
    private final BookEnrichmentService openLibraryService;
    private final Gson gson = GsonFactory.create();

    public BookCsvController(BookRepository repository, BookEnrichmentService openLibraryService) {
        this.repository = repository;
        this.openLibraryService = openLibraryService;
    }

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

    private String csvField(String value) {
        if (value == null) return "";
        // Prevent CSV formula injection: prefix dangerous first characters
        if (!value.isEmpty()) {
            char first = value.charAt(0);
            if (first == '=' || first == '@' || first == '\t' || first == '\r') {
                value = "'" + value;
            }
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")
                || value.contains("\r") || value.startsWith("'")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
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

        // Ignore imported createdAt — always use current time to prevent stats manipulation
        book.setCreatedAt(Instant.now());

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
}
