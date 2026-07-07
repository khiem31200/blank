package com.example.blank.repository;

import com.example.blank.entity.Identity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdentityRepository extends JpaRepository<Identity, Integer> {

    boolean existsByName(String name);
}
