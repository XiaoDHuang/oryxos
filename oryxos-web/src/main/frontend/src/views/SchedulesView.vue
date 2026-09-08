<script setup>
import { onMounted, ref } from 'vue'
import { apiGet, apiPost, apiPut } from '../api'

// 定时任务页是管理台唯一带写操作的页面(经 011 范围批准):行级立即执行与启停;其余页面保持只读。
const loading = ref(true)
const error = ref('')
const tasks = ref([])
const histories = ref({}) // taskId -> { loading, error, page, data }
const submitting = ref({}) // taskId -> true 防重复点击
const rowError = ref({}) // taskId -> message(操作失败与列表读取失败分开)

async function load() {
  loading.value = true
  error.value = ''
  try {
    tasks.value = (await apiGet('/schedules')) || []
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function loadHistory(taskId, page) {
  const current = histories.value[taskId] || { page: 0 }
  histories.value[taskId] = { ...current, loading: true, error: '', page }
  try {
    const data = await apiGet('/schedules/' + encodeURIComponent(taskId) + '/executions?page=' + page + '&size=10')
    histories.value[taskId] = { loading: false, error: '', page, data }
  } catch (e) {
    histories.value[taskId] = { loading: false, error: e.message, page, data: null }
  }
}

function toggleHistory(taskId) {
  if (histories.value[taskId] && histories.value[taskId].data) {
    histories.value[taskId] = { ...histories.value[taskId], data: null }
    return
  }
  loadHistory(taskId, 0)
}

async function runNow(task) {
  if (submitting.value[task.taskId]) {
    return
  }
  submitting.value[task.taskId] = true
  rowError.value[task.taskId] = ''
  try {
    const execution = await apiPost('/schedules/' + encodeURIComponent(task.taskId) + '/run')
    // 200 不代表业务成功:业务失败(success=false)显示失败结果
    if (execution && execution.success === false) {
      rowError.value[task.taskId] = '执行失败: ' + (execution.errorMessage || '未知原因')
    }
  } catch (e) {
    // 超时/失联:不自动重发 POST,提示经历史复核真实终态
    rowError.value[task.taskId] = e.message + '(执行结果需通过历史确认)'
  } finally {
    submitting.value[task.taskId] = false
  }
  await load()
  if (histories.value[task.taskId] && histories.value[task.taskId].data) {
    await loadHistory(task.taskId, histories.value[task.taskId].page)
  }
}

async function toggle(task) {
  if (submitting.value[task.taskId]) {
    return
  }
  submitting.value[task.taskId] = true
  rowError.value[task.taskId] = ''
  try {
    await apiPut('/schedules/' + encodeURIComponent(task.taskId), { enabled: !task.enabled })
  } catch (e) {
    rowError.value[task.taskId] = e.message
  } finally {
    submitting.value[task.taskId] = false
  }
  await load()
}

function fmtTime(iso) {
  return iso ? new Date(iso).toLocaleString() : '—'
}

function fmtDuration(ms) {
  return ms == null ? '—' : ms + 'ms'
}

onMounted(load)
</script>

<template>
  <h1>定时任务</h1>
  <p class="subtitle">规则来自各 Agent 的 Profile;此页可立即执行与启停(定义编辑需改 Profile 并重启)</p>

  <div v-if="loading" class="state-block">加载中…</div>
  <div v-else-if="error" class="state-block error">
    {{ error }}
    <br />
    <button class="retry" @click="load">重试</button>
  </div>
  <div v-else-if="!tasks.length" class="state-block">暂无定时任务</div>
  <template v-else>
    <table class="data">
      <thead>
        <tr>
          <th>任务 ID</th>
          <th>Profile</th>
          <th>规则</th>
          <th>下一次</th>
          <th>最近结果</th>
          <th>次数</th>
          <th>状态</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody v-for="t in tasks" :key="t.taskId">
        <tr>
          <td class="mono">{{ t.taskId }}</td>
          <td>{{ t.profileName }}</td>
          <td>
            <span class="mono">{{ t.cron }}</span>
            <span style="color: var(--text-3)">（{{ t.zone }}）</span>
          </td>
          <td class="mono">{{ fmtTime(t.nextRunAt) }}</td>
          <td>
            <span v-if="t.lastStatus" class="tag" :class="t.lastStatus">{{ t.lastStatus }}</span>
            <span v-else>—</span>
          </td>
          <td class="mono">{{ t.runCount }}</td>
          <td>
            <span class="dot" :class="t.available ? (t.enabled ? 'ok' : 'warn') : 'fail'"></span>
            {{ t.available ? (t.enabled ? '启用' : '停用') : '失效' }}
          </td>
          <td>
            <template v-if="t.available">
              <button
                class="pager-btn"
                :disabled="submitting[t.taskId]"
                @click="runNow(t)"
              >
                {{ submitting[t.taskId] ? '执行中…' : '立即执行' }}
              </button>
              <button
                class="pager-btn"
                :disabled="submitting[t.taskId]"
                @click="toggle(t)"
              >
                {{ t.enabled ? '停用' : '启用' }}
              </button>
            </template>
            <span v-else style="color: var(--text-3)">只读</span>
            <button class="pager-btn" @click="toggleHistory(t.taskId)">历史</button>
            <div v-if="rowError[t.taskId]" class="row-error">{{ rowError[t.taskId] }}</div>
          </td>
        </tr>
        <tr v-if="histories[t.taskId] && histories[t.taskId].data">
          <td colspan="8" style="background: var(--card)">
            <div v-if="histories[t.taskId].loading" class="state-block">历史加载中…</div>
            <div v-else-if="histories[t.taskId].error" class="state-block error">
              {{ histories[t.taskId].error }}
              <br />
              <button class="retry" @click="loadHistory(t.taskId, histories[t.taskId].page)">重试</button>
            </div>
            <div v-else-if="!histories[t.taskId].data.content.length" class="state-block">暂无执行历史</div>
            <template v-else>
              <table class="data">
                <thead>
                  <tr>
                    <th>执行 ID</th>
                    <th>会话</th>
                    <th>开始时间</th>
                    <th>结果</th>
                    <th>错误分类</th>
                    <th>耗时</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="e in histories[t.taskId].data.content" :key="e.executionId">
                    <td class="mono">{{ e.executionId }}</td>
                    <td class="mono">{{ e.sessionId }}</td>
                    <td class="mono">{{ fmtTime(e.startedAt) }}</td>
                    <td>
                      <span v-if="e.success === true" class="tag active">success</span>
                      <span v-else-if="e.success === false" class="tag" style="border-color: rgba(239,68,68,.5); color: var(--fail)">failed</span>
                      <span v-else class="tag">running</span>
                    </td>
                    <td>{{ e.errorMessage || '—' }}</td>
                    <td class="mono">{{ fmtDuration(e.durationMs) }}</td>
                  </tr>
                </tbody>
              </table>
              <div class="pager">
                <button
                  :disabled="histories[t.taskId].page === 0"
                  @click="loadHistory(t.taskId, histories[t.taskId].page - 1)"
                >
                  上一页
                </button>
                <span>
                  第 {{ histories[t.taskId].page + 1 }} 页 · 共 {{ histories[t.taskId].data.total }} 条
                </span>
                <button
                  :disabled="(histories[t.taskId].page + 1) * 10 >= histories[t.taskId].data.total"
                  @click="loadHistory(t.taskId, histories[t.taskId].page + 1)"
                >
                  下一页
                </button>
              </div>
            </template>
          </td>
        </tr>
      </tbody>
    </table>
  </template>
</template>
