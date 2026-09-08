// 统一 /api/v1 访问封装:解成功信封 {code,message,data};非 2xx 读错误信封 {errorCode,message} 并把 message 抛给页面。
const BASE = '/api/v1'

export async function apiGet(path) {
  return unwrap(await send('GET', path, null))
}

// 定时任务页例外(经批准的唯一写入口):POST/PUT 仅用于 /schedules 的执行与启停
export async function apiPost(path) {
  return unwrap(await send('POST', path, null))
}

export async function apiPut(path, body) {
  return unwrap(await send('PUT', path, body))
}

async function send(method, path, body) {
  let response
  try {
    response = await fetch(BASE + path, {
      method,
      headers:
        body == null
          ? { Accept: 'application/json' }
          : { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: body == null ? null : JSON.stringify(body),
    })
  } catch (networkError) {
    throw new Error('网络不可达: ' + (networkError.message || networkError))
  }
  return response
}

async function unwrap(response) {
  let payload = null
  try {
    payload = await response.json()
  } catch {
    // 非 JSON 响应(如网关错误页)按通用错误处理
  }
  if (!response.ok) {
    throw new Error(payload && payload.message ? payload.message : '请求失败(' + response.status + ')')
  }
  return payload ? payload.data : null
}
