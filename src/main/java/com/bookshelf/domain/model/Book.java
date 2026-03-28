package com.bookshelf.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class Book {
    // Identity
    private UUID id;
    // User-provided content
    private String title;
    private String author;
    private String genre;
    private Integer rating;
    private String isbn;
    private String review;
    private Integer readingProgress;
    private String startedAt;
    private String finishedAt;
    // Enrichment data
    private String publisher;
    private String publishDate;
    private Integer pageCount;
    private List<String> subjects;
    // Read tracking
    private ReadStatus readStatus;
    // Covers & metadata
    private transient byte[] coverData;
    private String coverUrl;
    private Instant createdAt;
    private transient Instant updatedAt;

    public Book() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public String getGenre() { return genre; }
    public void setGenre(String genre) { this.genre = genre; }

    public Integer getRating() { return rating; }
    public void setRating(Integer rating) { this.rating = rating; }

    public String getIsbn() { return isbn; }
    public void setIsbn(String isbn) { this.isbn = isbn; }

    public String getPublisher() { return publisher; }
    public void setPublisher(String publisher) { this.publisher = publisher; }

    public String getPublishDate() { return publishDate; }
    public void setPublishDate(String publishDate) { this.publishDate = publishDate; }

    public Integer getPageCount() { return pageCount; }
    public void setPageCount(Integer pageCount) { this.pageCount = pageCount; }

    public List<String> getSubjects() { return subjects; }
    public void setSubjects(List<String> subjects) { this.subjects = subjects; }

    public ReadStatus getReadStatus() { return readStatus; }
    public void setReadStatus(ReadStatus readStatus) { this.readStatus = readStatus; }

    public byte[] getCoverData() { return coverData; }
    public void setCoverData(byte[] coverData) { this.coverData = coverData; }

    public String getCoverUrl() { return coverUrl; }
    public void setCoverUrl(String coverUrl) { this.coverUrl = coverUrl; }

    public String getReview() { return review; }
    public void setReview(String review) { this.review = review; }

    public Integer getReadingProgress() { return readingProgress; }
    public void setReadingProgress(Integer readingProgress) { this.readingProgress = readingProgress; }

    public String getStartedAt() { return startedAt; }
    public void setStartedAt(String startedAt) { this.startedAt = startedAt; }

    public String getFinishedAt() { return finishedAt; }
    public void setFinishedAt(String finishedAt) { this.finishedAt = finishedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Book book = (Book) o;
        return Objects.equals(id, book.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Book{id=" + id + ", title='" + title + "', author='" + author + "', isbn='" + isbn + "'}";
    }

    public static class Builder {
        private final Book book = new Book();

        public Builder id(UUID id) { book.setId(id); return this; }
        public Builder title(String title) { book.setTitle(title); return this; }
        public Builder author(String author) { book.setAuthor(author); return this; }
        public Builder genre(String genre) { book.setGenre(genre); return this; }
        public Builder rating(Integer rating) { book.setRating(rating); return this; }
        public Builder isbn(String isbn) { book.setIsbn(isbn); return this; }
        public Builder publisher(String publisher) { book.setPublisher(publisher); return this; }
        public Builder publishDate(String publishDate) { book.setPublishDate(publishDate); return this; }
        public Builder pageCount(Integer pageCount) { book.setPageCount(pageCount); return this; }
        public Builder subjects(List<String> subjects) { book.setSubjects(subjects); return this; }
        public Builder readStatus(ReadStatus readStatus) { book.setReadStatus(readStatus); return this; }
        public Builder coverData(byte[] coverData) { book.setCoverData(coverData); return this; }
        public Builder coverUrl(String coverUrl) { book.setCoverUrl(coverUrl); return this; }
        public Builder review(String review) { book.setReview(review); return this; }
        public Builder readingProgress(Integer readingProgress) { book.setReadingProgress(readingProgress); return this; }
        public Builder startedAt(String startedAt) { book.setStartedAt(startedAt); return this; }
        public Builder finishedAt(String finishedAt) { book.setFinishedAt(finishedAt); return this; }
        public Builder createdAt(Instant createdAt) { book.setCreatedAt(createdAt); return this; }
        public Builder updatedAt(Instant updatedAt) { book.setUpdatedAt(updatedAt); return this; }

        public Book build() { return book; }
    }

    public static Builder builder() { return new Builder(); }
}
