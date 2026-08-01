package com.hanium.presentation.domain.user.repository;

import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.type.UserRole;
import com.hanium.presentation.domain.user.type.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from User user where user.email = :email")
    Optional<User> findByEmailForUpdate(@Param("email") String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from User user where user.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    boolean existsByEmail(String email);

    Page<User> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("""
            select user
            from User user
            where (:email is null or lower(user.email) like lower(concat('%', :email, '%')))
              and (:status is null or user.status = :status)
              and (:role is null or user.role = :role)
            order by user.createdAt desc
            """)
    Page<User> searchForAdmin(
            @Param("email") String email,
            @Param("status") UserStatus status,
            @Param("role") UserRole role,
            Pageable pageable
    );

    long countByRole(UserRole role);
}
