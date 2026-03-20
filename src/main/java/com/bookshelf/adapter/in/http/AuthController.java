package com.bookshelf.adapter.in.http;

import com.bookshelf.adapter.out.auth.GoogleTokenVerifier;
import com.bookshelf.adapter.out.auth.PasswordUtil;
import com.bookshelf.domain.exception.DuplicateUserException;
import com.bookshelf.domain.model.User;
import com.bookshelf.domain.port.out.TokenService;
import com.bookshelf.domain.port.out.UserRepository;
import com.bookshelf.framework.http.HttpRequest;
import com.bookshelf.framework.http.HttpResponse;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.UUID;

public class AuthController {

    private final UserRepository userRepository;
    private final TokenService tokenService;
    private final GoogleTokenVerifier googleTokenVerifier;
    private final String googleClientId;
    private final Gson gson = new Gson();

    public AuthController(UserRepository userRepository, TokenService tokenService) {
        this(userRepository, tokenService, null, null);
    }

    public AuthController(UserRepository userRepository, TokenService tokenService,
                          GoogleTokenVerifier googleTokenVerifier, String googleClientId) {
        this.userRepository = userRepository;
        this.tokenService = tokenService;
        this.googleTokenVerifier = googleTokenVerifier;
        this.googleClientId = googleClientId;
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

        String token = tokenService.createToken(user.getId(), user.getUsername());
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

        String token = tokenService.createToken(user.getId(), user.getUsername());
        return HttpResponse.ok(buildAuthResponse(token, user));
    }

    public HttpResponse handleGoogleLogin(HttpRequest request) {
        if (googleTokenVerifier == null) {
            return HttpResponse.badRequest("Google login is not configured");
        }

        JsonObject body;
        try {
            body = gson.fromJson(request.getBody(), JsonObject.class);
        } catch (Exception e) {
            return HttpResponse.badRequest("invalid JSON");
        }
        if (body == null) {
            return HttpResponse.badRequest("request body is required");
        }

        String credential = body.has("credential") && !body.get("credential").isJsonNull()
            ? body.get("credential").getAsString() : null;
        if (credential == null || credential.isEmpty()) {
            return HttpResponse.badRequest("credential is required");
        }

        GoogleTokenVerifier.GoogleUserInfo googleUser = googleTokenVerifier.verify(credential);
        if (googleUser == null) {
            return HttpResponse.badRequest("invalid Google token");
        }

        // Look up existing user by Google ID
        var existingUser = userRepository.findByGoogleId(googleUser.getSub());
        if (existingUser.isPresent()) {
            User user = existingUser.get();
            String token = tokenService.createToken(user.getId(), user.getUsername());
            return HttpResponse.ok(buildAuthResponse(token, user));
        }

        // Create new user
        String username = deriveUsername(googleUser.getEmail(), googleUser.getName());
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setGoogleId(googleUser.getSub());
        user.setEmail(googleUser.getEmail());
        user.setCreatedAt(Instant.now());

        try {
            userRepository.save(user);
        } catch (DuplicateUserException e) {
            return HttpResponse.conflict("username already exists");
        }

        String token = tokenService.createToken(user.getId(), user.getUsername());
        return HttpResponse.created(buildAuthResponse(token, user));
    }

    public HttpResponse handleGetAuthConfig(HttpRequest request) {
        JsonObject config = new JsonObject();
        if (googleClientId != null && !googleClientId.isEmpty()) {
            config.addProperty("googleClientId", googleClientId);
        } else {
            config.add("googleClientId", null);
        }
        return HttpResponse.ok(gson.toJson(config));
    }

    private String deriveUsername(String email, String name) {
        String base = null;
        if (email != null && email.contains("@")) {
            base = email.substring(0, email.indexOf('@'));
        } else if (name != null && !name.isEmpty()) {
            base = name.toLowerCase().replace(' ', '.');
        }
        if (base == null || base.isEmpty()) {
            base = "user";
        }
        // Sanitize: keep alphanumeric, dots, underscores
        base = base.replaceAll("[^a-zA-Z0-9._]", "");
        if (base.length() > 45) base = base.substring(0, 45);
        if (base.length() < 3) base = base + "user";

        String candidate = base;
        int suffix = 2;
        while (userRepository.findByUsername(candidate).isPresent()) {
            candidate = base + suffix;
            suffix++;
        }
        return candidate;
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
