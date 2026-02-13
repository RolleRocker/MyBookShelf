package com.bookshelf;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookRepository {
    List<Book> findAll();
    List<Book> findByGenre(String genre);
    List<Book> findByReadStatus(ReadStatus readStatus);
    Optional<Book> findById(UUID id);
    Optional<Book> findByIsbn(String isbn);
    Book save(Book book);
    Optional<Book> update(UUID id, Book updates);
    boolean delete(UUID id);
    void updateFromOpenLibrary(UUID bookId, BookMetadata metadata, byte[] coverData);
    void clear();
}
