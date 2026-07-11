package com.example.blank.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter
@Setter
@Entity
@Table(name = "recognition_logs")
public class RecognitionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "identity_name", nullable = false)
    private String identityName;

    @Column(nullable = false)
    private Double similarity;

    @Column(name = "recognized_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime recognizedAt;

    /** Gio Viet Nam: DB luu gio UTC (Python ghi) -> cong 7h khi hien thi. Hibernate bo qua (field-access). */
    public OffsetDateTime getRecognizedAtVn() {
        return recognizedAt == null ? null : recognizedAt.plusHours(7);
    }
}
