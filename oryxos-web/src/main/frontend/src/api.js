// 统一 /api/v1 访问封装:解成功信封 {code,message,data};非 2xx 读错误信封 {errorCode,message} 并把 message 抛给页面。
const BASE = '/api/v1'

export async function apiGet(path) {
  let response
  try {
    response = await fetch(BASE + path, { headers: { Accept: 'application/json' } })
  } catch (networkError) {
    throw new Error('网络不可达: ' + (networkError.message || networkError))
  }
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
