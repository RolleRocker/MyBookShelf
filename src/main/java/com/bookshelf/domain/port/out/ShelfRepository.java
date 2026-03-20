package com.bookshelf.domain.port.out;

import com.bookshelf.domain.model.Shelf;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShelfRepository {
    List<Shelf> findAll();
    Optional<Shelf> findById(UUID id);
    Shelf save(Shelf shelf);
    Optional<Shelf> update(UUID id, Shelf updates);
    boolean delete(UUID id);
    void addBook(UUID shelfId, UUID bookId);
    void removeBook(UUID shelfId, UUID bookId);
    boolean hasBook(UUID shelfId, UUID bookId);
    List<Shelf> findShelvesForBook(UUID bookId);
    void reorderShelves(List<UUID> shelfIds);
    void reorderBooks(UUID shelfId, List<UUID> bookIds);
    int getMaxShelfPosition();
    int getMaxBookPosition(UUID shelfId);
    void renumberShelfPositions();
    void removeBookFromAllShelves(UUID bookId);
    void clear();
}
