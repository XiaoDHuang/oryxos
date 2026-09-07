// Vite 使用源码启动；PID 由 Node 自己写入，避免 Git Bash 的 MSYS/WINPID 转换竞态。
import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'
import { pathToFileURL } from 'node:url'

const args = new Map(process.argv.slice(2).map(arg => {
  const split = arg.indexOf('=')
  return split < 0 ? [arg, ''] : [arg.slice(0, split), arg.slice(split + 1)]
}))
const frontend = path.resolve(args.get('--frontend') || '.')
const require = createRequire(path.join(frontend, 'package.json'))
const [major, minor] = process.versions.node.split('.').map(Number)
if (!(major > 22 || (major === 22 && minor >= 12) || (major === 20 && minor >= 19))) {
  throw new Error('Vite 需要 Node 20.19+/22.12+。')
}
let viteEntry
try {
  if (!fs.existsSync(path.join(frontend, 'node_modules/vite/package.json'))) throw new Error('缺少本工程依赖')
  viteEntry = require.resolve('vite')
} catch {
  throw new Error('前端依赖未安装，请进入 oryxos-web/src/main/frontend 执行 npm ci。')
}
if (!args.has('--check-dependencies')) {
  const state = path.resolve(args.get('--state'))
  const token = args.get('--oryxos-dev-token') || ''
  const port = Number(args.get('--port'))
  const backendPort = Number(args.get('--backend-port'))
  if (!/^\d+-\d+-\d+$/.test(token) || ![port, backendPort].every(p => Number.isInteger(p) && p > 0 && p <= 65535)) {
    throw new Error('开发服务器启动参数无效。')
  }
  fs.writeFileSync(path.join(state, 'manager.token'), `${token}\n`, { mode: 0o600 })
  fs.writeFileSync(path.join(state, 'manager.pid'), `${process.pid}\n`, { mode: 0o600 })
  process.chdir(frontend)
  // 代理地址仅供 Vite 服务端使用，不采用会向浏览器公开的 VITE_ 前缀。
  process.env.ORYXOS_API_TARGET = `http://127.0.0.1:${backendPort}`
  delete process.env.DEEPSEEK_API_KEY
  delete process.env.DEEPSEEK_BASE_URL
  let server
  let closing = false
  const close = async () => {
    if (closing) return
    closing = true
    await server?.close()
    process.exit(0)
  }
  process.on('SIGINT', close)
  process.on('SIGTERM', close)
  try {
    const { createServer } = await import(pathToFileURL(viteEntry).href)
    server = await createServer({
      root: frontend,
      configFile: path.join(frontend, 'vite.config.js'),
      server: { host: '127.0.0.1', port, strictPort: true },
    })
    await server.listen()
    fs.writeFileSync(path.join(state, 'manager.ready'), `${token}\n`, { mode: 0o600 })
    server.printUrls()
  } catch (error) {
    console.error('Vite 启动失败：', error)
    await server?.close()
    process.exitCode = 1
  }
}
