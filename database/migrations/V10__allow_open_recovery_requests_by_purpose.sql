BEGIN;

DO $$
DECLARE
    existing_index RECORD;
BEGIN
    FOR existing_index IN
        SELECT index_schema.nspname AS schema_name, index_class.relname AS index_name
        FROM pg_index index_info
        JOIN pg_class index_class ON index_class.oid = index_info.indexrelid
        JOIN pg_namespace index_schema ON index_schema.oid = index_class.relnamespace
        WHERE index_info.indrelid = 'password_reset_requests'::regclass
          AND index_info.indisunique
          AND index_info.indnkeyatts = 1
          AND index_info.indpred IS NOT NULL
          AND EXISTS (
              SELECT 1
              FROM pg_attribute attribute
              WHERE attribute.attrelid = index_info.indrelid
                AND attribute.attnum = index_info.indkey[0]
                AND attribute.attname = 'user_id'
          )
          AND pg_get_expr(index_info.indpred, index_info.indrelid) ILIKE '%status%'
          AND pg_get_expr(index_info.indpred, index_info.indrelid) ILIKE '%PENDING%'
          AND pg_get_expr(index_info.indpred, index_info.indrelid) ILIKE '%VERIFIED%'
    LOOP
        EXECUTE format('DROP INDEX %I.%I',
                existing_index.schema_name, existing_index.index_name);
    END LOOP;
END;
$$;

CREATE UNIQUE INDEX IF NOT EXISTS password_reset_open_user_purpose_idx
    ON password_reset_requests (user_id, purpose)
    WHERE status IN ('PENDING', 'VERIFIED');

COMMIT;
