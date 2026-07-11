package com.example.blank.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter
@Setter
@Entity
@Table(name = "identities")
public class Identity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "image_url", length = 512)
    private String imageUrl;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** Gio Viet Nam: DB luu gio UTC (Python ghi) -> cong 7h khi hien thi. Hibernate bo qua (field-access). */
    public OffsetDateTime getCreatedAtVn() {
        return createdAt == null ? null : createdAt.plusHours(7);
    }
}
