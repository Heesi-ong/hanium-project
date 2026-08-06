package com.hanium.presentation.application.admin;

import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.domain.user.type.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "admin.emails=admin@example.com, second-admin@example.com"
})
class AdminRoleSyncRunnerTest {

    @Autowired
    private AdminRoleSyncRunner adminRoleSyncRunner;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    @Test
    void promotesExistingUserWhoseEmailIsInAdminEmails() {
        User user = userRepository.saveAndFlush(User.create("admin@example.com", "hashed-password"));
        assertThat(user.getRole()).isEqualTo(UserRole.USER);

        adminRoleSyncRunner.run(null);

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getRole()).isEqualTo(UserRole.ADMIN);
    }

    @Test
    void demotesAdminWhoseEmailIsNoLongerInAdminEmails() {
        User user = userRepository.saveAndFlush(User.create("former-admin@example.com", "hashed-password"));
        user.syncRole(UserRole.ADMIN);
        userRepository.saveAndFlush(user);

        adminRoleSyncRunner.run(null);

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getRole()).isEqualTo(UserRole.USER);
    }

    @Test
    void leavesNonAdminEmailUserAsUser() {
        User user = userRepository.saveAndFlush(User.create("member@example.com", "hashed-password"));

        adminRoleSyncRunner.run(null);

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getRole()).isEqualTo(UserRole.USER);
    }

    @Test
    void doesNotCreateAnyUserForAdminEmailThatHasNotSignedUp() {
        adminRoleSyncRunner.run(null);

        assertThat(userRepository.findByEmail("admin@example.com")).isEmpty();
    }

    @Test
    void matchesAdminEmailsCaseInsensitivelyAndTrimmed() {
        User user = userRepository.saveAndFlush(User.create("second-admin@example.com", "hashed-password"));

        adminRoleSyncRunner.run(null);

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getRole()).isEqualTo(UserRole.ADMIN);
    }
}
