package com.bookshelf;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryBookRepository implements BookRepository {

    private final ConcurrentHashMap<UUID, Book> store = new ConcurrentHashMap<>();

    @Override
    public List<Book> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public List<Book> findByGenre(String genre) {
        return store.values().stream()
                .filter(b -> b.getGenre() != null && b.getGenre().equalsIgnoreCase(genre))
                .toList();
    }

    @Override
    public List<Book> findByReadStatus(ReadStatus readStatus) {
        return store.values().stream()
                .filter(b -> b.getReadStatus() == readStatus)
                .toList();
    }

    @Override
    public Optional<Book> findById(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public Optional<Book> findByIsbn(String isbn) {
        if (isbn == null) return Optional.empty();
        return store.values().stream()
                .filter(b -> isbn.equals(b.getIsbn()))
                .min(Comparator.comparing(Book::getCreatedAt));
    }

    @Override
    public Book save(Book book) {
        store.put(book.getId(), book);
        return book;
    }

    @Override
    public Optional<Book> update(UUID id, Book updates) {
        Book existing = store.get(id);
        if (existing == null) return Optional.empty();
        store.put(id, existing);
        return Optional.of(existing);
    }

    @Override
    public boolean delete(UUID id) {
        return store.remove(id) != null;
    }

    @Override
    public void updateFromOpenLibrary(UUID bookId, BookMetadata metadata, byte[] coverData) {
        Book book = store.get(bookId);
        if (book == null) return;

        if (metadata != null) {
            if (book.getTitle() == null && metadata.getTitle() != null) {
                book.setTitle(metadata.getTitle());
            }
            if (book.getAuthor() == null && metadata.getAuthor() != null) {
                book.setAuthor(metadata.getAuthor());
            }
            if (book.getPublisher() == null && metadata.getPublisher() != null) {
                book.setPublisher(metadata.getPublisher());
            }
            if (book.getPublishDate() == null && metadata.getPublishDate() != null) {
                book.setPublishDate(metadata.getPublishDate());
            }
            if (book.getPageCount() == null && metadata.getPageCount() != null) {
                book.setPageCount(metadata.getPageCount());
            }
            if (book.getSubjects() == null && metadata.getSubjects() != null) {
                book.setSubjects(metadata.getSubjects());
            }
            if (book.getCoverUrl() == null && metadata.getCoverUrl() != null) {
                book.setCoverUrl(metadata.getCoverUrl());
            }
            if (book.getGenre() == null && metadata.getGenre() != null) {
                book.setGenre(metadata.getGenre());
            }
        }
        if (book.getCoverData() == null && coverData != null) {
            book.setCoverData(coverData);
        }
        book.setUpdatedAt(Instant.now());
    }

    @Override
    public void clear() {
        store.clear();
    }
}
