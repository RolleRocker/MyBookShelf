package com.bookshelf.domain.port.out;

import com.bookshelf.domain.model.BookMetadata;

public interface BookMetadataFetcher {
    /** Fetch metadata from Open Library, falling back to Google Books. Merges results. */
    BookMetadata fetchByIsbn(String isbn);

    /** Download cover image by ISBN from Open Library. Returns null if unavailable. */
    byte[] fetchCoverByIsbn(String isbn);

    /** Download cover image from an arbitrary URL (e.g. Google Books thumbnail). */
    byte[] fetchCoverByUrl(String url);
}
