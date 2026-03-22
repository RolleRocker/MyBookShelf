package com.bookshelf;

import static org.junit.jupiter.api.Assertions.*;

import com.bookshelf.adapter.out.auth.PasswordUtil;
import org.junit.jupiter.api.Test;

public class PasswordUtilTest {

    @Test
    void testHashAndVerifyRoundTrip() {
        String salt = PasswordUtil.generateSalt();
        String hash = PasswordUtil.hashPassword("myPassword123", salt);
        assertTrue(PasswordUtil.verifyPassword("myPassword123", salt, hash));
    }

    @Test
    void testWrongPasswordFails() {
        String salt = PasswordUtil.generateSalt();
        String hash = PasswordUtil.hashPassword("correctPassword", salt);
        assertFalse(PasswordUtil.verifyPassword("wrongPassword", salt, hash));
    }

    @Test
    void testDifferentSaltsProduceDifferentHashes() {
        String salt1 = PasswordUtil.generateSalt();
        String salt2 = PasswordUtil.generateSalt();
        String hash1 = PasswordUtil.hashPassword("samePassword", salt1);
        String hash2 = PasswordUtil.hashPassword("samePassword", salt2);
        assertNotEquals(hash1, hash2);
    }

    @Test
    void testSaltIsBase64Encoded() {
        String salt = PasswordUtil.generateSalt();
        assertDoesNotThrow(() -> java.util.Base64.getDecoder().decode(salt));
    }
}
