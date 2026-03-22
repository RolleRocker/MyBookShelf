package com.bookshelf.adapter.in.http;

import com.bookshelf.domain.model.Book;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ShelfController {

    private static final String DEFAULT_COLOR = "#C4975A";
    private static final Set<String> VALID_SORT_FIELDS = Set.of("custom", "title", "author", "rating", "created");
    private static final Set<String> VALID_SORT_DIRECTIONS = Set.of("asc", "desc");
    private static final java.util.regex.Pattern HEX_COLOR_PATTERN =
            java.util.regex.Pattern.compile("^#[0-9a-fA-F]{6}$");

    private final ShelfRepository shelfRepository;
    private final BookRepository bookRepository;
    private final Gson gson;

    public ShelfController(ShelfRepository shelfRepository, BookRepository bookRepository) {
        this.shelfRepository = shelfRepository;
        this.bookRepository = bookRepository;
        this.gson = GsonFactory.create();
    }

    public HttpResponse handleGetShelves(HttpRequest request) {
        try {
            List<Shelf> shelves = shelfRepository.findAll();
            JsonArray arr = new JsonArray();
            for (Shelf shelf : shelves) {
                arr.add(shelfToListJson(shelf));
            }
            return HttpResponse.ok(arr.toString());
        } catch (RuntimeException e) {
            System.err.println("Error in handleGetShelves: " + e.getMessage());
            e.printStackTrace();
            return HttpResponse.internalServerError("Internal server error");
        }
    }

    public HttpResponse handleCreateShelf(HttpRequest request) {
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

        // Validate name
        String name = getStringField(json, "name");
        String nameError = validateName(name);
        if (nameError != null) return HttpResponse.badRequest(nameError);

        // Check case-insensitive uniqueness
        if (isNameTaken(name, null)) {
            return HttpResponse.conflict("Shelf name already exists");
        }

        // Validate color
        String color = getStringField(json, "color");
        if (color != null) {
            if (!HEX_COLOR_PATTERN.matcher(color).matches()) {
                return HttpResponse.badRequest("Invalid color format (must be #RRGGBB)");
            }
        } else {
            color = DEFAULT_COLOR;
        }

        // Validate sortField / sortDirection if provided
        String sortField = getStringField(json, "sortField");
        if (sortField != null && !VALID_SORT_FIELDS.contains(sortField)) {
            return HttpResponse.badRequest("Invalid sortField");
        }
        String sortDirection = getStringField(json, "sortDirection");
        if (sortDirection != null && !VALID_SORT_DIRECTIONS.contains(sortDirection)) {
            return HttpResponse.badRequest("Invalid sortDirection");
        }

        try {
            Shelf shelf = new Shelf();
            shelf.setId(UUID.randomUUID());
            shelf.setName(name.trim());
            shelf.setDescription(getStringField(json, "description"));
            shelf.setNotes(getStringField(json, "notes"));
            shelf.setColor(color);
            shelf.setSortField(sortField != null ? sortField : "custom");
            shelf.setSortDirection(sortDirection != null ? sortDirection : "asc");
            shelf.setPosition(shelfRepository.getMaxShelfPosition() + 1);

            Instant now = Instant.now();
            shelf.setCreatedAt(now);
            shelf.setUpdatedAt(now);

            Shelf saved = shelfRepository.save(shelf);
            return HttpResponse.created(shelfToListJson(saved).toString());
        } catch (RuntimeException e) {
            System.err.println("Error in handleCreateShelf: " + e.getMessage());
            e.printStackTrace();
            return HttpResponse.internalServerError("Internal server error");
        }
    }

    public HttpResponse handleGetShelf(HttpRequest request) {
        try {
            UUID id;
            try {
                id = UUID.fromString(request.getPathParams().get("id"));
            } catch (IllegalArgumentException e) {
                return HttpResponse.notFound("Shelf not found");
            }

            return shelfRepository.findById(id)
                    .map(shelf -> HttpResponse.ok(shelfToDetailJson(shelf).toString()))
                    .orElse(HttpResponse.notFound("Shelf not found"));
        } catch (RuntimeException e) {
            System.err.println("Error in handleGetShelf: " + e.getMessage());
            e.printStackTrace();
            return HttpResponse.internalServerError("Internal server error");
        }
    }

    public HttpResponse handleUpdateShelf(HttpRequest request) {
        UUID id;
        try {
            id = UUID.fromString(request.getPathParams().get("id"));
        } catch (IllegalArgumentException e) {
            return HttpResponse.notFound("Shelf not found");
        }

        var existing = shelfRepository.findById(id);
        if (existing.isEmpty()) {
            return HttpResponse.notFound("Shelf not found");
        }

        Shelf shelf = existing.get();

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

        // Validate name if provided
        if (json.has("name")) {
            String name = getStringField(json, "name");
            String nameError = validateName(name);
            if (nameError != null) return HttpResponse.badRequest(nameError);

            if (isNameTaken(name, id)) {
                return HttpResponse.conflict("Shelf name already exists");
            }
            shelf.setName(name.trim());
        }

        if (json.has("description")) {
            shelf.setDescription(json.get("description").isJsonNull() ? null : json.get("description").getAsString());
        }
        if (json.has("notes")) {
            shelf.setNotes(json.get("notes").isJsonNull() ? null : json.get("notes").getAsString());
        }
        if (json.has("color")) {
            String color = getStringField(json, "color");
            if (color != null && !HEX_COLOR_PATTERN.matcher(color).matches()) {
                return HttpResponse.badRequest("Invalid color format (must be #RRGGBB)");
            }
            shelf.setColor(color != null ? color : shelf.getColor());
        }
        if (json.has("sortField")) {
            String sf = getStringField(json, "sortField");
            if (sf != null && !VALID_SORT_FIELDS.contains(sf)) {
                return HttpResponse.badRequest("Invalid sortField");
            }
            shelf.setSortField(sf != null ? sf : shelf.getSortField());
        }
        if (json.has("sortDirection")) {
            String sd = getStringField(json, "sortDirection");
            if (sd != null && !VALID_SORT_DIRECTIONS.contains(sd)) {
                return HttpResponse.badRequest("Invalid sortDirection");
            }
            shelf.setSortDirection(sd != null ? sd : shelf.getSortDirection());
        }

        try {
            shelf.setUpdatedAt(Instant.now());
            var updated = shelfRepository.update(id, shelf);
            return updated
                    .map(s -> HttpResponse.ok(shelfToListJson(s).toString()))
                    .orElse(HttpResponse.notFound("Shelf not found"));
        } catch (RuntimeException e) {
            System.err.println("Error in handleUpdateShelf: " + e.getMessage());
            e.printStackTrace();
            return HttpResponse.internalServerError("Internal server error");
        }
    }

    public HttpResponse handleDeleteShelf(HttpRequest request) {
        try {
            UUID id;
            try {
                id = UUID.fromString(request.getPathParams().get("id"));
            } catch (IllegalArgumentException e) {
                return HttpResponse.notFound("Shelf not found");
            }

            if (shelfRepository.delete(id)) {
                shelfRepository.renumberShelfPositions();
                return HttpResponse.noContent();
            }
            return HttpResponse.notFound("Shelf not found");
        } catch (RuntimeException e) {
            System.err.println("Error in handleDeleteShelf: " + e.getMessage());
            e.printStackTrace();
            return HttpResponse.internalServerError("Internal server error");
        }
    }

    public HttpResponse handleAddBook(HttpRequest request) {
        UUID shelfId;
        try {
            shelfId = UUID.fromString(request.getPathParams().get("id"));
        } catch (IllegalArgumentException e) {
            return HttpResponse.notFound("Shelf not found");
        }

        if (shelfRepository.findById(shelfId).isEmpty()) {
            return HttpResponse.notFound("Shelf not found");
        }

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

        String bookIdStr = getStringField(json, "bookId");
        if (bookIdStr == null || bookIdStr.isBlank()) {
            return HttpResponse.badRequest("bookId is required");
        }

        UUID bookId;
        try {
            bookId = UUID.fromString(bookIdStr);
        } catch (IllegalArgumentException e) {
            return HttpResponse.badRequest("Invalid bookId format");
        }

        if (bookRepository.findById(bookId).isEmpty()) {
            return HttpResponse.notFound("Book not found");
        }

        if (shelfRepository.hasBook(shelfId, bookId)) {
            return HttpResponse.conflict("Book already on shelf");
        }

        try {
            shelfRepository.addBook(shelfId, bookId);
            int position = shelfRepository.getMaxBookPosition(shelfId);

            JsonObject response = new JsonObject();
            response.addProperty("shelfId", shelfId.toString());
            response.addProperty("bookId", bookId.toString());
            response.addProperty("position", position);
            response.addProperty("addedAt", Instant.now().toString());
            return HttpResponse.created(response.toString());
        } catch (RuntimeException e) {
            System.err.println("Error in handleAddBook: " + e.getMessage());
            e.printStackTrace();
            return HttpResponse.internalServerError("Internal server error");
        }
    }

    public HttpResponse handleRemoveBook(HttpRequest request) {
        try {
            UUID shelfId;
            try {
                shelfId = UUID.fromString(request.getPathParams().get("id"));
            } catch (IllegalArgumentException e) {
                return HttpResponse.notFound("Shelf not found");
            }

            UUID bookId;
            try {
                bookId = UUID.fromString(request.getPathParams().get("bookId"));
            } catch (IllegalArgumentException e) {
                return HttpResponse.notFound("Book not found");
            }

            if (shelfRepository.findById(shelfId).isEmpty()) {
                return HttpResponse.notFound("Shelf not found");
            }

            if (!shelfRepository.hasBook(shelfId, bookId)) {
                return HttpResponse.notFound("Book not on shelf");
            }

            shelfRepository.removeBook(shelfId, bookId);
            return HttpResponse.noContent();
        } catch (RuntimeException e) {
            System.err.println("Error in handleRemoveBook: " + e.getMessage());
            e.printStackTrace();
            return HttpResponse.internalServerError("Internal server error");
        }
    }

    public HttpResponse handleReorderShelves(HttpRequest request) {
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

        if (!json.has("order") || !json.get("order").isJsonArray()) {
            return HttpResponse.badRequest("order array is required");
        }

        JsonArray orderArr = json.getAsJsonArray("order");
        if (orderArr.isEmpty()) {
            return HttpResponse.badRequest("order array must not be empty");
        }

        List<UUID> order = new ArrayList<>();
        try {
            for (JsonElement el : orderArr) {
                order.add(UUID.fromString(el.getAsString()));
            }
        } catch (IllegalArgumentException e) {
            return HttpResponse.badRequest("Invalid UUID in order array");
        }

        // Validate: order must contain exactly all current shelf IDs
        List<Shelf> allShelves = shelfRepository.findAll();
        List<UUID> existingIds = allShelves.stream().map(Shelf::getId).toList();

        if (order.size() != existingIds.size() || !order.containsAll(existingIds)) {
            return HttpResponse.badRequest("order must contain exactly all shelf IDs");
        }

        try {
            shelfRepository.reorderShelves(order);
            List<Shelf> reordered = shelfRepository.findAll();
            JsonArray arr = new JsonArray();
            for (Shelf s : reordered) {
                arr.add(shelfToListJson(s));
            }
            return HttpResponse.ok(arr.toString());
        } catch (RuntimeException e) {
            System.err.println("Error in handleReorderShelves: " + e.getMessage());
            e.printStackTrace();
            return HttpResponse.internalServerError("Internal server error");
        }
    }

    public HttpResponse handleReorderBooks(HttpRequest request) {
        UUID shelfId;
        try {
            shelfId = UUID.fromString(request.getPathParams().get("id"));
        } catch (IllegalArgumentException e) {
            return HttpResponse.notFound("Shelf not found");
        }

        var shelfOpt = shelfRepository.findById(shelfId);
        if (shelfOpt.isEmpty()) {
            return HttpResponse.notFound("Shelf not found");
        }

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

        if (!json.has("order") || !json.get("order").isJsonArray()) {
            return HttpResponse.badRequest("order array is required");
        }

        JsonArray orderArr = json.getAsJsonArray("order");
        if (orderArr.isEmpty()) {
            return HttpResponse.badRequest("order array must not be empty");
        }

        List<UUID> order = new ArrayList<>();
        try {
            for (JsonElement el : orderArr) {
                order.add(UUID.fromString(el.getAsString()));
            }
        } catch (IllegalArgumentException e) {
            return HttpResponse.badRequest("Invalid UUID in order array");
        }

        // Validate: order must match exactly the books on this shelf
        Shelf shelf = shelfOpt.get();
        List<UUID> currentBookIds = shelf.getBooks() != null
                ? shelf.getBooks().stream().map(Book::getId).toList()
                : List.of();

        if (order.size() != currentBookIds.size() || !order.containsAll(currentBookIds)) {
            return HttpResponse.badRequest("order must contain exactly all book IDs on this shelf");
        }

        try {
            shelfRepository.reorderBooks(shelfId, order);

            // Auto-switch to custom sort
            shelf.setSortField("custom");
            shelf.setUpdatedAt(Instant.now());
            shelfRepository.update(shelfId, shelf);

            // Return updated shelf
            var updated = shelfRepository.findById(shelfId);
            return updated
                    .map(s -> HttpResponse.ok(shelfToDetailJson(s).toString()))
                    .orElse(HttpResponse.notFound("Shelf not found"));
        } catch (RuntimeException e) {
            System.err.println("Error in handleReorderBooks: " + e.getMessage());
            e.printStackTrace();
            return HttpResponse.internalServerError("Internal server error");
        }
    }

    // --- JSON serialization helpers ---

    private JsonObject shelfToListJson(Shelf shelf) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", shelf.getId().toString());
        obj.addProperty("name", shelf.getName());
        addNullableString(obj, "description", shelf.getDescription());
        obj.addProperty("color", shelf.getColor());
        obj.addProperty("sortField", shelf.getSortField());
        obj.addProperty("sortDirection", shelf.getSortDirection());
        obj.addProperty("position", shelf.getPosition());
        obj.addProperty("bookCount", shelf.getBookCount());

        JsonArray coverIds = new JsonArray();
        if (shelf.getCoverBookIds() != null) {
            for (UUID uid : shelf.getCoverBookIds()) {
                coverIds.add(uid.toString());
            }
        }
        obj.add("coverBookIds", coverIds);

        obj.addProperty("createdAt", shelf.getCreatedAt() != null ? shelf.getCreatedAt().toString() : null);
        return obj;
    }

    private JsonObject shelfToDetailJson(Shelf shelf) {
        JsonObject obj = shelfToListJson(shelf);
        addNullableString(obj, "notes", shelf.getNotes());
        obj.addProperty("updatedAt", shelf.getUpdatedAt() != null ? shelf.getUpdatedAt().toString() : null);

        // Books array
        JsonArray booksArr = new JsonArray();
        if (shelf.getBooks() != null) {
            for (Book book : shelf.getBooks()) {
                booksArr.add(JsonParser.parseString(gson.toJson(book)));
            }
        }
        obj.add("books", booksArr);

        // Stats
        if (shelf.getStats() != null) {
            obj.add("stats", JsonParser.parseString(gson.toJson(shelf.getStats())));
        }

        return obj;
    }

    private void addNullableString(JsonObject obj, String key, String value) {
        if (value != null) {
            obj.addProperty(key, value);
        } else {
            obj.add(key, com.google.gson.JsonNull.INSTANCE);
        }
    }

    private String getStringField(JsonObject json, String field) {
        if (!json.has(field) || json.get(field).isJsonNull()) {
            return null;
        }
        return json.get(field).getAsString();
    }

    private String validateName(String name) {
        if (name == null || name.isBlank()) {
            return "name is required";
        }
        if (name.trim().length() > 100) {
            return "name must be 100 characters or fewer";
        }
        return null;
    }

    private boolean isNameTaken(String name, UUID excludeId) {
        String lower = name.trim().toLowerCase();
        return shelfRepository.findAll().stream()
                .anyMatch(s -> s.getName().toLowerCase().equals(lower)
                        && (excludeId == null || !s.getId().equals(excludeId)));
    }
}
