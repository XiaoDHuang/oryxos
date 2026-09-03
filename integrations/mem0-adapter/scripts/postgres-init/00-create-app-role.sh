#!/bin/sh
# 首次初始化空数据卷时创建受限应用角色：仅授予应用库的 CREATE 权限，非 superuser。
# 已初始化的数据卷不会重跑本脚本，需部署人员手工补齐同一角色。
set -eu

secret_file="/run/secrets/postgres_app_password"
if [ ! -f "$secret_file" ]; then
  echo "应用数据库角色Secret缺失" >&2
  exit 78
fi
app_password="$(cat "$secret_file")"
if [ -z "$app_password" ]; then
  echo "应用数据库角色Secret为空" >&2
  exit 78
fi

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v app_password="$app_password" -v app_db="$POSTGRES_DB" <<'SQL'
CREATE EXTENSION vector WITH VERSION '0.8.6';
CREATE ROLE oryx_mem0_app LOGIN PASSWORD :'app_password';
GRANT CREATE ON DATABASE :"app_db" TO oryx_mem0_app;
SQL
