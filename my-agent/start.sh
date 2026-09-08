#!/usr/bin/env bash
# my-agent 隔离验证实例启动:使用仓库刚构建的 fat JAR,独立工作区/端口,不动 8080 开发服务。
# 用法: [PORT=18080] bash my-agent/start.sh
set -euo pipefail

PORT="${PORT:-18080}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
JAR="$ROOT/../oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar"

if [ ! -f "$JAR" ]; then
  echo "找不到 $JAR —— 先跑 mvn -pl oryxos-boot -am package(或 mvn clean package)" >&2
  exit 1
fi
if [ -z "${DEEPSEEK_API_KEY:-}" ]; then
  echo "提示:DEEPSEEK_API_KEY 未设置,deepseek provider 会被跳过,任务无法真实执行。" >&2
fi

export ORYXOS_ROOT="$ROOT"
export MEMORY_BACKEND=markdown
# 白名单放行:天气源 + 回环 webhook(实例级,不改全局 application.yaml)
export HTTP_ALLOWED_DOMAINS="api.open-meteo.com,localhost"

PIDFILE="$ROOT/.server.pid"
if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
  echo "已在运行: pid $(cat "$PIDFILE")" >&2
  exit 1
fi

nohup java -jar "$JAR" serve --port "$PORT" > "$ROOT/serve.log" 2>&1 &
echo $! > "$PIDFILE"
echo "my-agent serve 启动中: pid $(cat "$PIDFILE"), 端口 $PORT"
echo "日志: $ROOT/serve.log"

for _ in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:$PORT/api/v1/health" 2>/dev/null || true)
  if [ "$code" = "200" ]; then
    echo "就绪: http://localhost:$PORT/api/v1/health"
    echo "任务列表: curl http://localhost:$PORT/api/v1/schedules"
    exit 0
  fi
  sleep 1
done
echo "60 秒未就绪,查看 $ROOT/serve.log" >&2
exit 1
