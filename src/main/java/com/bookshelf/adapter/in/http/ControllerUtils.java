package com.bookshelf.adapter.in.http;

import com.bookshelf.framework.http.HttpResponse;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.util.UUID;

final class ControllerUtils {

    private ControllerUtils() {}

    static UUID parseUuid(String value) {
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static JsonObject parseJsonBody(String body) {
        if (body == null) return null;
        try {
            JsonElement parsed = JsonParser.parseString(body);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (JsonSyntaxException e) {
            return null;
        }
    }

    static HttpResponse validateStringLength(String value, String fieldName, int maxLength) {
        if (value != null && value.length() > maxLength) {
            return HttpResponse.badRequest(fieldName + " exceeds maximum length of " + maxLength);
        }
        return null;
    }
}
