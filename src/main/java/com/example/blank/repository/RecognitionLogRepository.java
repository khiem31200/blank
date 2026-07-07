package com.example.blank.repository;

import com.example.blank.entity.RecognitionLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecognitionLogRepository extends JpaRepository<RecognitionLog, Integer> {

    List<RecognitionLog> findByIdentityNameOrderByRecognizedAtDesc(String identityName);

    long deleteByIdentityName(String identityName);
}
