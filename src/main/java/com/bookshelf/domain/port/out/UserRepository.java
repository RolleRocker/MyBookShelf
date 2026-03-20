package com.bookshelf.domain.port.out;

import com.bookshelf.domain.model.User;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository {
    Optional<User> findById(UUID id);
    Optional<User> findByUsername(String username);
    Optional<User> findByGoogleId(String googleId);
    User save(User user);
    void update(User user);
}
