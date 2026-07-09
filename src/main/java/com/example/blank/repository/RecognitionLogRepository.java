package com.example.blank.repository;

import com.example.blank.entity.RecognitionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RecognitionLogRepository extends JpaRepository<RecognitionLog, Integer> {

    List<RecognitionLog> findByIdentityNameOrderByRecognizedAtDesc(String identityName);

    long deleteByIdentityName(String identityName);

    @Modifying
    @Query("update RecognitionLog r set r.identityName = :newName where r.identityName = :oldName")
    int renameIdentityName(@Param("oldName") String oldName, @Param("newName") String newName);

    @Query(value = """
    SELECT
    DATE(recognized_at),
    COUNT(DISTINCT identity_name)
    FROM recognition_logs
    GROUP BY DATE(recognized_at)
    ORDER BY DATE(recognized_at)
    """, nativeQuery = true)
    List<Object[]> getDailyAttendance();
}
