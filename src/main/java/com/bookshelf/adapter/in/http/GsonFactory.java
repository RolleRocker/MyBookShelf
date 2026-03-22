package com.bookshelf.adapter.in.http;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import java.time.Instant;

public final class GsonFactory {
    private static final Gson INSTANCE = new GsonBuilder()
        .serializeNulls()
        .registerTypeAdapter(Instant.class,
            (JsonSerializer<Instant>) (src, type, ctx) -> new JsonPrimitive(src.toString()))
        .create();

    private GsonFactory() {}

    public static Gson create() {
        return INSTANCE;
    }
}
