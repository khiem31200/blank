package com.example.blank.config;

import org.hibernate.boot.model.relational.Namespace;
import org.hibernate.boot.model.relational.Sequence;
import org.hibernate.mapping.Table;
import org.hibernate.tool.schema.spi.SchemaFilter;
import org.hibernate.tool.schema.spi.SchemaFilterProvider;

/**
 * Chi cho Hibernate quan ly schema cua bang `devices` (bang do view-service so huu).
 *
 * `identities` / `recognition_logs` thuoc quyen so huu cua backend-service Python (db_utils.py).
 * Truoc day ddl-auto=update de Hibernate tao/sua ca 2 bang nay -> schema drift:
 *  - recognition_logs bi tao thieu DEFAULT now() cho recognized_at -> backend insert loi NOT NULL
 *    (bug report 2026-07-11, da hotfix ALTER TABLE tren RDS)
 *  - neu DB trong, Hibernate se tao identities THIEU cot embedding VECTOR(512) (entity khong map)
 *    -> enroll cua backend chet
 *
 * Voi filter nay: create/update/validate/drop cua Hibernate chi ap len `devices`;
 * 2 bang cua Python duoc entity doc/ghi binh thuong nhung DDL hoan toan do db_utils.py quyet.
 * Dang ky qua: spring.jpa.properties.hibernate.hbm2ddl.schema_filter_provider
 */
public class DevicesOnlySchemaFilterProvider implements SchemaFilterProvider {

    private static final SchemaFilter DEVICES_ONLY = new SchemaFilter() {
        @Override public boolean includeNamespace(Namespace namespace) { return true; }
        @Override public boolean includeTable(Table table) { return "devices".equalsIgnoreCase(table.getName()); }
        @Override public boolean includeSequence(Sequence sequence) { return false; } // devices khong dung sequence
    };

    @Override public SchemaFilter getCreateFilter()    { return DEVICES_ONLY; }
    @Override public SchemaFilter getDropFilter()      { return DEVICES_ONLY; }
    @Override public SchemaFilter getMigrateFilter()   { return DEVICES_ONLY; }
    @Override public SchemaFilter getValidateFilter()  { return DEVICES_ONLY; }
    @Override public SchemaFilter getTruncatorFilter() { return DEVICES_ONLY; }
}
