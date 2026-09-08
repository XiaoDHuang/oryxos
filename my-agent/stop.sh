#!/usr/bin/env bash
# 停止 my-agent 隔离实例(只停自己记录的进程,工作区与日志保留)。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
PIDFILE="$ROOT/.server.pid"
if [ ! -f "$PIDFILE" ]; then
  echo "没有运行记录"
  exit 0
fi
PID="$(cat "$PIDFILE")"
if kill -0 "$PID" 2>/dev/null; then
  kill "$PID"
  echo "已停止 my-agent serve: pid $PID"
else
  echo "进程已不在"
fi
rm -f "$PIDFILE"
