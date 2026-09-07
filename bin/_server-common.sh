#!/usr/bin/env bash
# 启停共享状态目录和进程身份校验，防止 PID 过期后误停别的 Java 进程。

set -euo pipefail
umask 077
repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
run_dir="$repo_dir/.run/dev-server"
service=server
pid_file="$run_dir/$service.pid"
token_file="$run_dir/$service.token"
windows=false
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) windows=true ;; esac

die() { printf '%s\n' "$*" >&2; exit 1; }

shell_path() {
  if "$windows"; then cygpath -u -- "$1"; else printf '%s\n' "$1"; fi
}

native_path() {
  if "$windows"; then cygpath -m -- "$1"; else printf '%s\n' "$1"; fi
}

absolute_path() {
  local value
  value=$(shell_path "$1")
  case "$value" in /*) printf '%s\n' "$value" ;; *) printf '%s/%s\n' "$repo_dir" "$value" ;; esac
}

java_tools() {
  if [[ -n "${JAVA_HOME:-}" ]]; then
    java_bin="$(shell_path "$JAVA_HOME")/bin/java"
  else
    java_bin=$(command -v java || true)
  fi
  [[ -n "$java_bin" && -x "$java_bin" ]] || die '未找到 Java，请安装 JDK 21+ 并配置 JAVA_HOME/PATH。'
  jcmd_bin="$(dirname -- "$java_bin")/jcmd"
  [[ -x "$jcmd_bin" ]] || jcmd_bin=$(command -v jcmd || true)
  [[ -n "$jcmd_bin" && -x "$jcmd_bin" ]] || die '启停身份校验需要 JDK 的 jcmd，请使用完整 JDK 21+。'
}

node_tools() {
  if [[ -n "${NODE_BIN:-}" ]]; then node_bin=$(absolute_path "$NODE_BIN");
  else node_bin=$(command -v node || true); fi
  [[ -n "$node_bin" && -x "$node_bin" ]] || die '未找到 Node.js，请安装 Node 20.19+/22.12+ 或通过 NODE_BIN 指定。'
}

select_service() {
  service=$1
  pid_file="$run_dir/$service.pid"
  token_file="$run_dir/$service.token"
}

lock_state() {
  mkdir -p -- "$run_dir"
  mkdir -- "$run_dir/lock" 2>/dev/null || die "另一次启停正在执行：$run_dir/lock（若上次被强行中断，确认无人操作后移除此空目录）。"
  trap 'rmdir -- "$run_dir/lock" 2>/dev/null || true' EXIT
}

read_state() {
  [[ -f "$pid_file" ]] || return 1
  pid=$(<"$pid_file")
  token=''
  [[ ! -f "$token_file" ]] || token=$(<"$token_file")
  [[ "$pid" =~ ^[1-9][0-9]*$ ]] || die "PID 文件格式错误，未操作任何进程：$pid_file"
}

process_alive() {
  if "$windows"; then
    MSYS_NO_PATHCONV=1 tasklist.exe /FI "PID eq $pid" /FO CSV /NH 2>/dev/null |
      tr -d '\r' | grep -F -- "\",\"$pid\"," >/dev/null
  else
    kill -0 "$pid" 2>/dev/null && [[ "$(ps -p "$pid" -o stat= 2>/dev/null)" != Z* ]]
  fi
}

instance_matches() {
  [[ "$token" =~ ^[0-9]+-[0-9]+-[0-9]+$ ]] || return 1
  if [[ "$service" == manager ]]; then
    if "$windows"; then
      ORYXOS_CHECK_PID="$pid" ORYXOS_CHECK_TOKEN="$token" powershell.exe -NoProfile -NonInteractive -Command '
        $p = Get-CimInstance Win32_Process -Filter ("ProcessId = " + [int]$env:ORYXOS_CHECK_PID)
        if ($null -eq $p) { exit 1 }
        $pattern = "(?:^|\s|\x22)--oryxos-dev-token=" + [regex]::Escape($env:ORYXOS_CHECK_TOKEN) + "(?:\s|\x22|$)"
        if ($p.CommandLine -match $pattern -and $p.CommandLine.Contains("_manager.mjs")) { exit 0 }
        exit 1
      ' >/dev/null 2>&1
    else
      local args
      args=$(ps -p "$pid" -o args= 2>/dev/null) || return 1
      [[ " $args " == *" --oryxos-dev-token=$token "* && "$args" == *'_manager.mjs'* ]]
    fi
    return
  fi
  # 只匹配本次启动的标记，jcmd 的其他属性不输出、不保存。
  "$jcmd_bin" -J-Dsun.tools.attach.attachTimeout=3000 "$pid" VM.system_properties 2>/dev/null |
    tr -d '\r' | grep -Fx -- "oryxos.dev.launcher=$token" >/dev/null
}

clear_state() {
  rm -f -- "$pid_file" "$token_file" "$run_dir/$service.port" "$run_dir/$service.logpath" \
    "$run_dir/$service.ready" "$run_dir/$service.backend-port"
}

terminate_instance() {
  if ! instance_matches; then
    printf '进程身份不匹配或无法验证，拒绝停止 %s PID %s；保留记录供检查。\n' "$service" "$pid" >&2
    return 1
  fi
  if "$windows"; then
    # Windows 无 POSIX TERM；只结束已验证的本次 Java 及其子进程，不按端口或进程名杀进程。
    MSYS_NO_PATHCONV=1 taskkill.exe /PID "$pid" /T /F >/dev/null || return 1
  else
    kill -TERM "$pid"
  fi
}

stop_service() {
  select_service "$1"
  if ! read_state; then clear_state; return 0; fi
  if ! process_alive; then clear_state; return 0; fi
  terminate_instance || return 1
  for ((stop_attempt=0; stop_attempt<40; stop_attempt++)); do
    if ! process_alive; then clear_state; printf '已停止 %s PID %s。\n' "$service" "$pid"; return 0; fi
    sleep 1
  done
  if "${force:-false}" && ! "$windows"; then
    instance_matches || return 1
    kill -KILL "$pid"
    sleep 1
    if ! process_alive; then clear_state; return 0; fi
  fi
  printf '%s PID %s 尚未退出，记录保留。\n' "$service" "$pid" >&2
  return 1
}

show_addresses() {
  printf 'Server : http://127.0.0.1:%s/api/v1/health\n' "$port"
  printf 'Manager: http://127.0.0.1:%s/admin/ （Vite 热更新）\n' "$manager_port"
  printf 'OpenAPI: http://127.0.0.1:%s/swagger-ui.html\n' "$port"
}

load_deepseek_env() {
  local env_file=$1 line value name
  [[ -f "$env_file" ]] || return 0
  # 不 source .env：其中的命令替换、重定向等必须只是文本，不能被执行。
  while IFS= read -r line || [[ -n "$line" ]]; do
    line=${line#$'\xef\xbb\xbf'}
    line=${line%$'\r'}
    if [[ "$line" =~ ^[[:space:]]*(export[[:space:]]+)?(DEEPSEEK_API_KEY|DEEPSEEK_BASE_URL)[[:space:]]*=(.*)$ ]]; then
      name=${BASH_REMATCH[2]}
      value=${BASH_REMATCH[3]}
      value="${value#"${value%%[![:space:]]*}"}"
      value="${value%"${value##*[![:space:]]}"}"
      if [[ "$value" == \"* || "$value" == \'* ]]; then
        [[ ${#value} -ge 2 && "${value:0:1}" == "${value: -1}" ]] || die ".env 中 $name 的引号不匹配。"
        value=${value:1:${#value}-2}
      else
        value=${value%%[[:space:]]#*}
      fi
      # 已显式设置的进程环境优先，YAML 仍由 Spring 负责解释。
      if [[ -z "${!name+x}" ]]; then export "$name=$value"; fi
    fi
  done < "$env_file"
}
