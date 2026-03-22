package com.bookshelf.adapter.in.http;

import com.bookshelf.domain.exception.DuplicateGoalException;
import com.bookshelf.domain.model.Book;
import com.bookshelf.domain.model.ReadStatus;
import com.bookshelf.domain.model.ReadingGoal;
import com.bookshelf.domain.port.out.BookRepository;
import com.bookshelf.domain.port.out.GoalRepository;
import com.bookshelf.framework.http.HttpRequest;
import com.bookshelf.framework.http.HttpResponse;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.UUID;

public class GoalController {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(GoalController.class);

    private final GoalRepository goalRepository;
    private final BookRepository bookRepository;
    private final Gson gson;

    public GoalController(GoalRepository goalRepository, BookRepository bookRepository) {
        this.goalRepository = goalRepository;
        this.bookRepository = bookRepository;
        this.gson = GsonFactory.create();
    }

    public HttpResponse handleGetGoals(HttpRequest request) {
        try {
            List<ReadingGoal> goals = goalRepository.findAll();
            JsonArray arr = new JsonArray();
            for (ReadingGoal goal : goals) {
                arr.add(enrichGoalJson(goal));
            }
            return HttpResponse.ok(arr.toString());
        } catch (RuntimeException e) {
            logger.error("Error in handleGetGoals", e);
            return HttpResponse.internalServerError("Internal server error");
        }
    }

    public HttpResponse handleGetGoal(HttpRequest request) {
        try {
            int year;
            try {
                year = Integer.parseInt(request.getPathParams().get("year"));
            } catch (NumberFormatException e) {
                return HttpResponse.notFound("Goal not found");
            }
            return goalRepository.findByYear(year)
                .map(goal -> HttpResponse.ok(enrichGoalJson(goal).toString()))
                .orElse(HttpResponse.notFound("Goal not found"));
        } catch (RuntimeException e) {
            logger.error("Error in handleGetGoal", e);
            return HttpResponse.internalServerError("Internal server error");
        }
    }

    public HttpResponse handleCreateGoal(HttpRequest request) {
        JsonObject json;
        try {
            JsonElement parsed = JsonParser.parseString(request.getBody());
            if (!parsed.isJsonObject()) return HttpResponse.badRequest("Invalid JSON");
            json = parsed.getAsJsonObject();
        } catch (JsonSyntaxException | NullPointerException e) {
            return HttpResponse.badRequest("Invalid JSON");
        }

        if (!json.has("year") || json.get("year").isJsonNull()) {
            return HttpResponse.badRequest("year is required");
        }
        if (!json.has("target") || json.get("target").isJsonNull()) {
            return HttpResponse.badRequest("target is required");
        }

        int year, target;
        try {
            year = json.get("year").getAsInt();
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return HttpResponse.badRequest("year must be a valid integer");
        }
        try {
            target = json.get("target").getAsInt();
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return HttpResponse.badRequest("target must be a valid integer");
        }

        if (year < 2000 || year > 2100) {
            return HttpResponse.badRequest("year must be between 2000 and 2100");
        }
        if (target < 1) {
            return HttpResponse.badRequest("target must be at least 1");
        }

        try {
            ReadingGoal goal = new ReadingGoal();
            goal.setId(UUID.randomUUID());
            goal.setYear(year);
            goal.setTarget(target);
            Instant now = Instant.now();
            goal.setCreatedAt(now);
            goal.setUpdatedAt(now);

            goalRepository.save(goal);
            return HttpResponse.created(enrichGoalJson(goal).toString());
        } catch (DuplicateGoalException e) {
            return HttpResponse.conflict("Goal for year " + year + " already exists");
        } catch (RuntimeException e) {
            logger.error("Error in handleCreateGoal", e);
            return HttpResponse.internalServerError("Internal server error");
        }
    }

    public HttpResponse handleUpdateGoal(HttpRequest request) {
        int year;
        try {
            year = Integer.parseInt(request.getPathParams().get("year"));
        } catch (NumberFormatException e) {
            return HttpResponse.notFound("Goal not found");
        }

        JsonObject json;
        try {
            JsonElement parsed = JsonParser.parseString(request.getBody());
            if (!parsed.isJsonObject()) return HttpResponse.badRequest("Invalid JSON");
            json = parsed.getAsJsonObject();
        } catch (JsonSyntaxException | NullPointerException e) {
            return HttpResponse.badRequest("Invalid JSON");
        }

        if (!json.has("target") || json.get("target").isJsonNull()) {
            return HttpResponse.badRequest("target is required");
        }

        int target;
        try {
            target = json.get("target").getAsInt();
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return HttpResponse.badRequest("target must be a valid integer");
        }

        if (target < 1) {
            return HttpResponse.badRequest("target must be at least 1");
        }

        try {
            return goalRepository.update(year, target)
                .map(goal -> HttpResponse.ok(enrichGoalJson(goal).toString()))
                .orElse(HttpResponse.notFound("Goal not found"));
        } catch (RuntimeException e) {
            logger.error("Error in handleUpdateGoal", e);
            return HttpResponse.internalServerError("Internal server error");
        }
    }

    public HttpResponse handleDeleteGoal(HttpRequest request) {
        try {
            int year;
            try {
                year = Integer.parseInt(request.getPathParams().get("year"));
            } catch (NumberFormatException e) {
                return HttpResponse.notFound("Goal not found");
            }
            if (goalRepository.delete(year)) {
                return HttpResponse.noContent();
            }
            return HttpResponse.notFound("Goal not found");
        } catch (RuntimeException e) {
            logger.error("Error in handleDeleteGoal", e);
            return HttpResponse.internalServerError("Internal server error");
        }
    }

    private int countFinishedInYear(int year) {
        Instant start = Instant.parse(year + "-01-01T00:00:00Z");
        Instant end = Instant.parse((year + 1) + "-01-01T00:00:00Z");
        return (int) bookRepository.findAll().stream()
            .filter(b -> b.getReadStatus() == ReadStatus.FINISHED)
            .filter(b -> {
                // Prefer finishedAt date, fall back to createdAt
                String finishedAt = b.getFinishedAt();
                if (finishedAt != null) {
                    try {
                        int finishedYear = Integer.parseInt(finishedAt.substring(0, 4));
                        return finishedYear == year;
                    } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
                        // fall through to createdAt
                    }
                }
                return b.getCreatedAt() != null
                    && !b.getCreatedAt().isBefore(start)
                    && b.getCreatedAt().isBefore(end);
            })
            .count();
    }

    private JsonObject enrichGoalJson(ReadingGoal goal) {
        JsonObject obj = JsonParser.parseString(gson.toJson(goal)).getAsJsonObject();
        int progress = countFinishedInYear(goal.getYear());
        int percentage = Math.min(100, goal.getTarget() > 0 ? (progress * 100) / goal.getTarget() : 0);

        // Compute on-pace
        LocalDate today = LocalDate.now();
        int currentYear = today.getYear();
        boolean onPace;
        int paceDelta;
        if (goal.getYear() < currentYear) {
            // Past year
            onPace = progress >= goal.getTarget();
            paceDelta = progress - goal.getTarget();
        } else if (goal.getYear() > currentYear) {
            // Future year
            onPace = progress >= goal.getTarget();
            paceDelta = progress - goal.getTarget();
        } else {
            // Current year
            int dayOfYear = today.getDayOfYear();
            int daysInYear = Year.of(currentYear).length();
            double expectedByNow = (double) goal.getTarget() * dayOfYear / daysInYear;
            onPace = progress >= expectedByNow;
            paceDelta = progress - (int) Math.ceil(expectedByNow);
        }

        obj.addProperty("progress", progress);
        obj.addProperty("percentage", percentage);
        obj.addProperty("onPace", onPace);
        obj.addProperty("paceDelta", paceDelta);
        return obj;
    }
}
