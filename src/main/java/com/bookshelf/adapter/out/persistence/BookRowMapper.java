package com.bookshelf.adapter.out.persistence;

import com.bookshelf.domain.model.Book;
import com.bookshelf.domain.model.ReadStatus;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

final class BookRowMapper {

    private static final Gson gson = new Gson();

    private BookRowMapper() {}

    static Book mapRow(ResultSet rs) throws SQLException {
        Book book = new Book();
        book.setId(rs.getObject("id", UUID.class));
        book.setTitle(rs.getString("title"));
        book.setAuthor(rs.getString("author"));
        book.setGenre(rs.getString("genre"));
        int rating = rs.getInt("rating");
        book.setRating(rs.wasNull() ? null : rating);
        book.setIsbn(rs.getString("isbn"));
        book.setPublisher(rs.getString("publisher"));
        book.setPublishDate(rs.getString("publish_date"));
        int pageCount = rs.getInt("page_count");
        book.setPageCount(rs.wasNull() ? null : pageCount);
        String subjectsJson = rs.getString("subjects");
        if (subjectsJson != null) {
            book.setSubjects(gson.fromJson(subjectsJson, new TypeToken<List<String>>(){}.getType()));
        }
        book.setReadStatus(ReadStatus.valueOf(rs.getString("read_status")));
        book.setCoverData(rs.getBytes("cover_data"));
        book.setCoverUrl(rs.getString("cover_url"));
        int readingProgress = rs.getInt("reading_progress");
        book.setReadingProgress(rs.wasNull() ? null : readingProgress);
        book.setReview(rs.getString("review"));
        java.sql.Date startedAt = rs.getDate("started_at");
        book.setStartedAt(startedAt != null ? startedAt.toString() : null);
        java.sql.Date finishedAt = rs.getDate("finished_at");
        book.setFinishedAt(finishedAt != null ? finishedAt.toString() : null);
        Timestamp createdAt = rs.getTimestamp("created_at");
        if (createdAt != null) book.setCreatedAt(createdAt.toInstant());
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        if (updatedAt != null) book.setUpdatedAt(updatedAt.toInstant());
        return book;
    }
}
