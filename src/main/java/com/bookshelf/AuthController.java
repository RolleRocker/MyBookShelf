package com.bookshelf;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.UUID;

public class AuthController {

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final Gson gson = new Gson();

    public AuthController(UserRepository userRepository, JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
    }

    public HttpResponse handleRegister(HttpRequest request) {
        JsonObject body;
        try {
            body = gson.fromJson(request.getBody(), JsonObject.class);
        } catch (Exception e) {
            return HttpResponse.badRequest("invalid JSON");
        }
        if (body == null) {
            return HttpResponse.badRequest("request body is required");
        }

        String username = body.has("username") && !body.get("username").isJsonNull()
            ? body.get("username").getAsString().trim() : null;
        String password = body.has("password") && !body.get("password").isJsonNull()
            ? body.get("password").getAsString() : null;

        if (username == null || username.isEmpty()) {
            return HttpResponse.badRequest("username is required");
        }
        if (username.length() < 3 || username.length() > 50) {
            return HttpResponse.badRequest("username must be 3-50 characters");
        }
        if (password == null || password.isEmpty()) {
            return HttpResponse.badRequest("password is required");
        }
        if (password.length() < 8) {
            return HttpResponse.badRequest("password must be at least 8 characters");
        }
        if (password.length() > 128) {
            return HttpResponse.badRequest("password must be at most 128 characters");
        }

        if (userRepository.findByUsername(username).isPresent()) {
            return HttpResponse.conflict("username already exists");
        }

        String salt = PasswordUtil.generateSalt();
        String hash = PasswordUtil.hashPassword(password, salt);

        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(hash);
        user.setSalt(salt);
        user.setCreatedAt(Instant.now());

        try {
            userRepository.save(user);
        } catch (DuplicateUserException e) {
            return HttpResponse.conflict("username already exists");
        }

        String token = jwtUtil.createToken(user.getId(), user.getUsername());
        return HttpResponse.created(buildAuthResponse(token, user));
    }

    public HttpResponse handleLogin(HttpRequest request) {
        JsonObject body;
        try {
            body = gson.fromJson(request.getBody(), JsonObject.class);
        } catch (Exception e) {
            return HttpResponse.badRequest("invalid JSON");
        }
        if (body == null) {
            return HttpResponse.badRequest("request body is required");
        }

        String username = body.has("username") && !body.get("username").isJsonNull()
            ? body.get("username").getAsString().trim() : null;
        String password = body.has("password") && !body.get("password").isJsonNull()
            ? body.get("password").getAsString() : null;

        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            return HttpResponse.badRequest("username and password are required");
        }

        var userOpt = userRepository.findByUsername(username);
        if (userOpt.isEmpty()) {
            return HttpResponse.unauthorized("invalid username or password");
        }

        User user = userOpt.get();
        if (!PasswordUtil.verifyPassword(password, user.getSalt(), user.getPasswordHash())) {
            return HttpResponse.unauthorized("invalid username or password");
        }

        String token = jwtUtil.createToken(user.getId(), user.getUsername());
        return HttpResponse.ok(buildAuthResponse(token, user));
    }

    private String buildAuthResponse(String token, User user) {
        JsonObject resp = new JsonObject();
        resp.addProperty("token", token);
        JsonObject userObj = new JsonObject();
        userObj.addProperty("id", user.getId().toString());
        userObj.addProperty("username", user.getUsername());
        resp.add("user", userObj);
        return gson.toJson(resp);
    }
}
