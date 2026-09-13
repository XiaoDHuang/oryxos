#!/usr/bin/env bash
# my-agent 隔离验证实例(daily-reconcile 版,012 手工验收):工作目录切到隔离工作区,
# 沙箱白名单/对账数据/webhook 全部实例级环境变量注入,不改全局 application.yaml、不动 8080 开发服务。
# 用法: [PORT=18080] bash my-agent/start-reconcile.sh
set -euo pipefail

PORT="${PORT:-18080}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
JAR="$ROOT/../oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar"

if [ ! -f "$JAR" ]; then
  echo "找不到 $JAR —— 先跑 mvn clean package" >&2
  exit 1
fi
if [ -z "${DEEPSEEK_API_KEY:-}" ]; then
  echo "提示:DEEPSEEK_API_KEY 未设置,deepseek provider 会被跳过,任务无法真实执行。" >&2
fi

# fat JAR 是 JDK 21 编译:必须实测版本,不能只信 JAVA_HOME/PATH(可能指向 17)
JAVA_BIN=""
for candidate in \
    "/c/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin/java" \
    "${JAVA_HOME:+$JAVA_HOME/bin/java}" \
    "java"; do
  [ -n "$candidate" ] || continue
  major=$("$candidate" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' || true)
  if [ -n "$major" ] && [ "$major" -ge 21 ]; then
    JAVA_BIN="$candidate"
    break
  fi
done
if [ -z "$JAVA_BIN" ]; then
  echo "找不到 JDK 21+:请把 JAVA_HOME 指到 JDK 21 再重试(fat JAR 是 21 编译的)" >&2
  exit 1
fi
echo "使用 Java: $JAVA_BIN"

export ORYXOS_ROOT="$ROOT"
export MEMORY_BACKEND=markdown
# 实例级白名单:文件放行整个隔离工作区;shell 放行 python3 解释器(课件信任边界:装带脚本的 Agent=信任作者);
# http 只放回环 webhook。均为实例环境变量,进程退出即失效。
# 本机特有:Windows 上 ShellTools 的 "bash" 会被 CreateProcess 解析成 WSL(System32 先于 PATH),
# WSL 内只有 python3;WSLENV /p 让 RECON_* 两个 Windows 路径翻译进 WSL(变成 /mnt/d/...)
export FILE_ALLOWED_PATHS="$ROOT"
export SHELL_ALLOWED_COMMANDS="ls,cat,pwd,echo,python,python3"
export HTTP_ALLOWED_DOMAINS="localhost,127.0.0.1"
export OPS_WEBHOOK_URL="http://localhost:18099/reconcile"
export RECON_ORDERS_CSV="$ROOT/recon/orders.csv"
export RECON_SETTLE_CSV="$ROOT/recon/settle.csv"
export WSLENV="RECON_ORDERS_CSV/p:RECON_SETTLE_CSV/p"

PIDFILE="$ROOT/.server.pid"
if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
  echo "已在运行: pid $(cat "$PIDFILE") —— 先 bash my-agent/stop.sh" >&2
  exit 1
fi

cd "$ROOT"   # 让模型跑的相对路径(agents/...)与文件白名单都以隔离工作区为根
nohup "$JAVA_BIN" -jar "$JAR" serve --port "$PORT" > "$ROOT/serve.log" 2>&1 &
echo $! > "$PIDFILE"
echo "my-agent serve(daily-reconcile)启动中: pid $(cat "$PIDFILE"), 端口 $PORT"
echo "日志: $ROOT/serve.log"

for _ in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:$PORT/api/v1/health" 2>/dev/null || true)
  if [ "$code" = "200" ]; then
    echo "就绪: http://localhost:$PORT/api/v1/health"
    echo "Agent 列表: curl http://localhost:$PORT/api/v1/profiles"
    echo "定时列表: curl http://localhost:$PORT/api/v1/schedules"
    exit 0
  fi
  sleep 1
done
echo "60 秒未就绪,查看 $ROOT/serve.log" >&2
exit 1
