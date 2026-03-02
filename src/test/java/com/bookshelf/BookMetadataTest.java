package com.bookshelf;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for BookMetadata.deriveGenre() — complex keyword-matching logic
 * that was previously untested.
 */
public class BookMetadataTest {

    // --- Null / empty inputs ---

    @Test
    void testDeriveGenre_nullSubjects() {
        assertNull(BookMetadata.deriveGenre(null));
    }

    @Test
    void testDeriveGenre_emptySubjects() {
        assertNull(BookMetadata.deriveGenre(List.of()));
    }

    @Test
    void testDeriveGenre_noKeywordMatch() {
        assertNull(
            BookMetadata.deriveGenre(
                List.of("mathematics", "calculus", "algebra")
            )
        );
    }

    // --- Genre keyword matches ---

    @Test
    void testDeriveGenre_scienceFiction() {
        assertEquals(
            "Science Fiction",
            BookMetadata.deriveGenre(List.of("science fiction novels"))
        );
    }

    @Test
    void testDeriveGenre_sciFiKeyword() {
        assertEquals(
            "Science Fiction",
            BookMetadata.deriveGenre(List.of("sci-fi novels"))
        );
    }

    @Test
    void testDeriveGenre_fantasy() {
        assertEquals(
            "Fantasy",
            BookMetadata.deriveGenre(List.of("fantasy literature"))
        );
    }

    @Test
    void testDeriveGenre_mystery() {
        assertEquals(
            "Mystery",
            BookMetadata.deriveGenre(List.of("mystery fiction"))
        );
    }

    @Test
    void testDeriveGenre_detective() {
        assertEquals(
            "Mystery",
            BookMetadata.deriveGenre(List.of("detective stories"))
        );
    }

    @Test
    void testDeriveGenre_thriller() {
        assertEquals(
            "Thriller",
            BookMetadata.deriveGenre(List.of("thriller novels"))
        );
    }

    @Test
    void testDeriveGenre_suspense() {
        assertEquals(
            "Thriller",
            BookMetadata.deriveGenre(List.of("suspense fiction"))
        );
    }

    @Test
    void testDeriveGenre_romance() {
        assertEquals(
            "Romance",
            BookMetadata.deriveGenre(List.of("romance novels"))
        );
    }

    @Test
    void testDeriveGenre_horror() {
        assertEquals(
            "Horror",
            BookMetadata.deriveGenre(List.of("horror fiction"))
        );
    }

    @Test
    void testDeriveGenre_biography() {
        assertEquals(
            "Biography",
            BookMetadata.deriveGenre(List.of("biography"))
        );
    }

    @Test
    void testDeriveGenre_autobiography() {
        assertEquals(
            "Biography",
            BookMetadata.deriveGenre(List.of("autobiography"))
        );
    }

    @Test
    void testDeriveGenre_history() {
        assertEquals(
            "History",
            BookMetadata.deriveGenre(List.of("world history"))
        );
    }

    @Test
    void testDeriveGenre_historical() {
        assertEquals(
            "History",
            BookMetadata.deriveGenre(List.of("historical accounts"))
        );
    }

    @Test
    void testDeriveGenre_philosophy() {
        assertEquals(
            "Philosophy",
            BookMetadata.deriveGenre(List.of("philosophy"))
        );
    }

    @Test
    void testDeriveGenre_poetry() {
        assertEquals(
            "Poetry",
            BookMetadata.deriveGenre(List.of("poetry collections"))
        );
    }

    @Test
    void testDeriveGenre_children() {
        assertEquals(
            "Children's",
            BookMetadata.deriveGenre(List.of("children's fiction"))
        );
    }

    @Test
    void testDeriveGenre_youngAdult() {
        assertEquals(
            "Young Adult",
            BookMetadata.deriveGenre(List.of("young adult fiction"))
        );
    }

    @Test
    void testDeriveGenre_comics() {
        assertEquals(
            "Comics",
            BookMetadata.deriveGenre(List.of("comics & sequential art"))
        );
    }

    @Test
    void testDeriveGenre_graphicNovel() {
        assertEquals(
            "Comics",
            BookMetadata.deriveGenre(List.of("graphic novel"))
        );
    }

    @Test
    void testDeriveGenre_cooking() {
        assertEquals(
            "Cooking",
            BookMetadata.deriveGenre(List.of("cooking & food"))
        );
    }

    @Test
    void testDeriveGenre_cookbook() {
        assertEquals("Cooking", BookMetadata.deriveGenre(List.of("cookbook")));
    }

    @Test
    void testDeriveGenre_science_withoutFiction() {
        // "science" alone (no "fiction") → "Science", not "Science Fiction"
        assertEquals(
            "Science",
            BookMetadata.deriveGenre(List.of("popular science", "biology"))
        );
    }

    @Test
    void testDeriveGenre_fictionAlone() {
        assertEquals("Fiction", BookMetadata.deriveGenre(List.of("fiction")));
    }

    @Test
    void testDeriveGenre_nonFiction() {
        assertEquals(
            "Non-Fiction",
            BookMetadata.deriveGenre(List.of("nonfiction"))
        );
    }

    @Test
    void testDeriveGenre_nonFictionHyphenated() {
        assertEquals(
            "Non-Fiction",
            BookMetadata.deriveGenre(List.of("non-fiction"))
        );
    }

    // --- Precedence ---

    @Test
    void testDeriveGenre_scienceFictionBeforeScience() {
        // "science fiction" must be matched before the plain "science" check
        assertEquals(
            "Science Fiction",
            BookMetadata.deriveGenre(List.of("American science fiction novels"))
        );
    }

    @Test
    void testDeriveGenre_scienceFictionBeforeFiction() {
        // "science fiction" must be matched before the plain "fiction" check
        assertEquals(
            "Science Fiction",
            BookMetadata.deriveGenre(List.of("science fiction"))
        );
    }

    @Test
    void testDeriveGenre_historicalFictionMapsToHistory() {
        // "historical" is checked before "fiction" — "historical fiction" resolves to History
        assertEquals(
            "History",
            BookMetadata.deriveGenre(List.of("historical fiction"))
        );
    }

    // --- Case insensitivity ---

    @Test
    void testDeriveGenre_caseInsensitive_uppercase() {
        assertEquals(
            "Science Fiction",
            BookMetadata.deriveGenre(List.of("SCIENCE FICTION"))
        );
    }

    @Test
    void testDeriveGenre_caseInsensitive_mixedCase() {
        assertEquals(
            "Fantasy",
            BookMetadata.deriveGenre(List.of("Fantasy Literature"))
        );
    }

    // --- Multi-subject list ---

    @Test
    void testDeriveGenre_matchAcrossSubjectList() {
        // Match found in second element; subjects are joined before scanning
        assertEquals(
            "Horror",
            BookMetadata.deriveGenre(
                List.of("american literature", "horror stories")
            )
        );
    }

    @Test
    void testDeriveGenre_firstMatchWins() {
        // "fantasy" appears before "romance" in the keyword order
        assertEquals(
            "Fantasy",
            BookMetadata.deriveGenre(
                List.of("fantasy novels", "romance fiction")
            )
        );
    }

    // --- Google Books response parsing ---

    @Test
    void testParseGoogleBooks_fullResponse() {
        String json = """
            {
              "kind": "books#volumes",
              "totalItems": 1,
              "items": [{
                "volumeInfo": {
                  "title": "The Witcher: The Last Wish",
                  "authors": ["Andrzej Sapkowski"],
                  "publisher": "Orbit",
                  "publishedDate": "2008-12-14",
                  "pageCount": 288,
                  "categories": ["Fiction"],
                  "imageLinks": {
                    "thumbnail": "http://books.google.com/books/content?id=abc&zoom=1"
                  }
                }
              }]
            }
            """;
        BookEnrichmentService service = new BookEnrichmentService(
            new InMemoryBookRepository()
        );
        BookMetadata m = service.parseGoogleBooksResponse(json);
        assertNotNull(m);
        assertEquals("The Witcher: The Last Wish", m.getTitle());
        assertEquals("Andrzej Sapkowski", m.getAuthor());
        assertEquals("Orbit", m.getPublisher());
        assertEquals("2008-12-14", m.getPublishDate());
        assertEquals(288, m.getPageCount());
        assertEquals(List.of("Fiction"), m.getSubjects());
        assertEquals("Fiction", m.getGenre());
        // Cover URL should be upgraded to zoom=3 and https
        assertTrue(m.getCoverUrl().contains("zoom=3"));
        assertTrue(m.getCoverUrl().startsWith("https://"));
    }

    @Test
    void testParseGoogleBooks_emptyResponse() {
        String json = "{\"kind\":\"books#volumes\",\"totalItems\":0}";
        BookEnrichmentService service = new BookEnrichmentService(
            new InMemoryBookRepository()
        );
        BookMetadata m = service.parseGoogleBooksResponse(json);
        assertNull(m);
    }

    @Test
    void testParseGoogleBooks_noImageLinks() {
        String json = """
            {
              "totalItems": 1,
              "items": [{
                "volumeInfo": {
                  "title": "Some Book",
                  "authors": ["Author Name"]
                }
              }]
            }
            """;
        BookEnrichmentService service = new BookEnrichmentService(
            new InMemoryBookRepository()
        );
        BookMetadata m = service.parseGoogleBooksResponse(json);
        assertNotNull(m);
        assertEquals("Some Book", m.getTitle());
        assertNull(m.getCoverUrl());
    }

    @Test
    void testParseGoogleBooks_minimalVolumeInfo() {
        String json = """
            {
              "totalItems": 1,
              "items": [{"volumeInfo": {"title": "Minimal"}}]
            }
            """;
        BookEnrichmentService service = new BookEnrichmentService(
            new InMemoryBookRepository()
        );
        BookMetadata m = service.parseGoogleBooksResponse(json);
        assertNotNull(m);
        assertEquals("Minimal", m.getTitle());
        assertNull(m.getAuthor());
        assertNull(m.getPublisher());
        assertNull(m.getPageCount());
    }

    // --- mergeMetadata ---

    @Test
    void testMergeMetadata_primaryNull() {
        BookMetadata fallback = new BookMetadata();
        fallback.setTitle("Fallback Title");
        BookMetadata result = BookEnrichmentService.mergeMetadata(
            null,
            fallback
        );
        assertEquals("Fallback Title", result.getTitle());
    }

    @Test
    void testMergeMetadata_fallbackNull() {
        BookMetadata primary = new BookMetadata();
        primary.setTitle("Primary Title");
        BookMetadata result = BookEnrichmentService.mergeMetadata(
            primary,
            null
        );
        assertEquals("Primary Title", result.getTitle());
    }

    @Test
    void testMergeMetadata_primaryFieldsWin() {
        BookMetadata primary = new BookMetadata();
        primary.setTitle("OL Title");
        primary.setAuthor("OL Author");

        BookMetadata fallback = new BookMetadata();
        fallback.setTitle("Google Title");
        fallback.setAuthor("Google Author");
        fallback.setPublisher("Google Publisher");

        BookMetadata result = BookEnrichmentService.mergeMetadata(
            primary,
            fallback
        );
        assertEquals("OL Title", result.getTitle());
        assertEquals("OL Author", result.getAuthor());
        assertEquals("Google Publisher", result.getPublisher());
    }

    @Test
    void testMergeMetadata_fillsAllGaps() {
        BookMetadata primary = new BookMetadata();
        // All fields null

        BookMetadata fallback = new BookMetadata();
        fallback.setTitle("Title");
        fallback.setAuthor("Author");
        fallback.setPublisher("Publisher");
        fallback.setPublishDate("2024");
        fallback.setPageCount(300);
        fallback.setSubjects(List.of("Fiction"));
        fallback.setCoverUrl("https://example.com/cover.jpg");
        fallback.setGenre("Fiction");

        BookMetadata result = BookEnrichmentService.mergeMetadata(
            primary,
            fallback
        );
        assertEquals("Title", result.getTitle());
        assertEquals("Author", result.getAuthor());
        assertEquals("Publisher", result.getPublisher());
        assertEquals("2024", result.getPublishDate());
        assertEquals(300, result.getPageCount());
        assertEquals(List.of("Fiction"), result.getSubjects());
        assertEquals("https://example.com/cover.jpg", result.getCoverUrl());
        assertEquals("Fiction", result.getGenre());
    }
}
