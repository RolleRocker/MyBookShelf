package com.bookshelf.adapter.out.persistence;

import com.bookshelf.domain.model.Book;
import com.bookshelf.domain.model.Shelf;
import com.bookshelf.domain.port.out.BookRepository;
import com.bookshelf.domain.port.out.ShelfRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryShelfRepository implements ShelfRepository {

    private final ConcurrentHashMap<UUID, Shelf> shelves =
        new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, List<ShelfBookEntry>> shelfBooks =
        new ConcurrentHashMap<>();
    private final BookRepository bookRepository;

    record ShelfBookEntry(UUID bookId, int position, Instant addedAt) {}

    public InMemoryShelfRepository(BookRepository bookRepository) {
        this.bookRepository = bookRepository;
    }

    @Override
    public List<Shelf> findAll() {
        return shelves
            .values()
            .stream()
            .sorted(Comparator.comparingInt(Shelf::getPosition))
            .map(this::enrichForList)
            .toList();
    }

    @Override
    public Optional<Shelf> findById(UUID id) {
        Shelf shelf = shelves.get(id);
        if (shelf == null) return Optional.empty();
        return Optional.of(enrichForDetail(shelf));
    }

    @Override
    public Shelf save(Shelf shelf) {
        shelves.put(shelf.getId(), shelf);
        shelfBooks.putIfAbsent(shelf.getId(), new ArrayList<>());
        return enrichForList(shelf);
    }

    @Override
    public Optional<Shelf> update(UUID id, Shelf updates) {
        Shelf existing = shelves.get(id);
        if (existing == null) return Optional.empty();
        shelves.put(id, updates);
        return Optional.of(enrichForList(updates));
    }

    @Override
    public boolean delete(UUID id) {
        shelfBooks.remove(id);
        return shelves.remove(id) != null;
    }

    @Override
    public void addBook(UUID shelfId, UUID bookId) {
        List<ShelfBookEntry> entries = shelfBooks.computeIfAbsent(shelfId, k ->
            new ArrayList<>()
        );
        int maxPos = entries
            .stream()
            .mapToInt(ShelfBookEntry::position)
            .max()
            .orElse(-1);
        entries.add(new ShelfBookEntry(bookId, maxPos + 1, Instant.now()));
    }

    @Override
    public void removeBook(UUID shelfId, UUID bookId) {
        List<ShelfBookEntry> entries = shelfBooks.get(shelfId);
        if (entries != null) {
            entries.removeIf(e -> e.bookId().equals(bookId));
            // Renumber positions to close gaps
            entries.sort(Comparator.comparingInt(ShelfBookEntry::position));
            List<ShelfBookEntry> renumbered = new ArrayList<>();
            for (int i = 0; i < entries.size(); i++) {
                ShelfBookEntry e = entries.get(i);
                renumbered.add(new ShelfBookEntry(e.bookId(), i, e.addedAt()));
            }
            shelfBooks.put(shelfId, renumbered);
        }
    }

    @Override
    public boolean hasBook(UUID shelfId, UUID bookId) {
        List<ShelfBookEntry> entries = shelfBooks.get(shelfId);
        if (entries == null) return false;
        return entries.stream().anyMatch(e -> e.bookId().equals(bookId));
    }

    @Override
    public List<Shelf> findShelvesForBook(UUID bookId) {
        return shelfBooks.entrySet().stream()
            .filter(entry -> entry.getValue().stream()
                .anyMatch(e -> e.bookId().equals(bookId)))
            .map(entry -> shelves.get(entry.getKey()))
            .filter(shelf -> shelf != null)
            .map(shelf -> {
                // Return minimal shelf info for book enrichment
                Shelf mini = new Shelf();
                mini.setId(shelf.getId());
                mini.setName(shelf.getName());
                mini.setColor(shelf.getColor());
                return mini;
            })
            .toList();
    }

    @Override
    public void reorderShelves(List<UUID> shelfIds) {
        for (int i = 0; i < shelfIds.size(); i++) {
            Shelf shelf = shelves.get(shelfIds.get(i));
            if (shelf != null) {
                shelf.setPosition(i);
                shelf.setUpdatedAt(Instant.now());
            }
        }
    }

    @Override
    public void reorderBooks(UUID shelfId, List<UUID> bookIds) {
        List<ShelfBookEntry> entries = shelfBooks.get(shelfId);
        if (entries == null) return;

        List<ShelfBookEntry> reordered = new ArrayList<>();
        for (int i = 0; i < bookIds.size(); i++) {
            UUID bookId = bookIds.get(i);
            ShelfBookEntry original = entries
                .stream()
                .filter(e -> e.bookId().equals(bookId))
                .findFirst()
                .orElse(null);
            if (original != null) {
                reordered.add(
                    new ShelfBookEntry(bookId, i, original.addedAt())
                );
            }
        }
        shelfBooks.put(shelfId, reordered);
    }

    @Override
    public int getMaxShelfPosition() {
        return shelves
            .values()
            .stream()
            .mapToInt(Shelf::getPosition)
            .max()
            .orElse(-1);
    }

    @Override
    public int getMaxBookPosition(UUID shelfId) {
        List<ShelfBookEntry> entries = shelfBooks.get(shelfId);
        if (entries == null || entries.isEmpty()) return -1;
        return entries
            .stream()
            .mapToInt(ShelfBookEntry::position)
            .max()
            .orElse(-1);
    }

    @Override
    public void renumberShelfPositions() {
        List<Shelf> sorted = shelves
            .values()
            .stream()
            .sorted(Comparator.comparingInt(Shelf::getPosition))
            .toList();
        for (int i = 0; i < sorted.size(); i++) {
            sorted.get(i).setPosition(i);
        }
    }

    @Override
    public void clear() {
        shelves.clear();
        shelfBooks.clear();
    }

    @Override
    public void removeBookFromAllShelves(UUID bookId) {
        for (var entry : shelfBooks.entrySet()) {
            entry.getValue().removeIf(e -> e.bookId().equals(bookId));
            // Renumber
            List<ShelfBookEntry> entries = entry.getValue();
            entries.sort(Comparator.comparingInt(ShelfBookEntry::position));
            List<ShelfBookEntry> renumbered = new ArrayList<>();
            for (int i = 0; i < entries.size(); i++) {
                ShelfBookEntry e = entries.get(i);
                renumbered.add(new ShelfBookEntry(e.bookId(), i, e.addedAt()));
            }
            shelfBooks.put(entry.getKey(), renumbered);
        }
    }

    // --- Private enrichment helpers ---

    private Shelf enrichForList(Shelf shelf) {
        List<ShelfBookEntry> entries = shelfBooks.getOrDefault(
            shelf.getId(),
            List.of()
        );
        shelf.setBookCount(entries.size());
        // coverBookIds = first 4 books by position
        List<UUID> coverIds = entries
            .stream()
            .sorted(Comparator.comparingInt(ShelfBookEntry::position))
            .limit(4)
            .map(ShelfBookEntry::bookId)
            .toList();
        shelf.setCoverBookIds(coverIds);
        return shelf;
    }

    private Shelf enrichForDetail(Shelf shelf) {
        List<ShelfBookEntry> entries = shelfBooks.getOrDefault(
            shelf.getId(),
            new ArrayList<>()
        );

        // Get books sorted appropriately
        List<ShelfBookEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingInt(ShelfBookEntry::position));

        List<Book> books = sorted.stream()
            .map(entry -> bookRepository.findById(entry.bookId()))
            .flatMap(Optional::stream)
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        // Apply sort if not custom
        String sortField =
            shelf.getSortField() != null ? shelf.getSortField() : "custom";
        if (!"custom".equals(sortField)) {
            boolean desc = "desc".equalsIgnoreCase(shelf.getSortDirection());
            Comparator<Book> comparator;
            if ("rating".equals(sortField)) {
                // Unrated (0) always sorts last regardless of direction
                comparator = (x, y) -> {
                    int rx = x.getRating() != null ? x.getRating() : 0;
                    int ry = y.getRating() != null ? y.getRating() : 0;
                    if (rx == 0 && ry == 0) return 0;
                    if (rx == 0) return 1;
                    if (ry == 0) return -1;
                    return desc
                        ? Integer.compare(ry, rx)
                        : Integer.compare(rx, ry);
                };
            } else {
                comparator = switch (sortField) {
                    case "title" -> Comparator.comparing(
                        (Book b) ->
                            b.getTitle() != null
                                ? b.getTitle().toLowerCase()
                                : null,
                        Comparator.nullsLast(Comparator.naturalOrder())
                    );
                    case "author" -> Comparator.comparing(
                        (Book b) ->
                            b.getAuthor() != null
                                ? b.getAuthor().toLowerCase()
                                : null,
                        Comparator.nullsLast(Comparator.naturalOrder())
                    );
                    case "created" -> Comparator.comparing((Book b) ->
                        b.getCreatedAt() != null
                            ? b.getCreatedAt()
                            : Instant.EPOCH
                    );
                    default -> null;
                };
                if (comparator != null && desc) {
                    comparator = comparator.reversed();
                }
            }
            if (comparator != null) {
                books.sort(comparator);
            }
        }

        shelf.setBooks(books);
        shelf.setBookCount(books.size());

        // Compute stats
        shelf.setStats(computeStats(books));

        // Cover book IDs
        List<UUID> coverIds = entries
            .stream()
            .sorted(Comparator.comparingInt(ShelfBookEntry::position))
            .limit(4)
            .map(ShelfBookEntry::bookId)
            .toList();
        shelf.setCoverBookIds(coverIds);

        return shelf;
    }

    private java.util.Map<String, Object> computeStats(List<Book> books) {
        java.util.Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("bookCount", books.size());

        // Average rating (exclude unrated / rating == 0)
        List<Integer> rated = books
            .stream()
            .map(Book::getRating)
            .filter(r -> r != null && r > 0)
            .toList();
        double avgRating = rated.isEmpty()
            ? 0.0
            : rated.stream().mapToInt(Integer::intValue).average().orElse(0.0) / 2.0;
        stats.put("averageRating", avgRating);

        // Total pages
        int totalPages = books
            .stream()
            .map(Book::getPageCount)
            .filter(p -> p != null)
            .mapToInt(Integer::intValue)
            .sum();
        stats.put("totalPages", totalPages);

        // Genre breakdown
        java.util.Map<String, Integer> genreBreakdown = books.stream()
            .map(Book::getGenre)
            .filter(g -> g != null && !g.isBlank())
            .collect(java.util.stream.Collectors.toMap(
                g -> g, g -> 1, Integer::sum, java.util.LinkedHashMap::new));
        stats.put("genreBreakdown", genreBreakdown);

        return stats;
    }

    // Expose entries for validation in controller
    public List<UUID> getBookIdsOnShelf(UUID shelfId) {
        List<ShelfBookEntry> entries = shelfBooks.getOrDefault(
            shelfId,
            List.of()
        );
        return entries
            .stream()
            .sorted(Comparator.comparingInt(ShelfBookEntry::position))
            .map(ShelfBookEntry::bookId)
            .toList();
    }
}
