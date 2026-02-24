package com.bookshelf;

import java.util.List;
import java.util.Locale;

public class BookMetadata {
    private String title;
    private String author;
    private String publisher;
    private String publishDate;
    private Integer pageCount;
    private List<String> subjects;
    private String coverUrl;
    private String genre;

    public BookMetadata() {}

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public String getPublisher() { return publisher; }
    public void setPublisher(String publisher) { this.publisher = publisher; }

    public String getPublishDate() { return publishDate; }
    public void setPublishDate(String publishDate) { this.publishDate = publishDate; }

    public Integer getPageCount() { return pageCount; }
    public void setPageCount(Integer pageCount) { this.pageCount = pageCount; }

    public List<String> getSubjects() { return subjects; }
    public void setSubjects(List<String> subjects) { this.subjects = subjects; }

    public String getCoverUrl() { return coverUrl; }
    public void setCoverUrl(String coverUrl) { this.coverUrl = coverUrl; }

    public String getGenre() { return genre; }
    public void setGenre(String genre) { this.genre = genre; }

    public static String deriveGenre(List<String> subjects) {
        if (subjects == null || subjects.isEmpty()) return null;
        String joined = String.join(" | ", subjects).toLowerCase(Locale.ROOT);
        // "science fiction" must be checked before "science" and "fiction"
        if (joined.contains("science fiction") || joined.contains("sci-fi")) return "Science Fiction";
        if (joined.contains("fantasy")) return "Fantasy";
        if (joined.contains("mystery") || joined.contains("detective")) return "Mystery";
        if (joined.contains("thriller") || joined.contains("suspense")) return "Thriller";
        if (joined.contains("romance")) return "Romance";
        if (joined.contains("horror")) return "Horror";
        if (joined.contains("biography") || joined.contains("autobiography")) return "Biography";
        if (joined.contains("history") || joined.contains("historical")) return "History";
        if (joined.contains("philosophy")) return "Philosophy";
        if (joined.contains("poetry")) return "Poetry";
        if (joined.contains("children")) return "Children's";
        if (joined.contains("young adult")) return "Young Adult";
        if (joined.contains("comics") || joined.contains("graphic novel")) return "Comics";
        if (joined.contains("cooking") || joined.contains("cookbook")) return "Cooking";
        if (joined.contains("science")) return "Science";
        if (joined.contains("nonfiction") || joined.contains("non-fiction")) return "Non-Fiction";
        if (joined.contains("fiction")) return "Fiction";
        return null;
    }
}
