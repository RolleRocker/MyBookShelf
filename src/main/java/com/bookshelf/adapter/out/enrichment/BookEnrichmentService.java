package com.bookshelf.adapter.out.enrichment;

import com.bookshelf.domain.model.Book;
import com.bookshelf.domain.model.BookMetadata;
import com.bookshelf.domain.port.out.BookMetadataFetcher;
import com.bookshelf.domain.port.out.BookRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class BookEnrichmentService {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(BookEnrichmentService.class);

    private static final long RE_ENRICH_DELAY_MS = 3000;

    private final BookRepository repository;
    private final BookMetadataFetcher fetcher;
    private final ExecutorService executor;

    public BookEnrichmentService(BookRepository repository, BookMetadataFetcher fetcher) {
        this.repository = repository;
        this.fetcher = fetcher;
        this.executor = Executors.newSingleThreadExecutor();
    }

    public void enrichBookAsync(UUID bookId, String isbn) {
        executor.submit(() -> {
            try {
                BookMetadata metadata = fetcher.fetchByIsbn(isbn);
                byte[] coverData = fetcher.fetchCoverByIsbn(isbn);

                if (coverData == null && metadata != null && metadata.getCoverUrl() != null) {
                    coverData = fetcher.fetchCoverByUrl(metadata.getCoverUrl());
                }

                repository.updateFromOpenLibrary(bookId, metadata, coverData);
            } catch (Exception e) {
                logger.error("Enrichment failed for ISBN {}", isbn, e);
            }
        });
    }

    public int reEnrichAll(List<Book> books) {
        // Sort: null-title first (stuck books), then by createdAt
        List<Book> sorted = new ArrayList<>(books);
        sorted.sort(
            Comparator.comparing(
                (Book b) -> b.getTitle() != null
            ).thenComparing(
                b -> // false (null) before true
                    b.getCreatedAt() != null
                        ? b.getCreatedAt()
                        : Instant.EPOCH
            )
        );

        int count = sorted.size();
        executor.submit(() -> {
            for (int i = 0; i < sorted.size(); i++) {
                Book book = sorted.get(i);
                try {
                    BookMetadata metadata = fetcher.fetchByIsbn(book.getIsbn());
                    byte[] coverData = fetcher.fetchCoverByIsbn(book.getIsbn());

                    if (coverData == null && metadata != null && metadata.getCoverUrl() != null) {
                        coverData = fetcher.fetchCoverByUrl(metadata.getCoverUrl());
                    }

                    repository.updateFromOpenLibrary(
                        book.getId(),
                        metadata,
                        coverData
                    );
                    logger.info("Re-enriched: {} ({}/{})", book.getIsbn(), i + 1, sorted.size());
                } catch (Exception e) {
                    logger.error("Re-enrichment failed for ISBN {}", book.getIsbn(), e);
                }
                // Rate-limit: wait 3 seconds between books (except after last)
                if (i < sorted.size() - 1) {
                    try {
                        Thread.sleep(RE_ENRICH_DELAY_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            logger.info("Re-enrichment complete.");
        });
        return count;
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
