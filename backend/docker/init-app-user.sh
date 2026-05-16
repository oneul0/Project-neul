#!/bin/bash
# PostgreSQL 컨테이너 최초 기동 시 한 번만 실행됩니다.
# 애플리케이션 런타임 전용 유저(DML만 허용, DDL 불가)를 생성합니다.
set -e

APP_USER="${GAK_POSTGRES_APP_USER:-gak_app}"
APP_PASSWORD="${GAK_POSTGRES_APP_PASSWORD:-gak_app_password}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    -- 런타임 유저 생성
    DO \$\$
    BEGIN
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = '$APP_USER') THEN
            CREATE USER "$APP_USER" WITH PASSWORD '$APP_PASSWORD';
        END IF;
    END
    \$\$;

    -- 기존 테이블에 DML 권한 부여
    GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO "$APP_USER";
    GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO "$APP_USER";

    -- 이후 Flyway가 새 테이블을 만들 때도 자동으로 권한 부여
    ALTER DEFAULT PRIVILEGES IN SCHEMA public
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO "$APP_USER";
    ALTER DEFAULT PRIVILEGES IN SCHEMA public
        GRANT USAGE, SELECT ON SEQUENCES TO "$APP_USER";
EOSQL

echo "[init-app-user] Runtime user '$APP_USER' ready."
