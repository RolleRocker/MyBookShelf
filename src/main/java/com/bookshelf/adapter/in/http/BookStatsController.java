package com.bookshelf.adapter.in.http;

import com.bookshelf.domain.model.Book;
import com.bookshelf.domain.model.ReadStatus;
import com.bookshelf.domain.port.out.BookRepository;
import com.bookshelf.framework.http.HttpRequest;
import com.bookshelf.framework.http.HttpResponse;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

public class BookStatsController {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(BookStatsController.class);

    private final BookRepository repository;
    private final Gson gson = GsonFactory.create();

    public BookStatsController(BookRepository repository) {
        this.repository = repository;
    }

    public HttpResponse handleGetStats(HttpRequest request) {
        try {
            List<Book> allBooks = repository.findAll();
            JsonObject stats = new JsonObject();

            // totalBooks
            stats.addProperty("totalBooks", allBooks.size());

            // byStatus
            JsonObject byStatus = new JsonObject();
            for (ReadStatus s : ReadStatus.values()) {
                byStatus.addProperty(s.name(),
                    allBooks.stream().filter(b -> b.getReadStatus() == s).count());
            }
            stats.add("byStatus", byStatus);

            // byGenre
            JsonObject byGenre = new JsonObject();
            Map<String, Long> genreCounts = allBooks.stream()
                .collect(Collectors.groupingBy(
                    b -> b.getGenre() != null ? b.getGenre() : "null",
                    Collectors.counting()));
            genreCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> byGenre.addProperty(e.getKey(), e.getValue()));
            stats.add("byGenre", byGenre);

            // ratings
            JsonObject ratings = new JsonObject();
            List<Book> rated = allBooks.stream()
                .filter(b -> b.getRating() != null && b.getRating() > 0)
                .collect(Collectors.toList());
            double avg = rated.isEmpty() ? 0 :
                rated.stream().mapToDouble(b -> b.getRating() / 2.0).average().orElse(0);
            ratings.addProperty("average", Math.round(avg * 10.0) / 10.0);

            JsonObject dist = new JsonObject();
            Map<String, Long> ratingCounts = rated.stream()
                .collect(Collectors.groupingBy(b -> {
                    int r = b.getRating();
                    return r % 2 == 0 ? String.valueOf(r / 2) : String.valueOf(r / 2.0);
                }, Collectors.counting()));
            ratingCounts.entrySet().stream()
                .sorted(Comparator.comparingDouble(e -> Double.parseDouble(e.getKey())))
                .forEach(e -> dist.addProperty(e.getKey(), e.getValue()));
            ratings.add("distribution", dist);
            ratings.addProperty("unrated",
                allBooks.stream().filter(b -> b.getRating() == null || b.getRating() == 0).count());
            stats.add("ratings", ratings);

            // pages
            JsonObject pages = new JsonObject();
            List<Book> finishedWithPages = allBooks.stream()
                .filter(b -> b.getReadStatus() == ReadStatus.FINISHED
                    && b.getPageCount() != null && b.getPageCount() > 0)
                .collect(Collectors.toList());
            int totalRead = finishedWithPages.stream().mapToInt(Book::getPageCount).sum();
            pages.addProperty("totalRead", totalRead);
            pages.addProperty("averagePerBook",
                finishedWithPages.isEmpty() ? 0 : totalRead / finishedWithPages.size());
            stats.add("pages", pages);

            // finishedByMonth
            JsonObject finishedByMonth = new JsonObject();
            Map<String, Long> monthCounts = new TreeMap<>();
            allBooks.stream()
                .filter(b -> b.getReadStatus() == ReadStatus.FINISHED)
                .forEach(b -> {
                    String month;
                    if (b.getFinishedAt() != null && !b.getFinishedAt().isEmpty()) {
                        month = b.getFinishedAt().substring(0, 7); // YYYY-MM
                    } else if (b.getCreatedAt() != null) {
                        month = YearMonth.from(
                            b.getCreatedAt().atZone(java.time.ZoneId.systemDefault())).toString();
                    } else {
                        return;
                    }
                    monthCounts.merge(month, 1L, Long::sum);
                });
            monthCounts.forEach(finishedByMonth::addProperty);
            stats.add("finishedByMonth", finishedByMonth);

            // topAuthors (top 5)
            JsonArray topAuthors = new JsonArray();
            allBooks.stream()
                .filter(b -> b.getAuthor() != null && !b.getAuthor().isEmpty())
                .collect(Collectors.groupingBy(Book::getAuthor, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .forEach(e -> {
                    JsonObject a = new JsonObject();
                    a.addProperty("author", e.getKey());
                    a.addProperty("count", e.getValue());
                    topAuthors.add(a);
                });
            stats.add("topAuthors", topAuthors);

            // recentlyFinished (last 5)
            JsonArray recentlyFinished = new JsonArray();
            allBooks.stream()
                .filter(b -> b.getReadStatus() == ReadStatus.FINISHED)
                .sorted((a, b) -> {
                    String aDate = a.getFinishedAt() != null ? a.getFinishedAt() : "";
                    String bDate = b.getFinishedAt() != null ? b.getFinishedAt() : "";
                    int cmp = bDate.compareTo(aDate);
                    if (cmp != 0) return cmp;
                    if (a.getCreatedAt() != null && b.getCreatedAt() != null)
                        return b.getCreatedAt().compareTo(a.getCreatedAt());
                    return 0;
                })
                .limit(5)
                .forEach(b -> {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("id", b.getId().toString());
                    obj.addProperty("title", b.getTitle());
                    obj.addProperty("author", b.getAuthor());
                    int r = b.getRating() != null ? b.getRating() : 0;
                    if (r > 0) {
                        obj.addProperty("rating", r % 2 == 0 ? r / 2 : r / 2.0);
                    } else {
                        obj.addProperty("rating", 0);
                    }
                    if (b.getCreatedAt() != null)
                        obj.addProperty("createdAt", b.getCreatedAt().toString());
                    if (b.getFinishedAt() != null)
                        obj.addProperty("finishedAt", b.getFinishedAt());
                    recentlyFinished.add(obj);
                });
            stats.add("recentlyFinished", recentlyFinished);

            return HttpResponse.ok(gson.toJson(stats));
        } catch (RuntimeException e) {
            return HttpResponse.internalServerError("Internal server error");
        }
    }
}
