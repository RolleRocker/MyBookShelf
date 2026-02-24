package com.bookshelf;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
        assertNull(BookMetadata.deriveGenre(List.of("mathematics", "calculus", "algebra")));
    }

    // --- Genre keyword matches ---

    @Test
    void testDeriveGenre_scienceFiction() {
        assertEquals("Science Fiction", BookMetadata.deriveGenre(List.of("science fiction novels")));
    }

    @Test
    void testDeriveGenre_sciFiKeyword() {
        assertEquals("Science Fiction", BookMetadata.deriveGenre(List.of("sci-fi novels")));
    }

    @Test
    void testDeriveGenre_fantasy() {
        assertEquals("Fantasy", BookMetadata.deriveGenre(List.of("fantasy literature")));
    }

    @Test
    void testDeriveGenre_mystery() {
        assertEquals("Mystery", BookMetadata.deriveGenre(List.of("mystery fiction")));
    }

    @Test
    void testDeriveGenre_detective() {
        assertEquals("Mystery", BookMetadata.deriveGenre(List.of("detective stories")));
    }

    @Test
    void testDeriveGenre_thriller() {
        assertEquals("Thriller", BookMetadata.deriveGenre(List.of("thriller novels")));
    }

    @Test
    void testDeriveGenre_suspense() {
        assertEquals("Thriller", BookMetadata.deriveGenre(List.of("suspense fiction")));
    }

    @Test
    void testDeriveGenre_romance() {
        assertEquals("Romance", BookMetadata.deriveGenre(List.of("romance novels")));
    }

    @Test
    void testDeriveGenre_horror() {
        assertEquals("Horror", BookMetadata.deriveGenre(List.of("horror fiction")));
    }

    @Test
    void testDeriveGenre_biography() {
        assertEquals("Biography", BookMetadata.deriveGenre(List.of("biography")));
    }

    @Test
    void testDeriveGenre_autobiography() {
        assertEquals("Biography", BookMetadata.deriveGenre(List.of("autobiography")));
    }

    @Test
    void testDeriveGenre_history() {
        assertEquals("History", BookMetadata.deriveGenre(List.of("world history")));
    }

    @Test
    void testDeriveGenre_historical() {
        assertEquals("History", BookMetadata.deriveGenre(List.of("historical accounts")));
    }

    @Test
    void testDeriveGenre_philosophy() {
        assertEquals("Philosophy", BookMetadata.deriveGenre(List.of("philosophy")));
    }

    @Test
    void testDeriveGenre_poetry() {
        assertEquals("Poetry", BookMetadata.deriveGenre(List.of("poetry collections")));
    }

    @Test
    void testDeriveGenre_children() {
        assertEquals("Children's", BookMetadata.deriveGenre(List.of("children's fiction")));
    }

    @Test
    void testDeriveGenre_youngAdult() {
        assertEquals("Young Adult", BookMetadata.deriveGenre(List.of("young adult fiction")));
    }

    @Test
    void testDeriveGenre_comics() {
        assertEquals("Comics", BookMetadata.deriveGenre(List.of("comics & sequential art")));
    }

    @Test
    void testDeriveGenre_graphicNovel() {
        assertEquals("Comics", BookMetadata.deriveGenre(List.of("graphic novel")));
    }

    @Test
    void testDeriveGenre_cooking() {
        assertEquals("Cooking", BookMetadata.deriveGenre(List.of("cooking & food")));
    }

    @Test
    void testDeriveGenre_cookbook() {
        assertEquals("Cooking", BookMetadata.deriveGenre(List.of("cookbook")));
    }

    @Test
    void testDeriveGenre_science_withoutFiction() {
        // "science" alone (no "fiction") → "Science", not "Science Fiction"
        assertEquals("Science", BookMetadata.deriveGenre(List.of("popular science", "biology")));
    }

    @Test
    void testDeriveGenre_fictionAlone() {
        assertEquals("Fiction", BookMetadata.deriveGenre(List.of("fiction")));
    }

    @Test
    void testDeriveGenre_nonFiction() {
        assertEquals("Non-Fiction", BookMetadata.deriveGenre(List.of("nonfiction")));
    }

    @Test
    void testDeriveGenre_nonFictionHyphenated() {
        assertEquals("Non-Fiction", BookMetadata.deriveGenre(List.of("non-fiction")));
    }

    // --- Precedence ---

    @Test
    void testDeriveGenre_scienceFictionBeforeScience() {
        // "science fiction" must be matched before the plain "science" check
        assertEquals("Science Fiction", BookMetadata.deriveGenre(List.of("American science fiction novels")));
    }

    @Test
    void testDeriveGenre_scienceFictionBeforeFiction() {
        // "science fiction" must be matched before the plain "fiction" check
        assertEquals("Science Fiction", BookMetadata.deriveGenre(List.of("science fiction")));
    }

    @Test
    void testDeriveGenre_historicalFictionMapsToHistory() {
        // "historical" is checked before "fiction" — "historical fiction" resolves to History
        assertEquals("History", BookMetadata.deriveGenre(List.of("historical fiction")));
    }

    // --- Case insensitivity ---

    @Test
    void testDeriveGenre_caseInsensitive_uppercase() {
        assertEquals("Science Fiction", BookMetadata.deriveGenre(List.of("SCIENCE FICTION")));
    }

    @Test
    void testDeriveGenre_caseInsensitive_mixedCase() {
        assertEquals("Fantasy", BookMetadata.deriveGenre(List.of("Fantasy Literature")));
    }

    // --- Multi-subject list ---

    @Test
    void testDeriveGenre_matchAcrossSubjectList() {
        // Match found in second element; subjects are joined before scanning
        assertEquals("Horror", BookMetadata.deriveGenre(List.of("american literature", "horror stories")));
    }

    @Test
    void testDeriveGenre_firstMatchWins() {
        // "fantasy" appears before "romance" in the keyword order
        assertEquals("Fantasy",
                BookMetadata.deriveGenre(List.of("fantasy novels", "romance fiction")));
    }
}
