#!/usr/bin/env bash
# 先验证全部进程身份，再停止 Vite 和后端，避免记录损坏时只停掉一半。
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)/_server-common.sh"
force=false
case "${1:-}" in
  '') ;;
  --force) force=true ;;
  --help|-h) printf '%s\n' '用法：bash bin/stop.sh [--force]' '同时停止 Vite Manager 与后端；保留工作区、配置和日志。'; exit 0 ;;
  *) die '未知参数，参见 bash bin/stop.sh --help。' ;;
esac
[[ $# -le 1 ]] || die '参数过多。'
java_tools
lock_state
for kind in manager server; do
  select_service "$kind"
  if read_state && process_alive; then
    instance_matches || die "$kind PID $pid 身份不匹配，未停止任何进程。"
  fi
done
result=0
stop_service manager || result=1
stop_service server || result=1
[[ "$result" == 0 ]] || die '部分进程尚未停止，保留 PID 记录供检查。'
printf 'Server 与 Vite Manager 均已停止（或原本未运行）；工作区与日志保留。'
printf '\n'
