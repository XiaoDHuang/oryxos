#!/bin/sh
set -eu

read_secret() {
  variable="$1"
  file="$2"
  if [ ! -f "$file" ]; then
    echo "记忆服务Secret缺失" >&2
    exit 78
  fi
  value="$(cat "$file")"
  if [ -z "$value" ]; then
    echo "记忆服务Secret为空" >&2
    exit 78
  fi
  export "$variable=$value"
}

read_secret ADAPTER_DATABASE_URL /run/secrets/adapter_database_url
read_secret ADAPTER_CLIENT_BINDINGS /run/secrets/adapter_client_bindings
read_secret ADAPTER_CURSOR_SECRET /run/secrets/adapter_cursor_secret
read_secret ADAPTER_LLM_API_KEY /run/secrets/adapter_llm_api_key
read_secret ADAPTER_EMBEDDING_API_KEY /run/secrets/adapter_embedding_api_key

exec python -m uvicorn oryx_mem0.runtime:create_application --factory \
  --host 0.0.0.0 --port 8443 --workers 1 --no-access-log \
  --ssl-certfile "$ADAPTER_TLS_CERT" --ssl-keyfile "$ADAPTER_TLS_KEY"
