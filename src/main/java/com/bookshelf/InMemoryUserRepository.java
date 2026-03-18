package com.bookshelf;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryUserRepository implements UserRepository {

    private final ConcurrentHashMap<UUID, User> store = new ConcurrentHashMap<>();

    @Override
    public Optional<User> findById(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return store.values().stream()
            .filter(u -> u.getUsername().equals(username))
            .findFirst();
    }

    @Override
    public User save(User user) {
        store.put(user.getId(), user);
        return user;
    }
}
