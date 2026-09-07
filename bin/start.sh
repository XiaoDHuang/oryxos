#!/usr/bin/env bash
# 开发模式：Spring Boot 提供 API，独立 Vite 进程提供管理台和 HMR。
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)/_server-common.sh"

if [[ "${1:-}" == --help || "${1:-}" == -h ]]; then
  printf '%s\n' '用法：bash bin/start.sh [后端端口，默认8080] [前端端口，默认5173]' \
    '需 JDK 21+、Node 20.19+/22.12+ 和已安装的前端依赖（frontend 目录执行 npm ci）。' \
    '路径覆盖：ORYXOS_CONFIG、ORYXOS_WORKSPACE、ORYXOS_JAR、ORYXOS_ENV_FILE、JAVA_HOME、NODE_BIN、JAVA_OPTS。'
  exit 0
fi
[[ $# -le 2 ]] || die '参数过多，参见 bash bin/start.sh --help。'
port=${1:-8080}
manager_port=${2:-5173}
for value in "$port" "$manager_port"; do
  [[ "$value" =~ ^[0-9]{1,5}$ ]] || die '端口必须是 1–65535 的整数。'
  ((10#$value >= 1 && 10#$value <= 65535)) || die '端口必须是 1–65535 的整数。'
done
port=$((10#$port))
manager_port=$((10#$manager_port))
[[ "$port" != "$manager_port" ]] || die '前端和后端不能使用同一端口。'
command -v curl >/dev/null || die '未找到 curl，请安装 curl 或使用 Git Bash。'
java_tools
node_tools
frontend="$repo_dir/oryxos-web/src/main/frontend"
manager_script="$repo_dir/bin/_manager.mjs"
[[ -f "$frontend/vite.config.js" ]] || die "前端工程不存在：$frontend"
"$node_bin" "$(native_path "$manager_script")" "--frontend=$(native_path "$frontend")" --check-dependencies
lock_state
cd -- "$repo_dir"
server_running=false
manager_running=false
for kind in server manager; do
  select_service "$kind"
  if read_state && process_alive; then
    instance_matches || die "$kind PID $pid 的身份不匹配，未修改任何进程。"
    expected=$port
    [[ "$kind" != manager ]] || expected=$manager_port
    [[ -f "$run_dir/$kind.port" && "$(<"$run_dir/$kind.port")" == "$expected" ]] || die "$kind 已使用其他端口，请先 stop。"
    if [[ "$kind" == manager ]]; then
      [[ -f "$run_dir/manager.backend-port" && "$(<"$run_dir/manager.backend-port")" == "$port" ]] || die '前端代理目标不同，请先 stop。'
      manager_running=true
    else server_running=true; fi
  else clear_state; fi
done
if "$server_running" && "$manager_running"; then
  printf 'Server 与 Vite Manager 已在运行。\n'
  show_addresses
  exit 0
fi
for kind in server manager; do
  active=$server_running; expected=$port
  if [[ "$kind" == manager ]]; then active=$manager_running; expected=$manager_port; fi
  if ! "$active" && (exec 3<>"/dev/tcp/127.0.0.1/$expected") 2>/dev/null; then
    die "端口 $expected 已被占用，未启动或停止任何进程。"
  fi
done

server_started=false
manager_started=false
completed=false
cleanup_new_service() {
  select_service "$1"
  if [[ -f "$pid_file" ]]; then stop_service "$1" || true;
  else
    # 尚未发布原生 PID 时，只能清理本 shell 刚创建的子任务。
    kill -TERM "$2" 2>/dev/null || true
    clear_state
  fi
}
cleanup_start() {
  local result=$?
  if ! "$completed"; then
    if "$manager_started"; then cleanup_new_service manager "$manager_shell_pid"; fi
    if "$server_started"; then cleanup_new_service server "$server_shell_pid"; fi
  fi
  rmdir -- "$run_dir/lock" 2>/dev/null || true
  return "$result"
}
trap cleanup_start EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if ! "$server_running"; then
  select_service server
  if [[ -n "${ORYXOS_JAR:-}" ]]; then jar_file=$(absolute_path "$ORYXOS_JAR");
  else
    shopt -s nullglob
    jars=()
    for candidate in "$repo_dir"/oryxos-boot/target/oryxos-boot-*.jar; do
      case "$candidate" in *-sources.jar|*-javadoc.jar|*-tests.jar) continue ;; esac
      jars+=("$candidate")
    done
    [[ ${#jars[@]} -eq 1 ]] || die '需要一个 fat JAR：先运行 mvn package；多个产物时用 ORYXOS_JAR 指定。'
    jar_file=${jars[0]}
  fi
  [[ -f "$jar_file" ]] || die "JAR 不存在：$jar_file"
  config_file=$(absolute_path "${ORYXOS_CONFIG:-config/application.yml}")
  if [[ ! -f "$config_file" ]]; then
    [[ -z "${ORYXOS_CONFIG:-}" ]] || die "指定的 YAML 配置不存在：$config_file"
    cp -n -- "$repo_dir/config/application-dev.yml.example" "$config_file"
    printf '已创建开发配置：%s\n' "$config_file"
  fi
  load_deepseek_env "$(absolute_path "${ORYXOS_ENV_FILE:-.env}")"
  workspace=$(absolute_path "${ORYXOS_WORKSPACE:-.oryxos}")
  jvm_args=()
  if [[ -n "${JAVA_OPTS:-}" ]]; then read -r -a jvm_args <<< "$JAVA_OPTS"; fi
  jvm_args+=("-Doryxos.root=$(native_path "$workspace")" -Dfile.encoding=UTF-8)
  if "$windows"; then
    mkdir -p -- "$run_dir/sockets"
    jvm_args+=("-Djdk.net.unixdomain.tmpdir=$(native_path "$run_dir/sockets")")
  fi
  if [[ ! -e "$workspace" ]]; then "$java_bin" "${jvm_args[@]}" -jar "$(native_path "$jar_file")" init; fi
  [[ -d "$workspace/profiles" ]] || die "工作区缺少 profiles 目录：$workspace"
  token="$(date +%s)-$$-$RANDOM"
  log_file="$run_dir/server-$token.log"
  nohup "$java_bin" "${jvm_args[@]}" "-Doryxos.dev.launcher=$token" \
    "-Dspring.config.additional-location=file:$(native_path "$config_file")" \
    -Dserver.address=127.0.0.1 -jar "$(native_path "$jar_file")" serve --port "$port" \
    > "$log_file" 2>&1 < /dev/null &
  shell_pid=$!
  server_shell_pid=$shell_pid
  server_started=true
  pid=$shell_pid
  if "$windows"; then
    verified=false
    for ((probe=0; probe<30; probe++)); do
      pid=$(ps -p "$shell_pid" | awk 'NR > 1 {print $4; exit}')
      if [[ "$pid" =~ ^[1-9][0-9]*$ ]] && instance_matches; then verified=true; break; fi
      sleep 0.2
    done
    if ! "$verified"; then kill -TERM "$shell_pid" 2>/dev/null || true; die "无法确认 Java PID，查看：$log_file"; fi
  fi
  printf '%s\n' "$pid" > "$pid_file"
  printf '%s\n' "$token" > "$token_file"
  printf '%s\n' "$port" > "$run_dir/server.port"
  printf '%s\n' "$log_file" > "$run_dir/server.logpath"
  disown "$shell_pid" 2>/dev/null || true
  for ((attempt=0; attempt<60; attempt++)); do
    process_alive || die "后端启动失败，日志：$log_file"
    if grep -E "Tomcat started on port $port([ (]|$)" "$log_file" >/dev/null && \
        curl --noproxy '*' --silent --fail --max-time 2 "http://127.0.0.1:$port/api/v1/health" | grep '"status":"ok"' >/dev/null; then
      server_running=true; break
    fi
    sleep 1
  done
  "$server_running" || die "后端启动超时，日志：$log_file"
fi

if ! "$manager_running"; then
  select_service manager
  token="$(date +%s)-$$-$RANDOM"
  expected_manager_token=$token
  log_file="$run_dir/manager-$token.log"
  printf '%s\n' "$manager_port" > "$run_dir/manager.port"
  printf '%s\n' "$port" > "$run_dir/manager.backend-port"
  printf '%s\n' "$log_file" > "$run_dir/manager.logpath"
  env -u DEEPSEEK_API_KEY -u DEEPSEEK_BASE_URL nohup "$node_bin" "$(native_path "$manager_script")" \
    "--frontend=$(native_path "$frontend")" "--state=$(native_path "$run_dir")" \
    "--port=$manager_port" "--backend-port=$port" "--oryxos-dev-token=$token" \
    > "$log_file" 2>&1 < /dev/null &
  manager_shell_pid=$!
  manager_started=true
  disown "$manager_shell_pid" 2>/dev/null || true
  for ((attempt=0; attempt<45; attempt++)); do
    if read_state; then
      [[ "$token" == "$expected_manager_token" ]] || die 'Vite 启动记录的标记与本次启动不匹配。'
      process_alive || die "Vite 启动失败，日志：$log_file"
      if [[ -f "$run_dir/manager.ready" && "$(<"$run_dir/manager.ready")" == "$token" ]] && \
          curl --noproxy '*' --silent --fail --max-time 2 "http://127.0.0.1:$manager_port/admin/" -o /dev/null && \
          curl --noproxy '*' --silent --fail --max-time 2 "http://127.0.0.1:$manager_port/api/v1/health" | grep '"status":"ok"' >/dev/null; then
        instance_matches || die 'Vite 进程身份校验失败。'
        manager_running=true; break
      fi
    fi
    sleep 1
  done
  "$manager_running" || die "Vite 启动超时，日志：$log_file"
fi
completed=true
printf '开发模式启动成功。\n后端日志：%s\n前端日志：%s\n' "$(<"$run_dir/server.logpath")" "$(<"$run_dir/manager.logpath")"
show_addresses
