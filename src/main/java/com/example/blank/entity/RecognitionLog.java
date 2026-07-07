package com.example.blank.entity;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

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

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getIdentityName() {
        return identityName;
    }

    public void setIdentityName(String identityName) {
        this.identityName = identityName;
    }

    public Double getSimilarity() {
        return similarity;
    }

    public void setSimilarity(Double similarity) {
        this.similarity = similarity;
    }

    public OffsetDateTime getRecognizedAt() {
        return recognizedAt;
    }

    public void setRecognizedAt(OffsetDateTime recognizedAt) {
        this.recognizedAt = recognizedAt;
    }
}