package com.bookshelf.adapter.in.mcp;

import com.bookshelf.domain.model.Book;
import com.bookshelf.domain.model.ReadStatus;
import com.bookshelf.domain.model.Shelf;
import com.bookshelf.domain.port.out.BookRepository;
import com.bookshelf.domain.port.out.ShelfRepository;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class McpToolHandler {

    private final BookRepository bookRepository;
    private final ShelfRepository shelfRepository;

    public McpToolHandler(BookRepository bookRepository, ShelfRepository shelfRepository) {
        this.bookRepository = bookRepository;
        this.shelfRepository = shelfRepository;
    }

    public String checkBook(JsonObject args) {
        // ISBN branch
        if (args.has("isbn") && !args.get("isbn").isJsonNull()) {
            String isbn = args.get("isbn").getAsString().trim();
            Optional<Book> book = bookRepository.findByIsbn(isbn);
            if (book.isPresent()) {
                return "Yes, this book is on your shelf:\n" + formatBook(book.get());
            }
            return "No book found with ISBN " + isbn + ".";
        }

        // Title search branch
        if (!args.has("title") || args.get("title").isJsonNull()) {
            return "Please provide a title or ISBN to check.";
        }
        String title = args.get("title").getAsString().trim();
        String author = args.has("author") && !args.get("author").isJsonNull()
                ? args.get("author").getAsString().trim().toLowerCase(Locale.ROOT)
                : null;

        List<Book> results = bookRepository.findBySearch(title);
        if (author != null) {
            results = results.stream()
                    .filter(b -> b.getAuthor() != null
                            && b.getAuthor().toLowerCase(Locale.ROOT).contains(author))
                    .toList();
        }

        if (results.isEmpty()) {
            String msg = "No book found matching title \"" + title + "\"";
            if (author != null) {
                msg += " by " + author;
            }
            return msg + ".";
        }
        if (results.size() == 1) {
            return "Yes, this book is on your shelf:\n" + formatBook(results.get(0));
        }
        StringBuilder sb = new StringBuilder("Found " + results.size() + " matching books:\n");
        for (int i = 0; i < results.size(); i++) {
            sb.append(i + 1).append(". ").append(formatBookOneLiner(results.get(i))).append("\n");
        }
        return sb.toString().trim();
    }

    public String searchBooks(JsonObject args) {
        if (!args.has("query") || args.get("query").isJsonNull()) {
            return "Please provide a search query.";
        }
        String query = args.get("query").getAsString().trim();
        List<Book> results = bookRepository.findBySearch(query);
        if (results.isEmpty()) {
            return "No books found matching \"" + query + "\".";
        }
        StringBuilder sb = new StringBuilder("Found " + results.size() + " book(s):\n");
        for (Book b : results) {
            sb.append("- ").append(formatBookOneLiner(b)).append("\n");
        }
        return sb.toString().trim();
    }

    public String listBooks(JsonObject args) {
        String readStatusStr = args.has("readStatus") && !args.get("readStatus").isJsonNull()
                ? args.get("readStatus").getAsString().trim()
                : null;
        String genre = args.has("genre") && !args.get("genre").isJsonNull()
                ? args.get("genre").getAsString().trim()
                : null;

        List<Book> books;
        if (readStatusStr != null) {
            try {
                ReadStatus status = ReadStatus.valueOf(readStatusStr.toUpperCase(Locale.ROOT));
                books = bookRepository.findByReadStatus(status);
            } catch (IllegalArgumentException e) {
                return "Invalid readStatus: \"" + readStatusStr
                        + "\". Valid values: WANT_TO_READ, READING, FINISHED, DNF.";
            }
        } else if (genre != null) {
            books = bookRepository.findByGenre(genre);
        } else {
            books = bookRepository.findAll();
        }

        // Post-filter by genre if both readStatus and genre provided
        if (readStatusStr != null && genre != null) {
            books = books.stream()
                    .filter(b -> b.getGenre() != null
                            && b.getGenre().equalsIgnoreCase(genre))
                    .toList();
        }

        if (books.isEmpty()) {
            StringBuilder msg = new StringBuilder("No books found");
            if (readStatusStr != null) msg.append(" with status ").append(readStatusStr);
            if (genre != null) msg.append(" in genre \"").append(genre).append("\"");
            return msg.append(".").toString();
        }

        StringBuilder sb = new StringBuilder(books.size() + " book(s):\n");
        for (int i = 0; i < books.size(); i++) {
            sb.append(i + 1).append(". ").append(formatBookOneLiner(books.get(i))).append("\n");
        }
        return sb.toString().trim();
    }

    public String getBookByIsbn(JsonObject args) {
        if (!args.has("isbn") || args.get("isbn").isJsonNull()) {
            return "Please provide an ISBN.";
        }
        String isbn = args.get("isbn").getAsString().trim();
        Optional<Book> book = bookRepository.findByIsbn(isbn);
        if (book.isPresent()) {
            return formatBook(book.get());
        }
        return "No book found with ISBN " + isbn + ".";
    }

    public String getBookshelfStats(JsonObject args) {
        List<Book> all = bookRepository.findAll();
        if (all.isEmpty()) {
            return "Your bookshelf is empty.";
        }

        int total = all.size();
        int wantToRead = 0, reading = 0, finished = 0, dnf = 0;
        int ratedCount = 0;
        double ratingSum = 0;

        for (Book b : all) {
            if (b.getReadStatus() != null) {
                switch (b.getReadStatus()) {
                    case WANT_TO_READ -> wantToRead++;
                    case READING -> reading++;
                    case FINISHED -> finished++;
                    case DNF -> dnf++;
                }
            }
            if (b.getRating() != null && b.getRating() > 0) {
                ratedCount++;
                ratingSum += b.getRating();
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Your bookshelf has ").append(total).append(" book(s):\n");
        sb.append("- Want to read: ").append(wantToRead).append("\n");
        sb.append("- Reading: ").append(reading).append("\n");
        sb.append("- Finished: ").append(finished).append("\n");
        sb.append("- DNF: ").append(dnf).append("\n");
        if (ratedCount > 0) {
            sb.append("- Average rating: ")
                    .append(String.format("%.1f", ratingSum / ratedCount / 2.0))
                    .append("/5 (").append(ratedCount).append(" rated)");
        } else {
            sb.append("- No books rated yet");
        }
        return sb.toString();
    }

    /**
     * Returns the JSON array of tool definitions for tools/list response.
     */
    public JsonArray getToolDefinitions() {
        JsonArray tools = new JsonArray();
        tools.add(toolDef("check_book",
                "Check if a specific book is on the shelf by title, author, or ISBN",
                checkBookSchema()));
        tools.add(toolDef("search_books",
                "Full-text search across book titles and authors",
                searchBooksSchema()));
        tools.add(toolDef("list_books",
                "List books with optional read-status or genre filter",
                listBooksSchema()));
        tools.add(toolDef("get_book_by_isbn",
                "Look up a book by its ISBN",
                getBookByIsbnSchema()));
        tools.add(toolDef("get_bookshelf_stats",
                "Get summary statistics: counts by read status and average rating",
                emptySchema()));
        return tools;
    }

    // --- Formatting helpers ---

    private String formatBook(Book b) {
        StringBuilder sb = new StringBuilder();
        sb.append("Title: ").append(valueOrUnknown(b.getTitle())).append("\n");
        sb.append("Author: ").append(valueOrUnknown(b.getAuthor())).append("\n");
        sb.append("Status: ").append(b.getReadStatus() != null ? b.getReadStatus().name() : "unknown").append("\n");
        if (b.getRating() != null && b.getRating() > 0) {
            sb.append("Rating: ").append(displayRating(b.getRating())).append("/5\n");
        }
        if (b.getGenre() != null) sb.append("Genre: ").append(b.getGenre()).append("\n");
        if (b.getIsbn() != null) sb.append("ISBN: ").append(b.getIsbn()).append("\n");
        if (b.getPublisher() != null) sb.append("Publisher: ").append(b.getPublisher()).append("\n");
        if (b.getPublishDate() != null) sb.append("Published: ").append(b.getPublishDate()).append("\n");
        if (b.getPageCount() != null) sb.append("Pages: ").append(b.getPageCount()).append("\n");
        if (b.getReadingProgress() != null && b.getReadStatus() == ReadStatus.READING) {
            sb.append("Progress: ").append(b.getReadingProgress()).append("%\n");
        }
        return sb.toString().trim();
    }

    private String formatBookOneLiner(Book b) {
        StringBuilder sb = new StringBuilder();
        sb.append(valueOrUnknown(b.getTitle()));
        if (b.getAuthor() != null) sb.append(" by ").append(b.getAuthor());
        sb.append(" — ").append(b.getReadStatus() != null ? b.getReadStatus().name() : "unknown");
        if (b.getRating() != null && b.getRating() > 0) {
            sb.append(", rated ").append(displayRating(b.getRating())).append("/5");
        }
        if (b.getReadingProgress() != null && b.getReadStatus() == ReadStatus.READING) {
            sb.append(" (").append(b.getReadingProgress()).append("% progress)");
        }
        return sb.toString();
    }

    private String valueOrUnknown(String value) {
        return value != null ? value : "(unknown)";
    }

    /** Converts internal rating (0-10) to display string (e.g. "3.5" or "4"). */
    private String displayRating(int internal) {
        if (internal % 2 == 0) {
            return String.valueOf(internal / 2);
        }
        return String.format("%.1f", internal / 2.0);
    }

    // --- JSON Schema helpers ---

    private JsonObject toolDef(String name, String description, JsonObject inputSchema) {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", description);
        tool.add("inputSchema", inputSchema);
        return tool;
    }

    private JsonObject checkBookSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        props.add("title", stringProp("Book title to search for"));
        props.add("author", stringProp("Author name to filter by (optional)"));
        props.add("isbn", stringProp("ISBN to look up directly (optional)"));
        schema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("title");
        schema.add("required", required);
        return schema;
    }

    private JsonObject searchBooksSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        props.add("query", stringProp("Search query (matches title and author)"));
        schema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("query");
        schema.add("required", required);
        return schema;
    }

    private JsonObject listBooksSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject statusProp = stringProp("Filter by read status");
        JsonArray enumValues = new JsonArray();
        enumValues.add("WANT_TO_READ");
        enumValues.add("READING");
        enumValues.add("FINISHED");
        enumValues.add("DNF");
        statusProp.add("enum", enumValues);
        props.add("readStatus", statusProp);
        props.add("genre", stringProp("Filter by genre"));
        schema.add("properties", props);
        return schema;
    }

    private JsonObject getBookByIsbnSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        props.add("isbn", stringProp("ISBN (10 or 13 digit)"));
        schema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("isbn");
        schema.add("required", required);
        return schema;
    }

    private JsonObject emptySchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        return schema;
    }

    private JsonObject stringProp(String description) {
        JsonObject prop = new JsonObject();
        prop.addProperty("type", "string");
        prop.addProperty("description", description);
        return prop;
    }
}
