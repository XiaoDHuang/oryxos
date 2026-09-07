<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { apiGet } from '../api'

const loading = ref(true)
const error = ref('')
const page = ref(0)
const size = 20
const data = ref({ content: [], total: 0, page: 0, size })
const route = useRoute()
const selectedId = computed(() => typeof route.query.id === 'string' ? route.query.id : '')
const detail = ref(null)
const detailLoading = ref(false)
const detailError = ref('')
let detailRequest = 0

async function loadDetail() {
  const request = ++detailRequest
  detail.value = null
  detailError.value = ''
  detailLoading.value = Boolean(selectedId.value)
  if (!selectedId.value) return
  try {
    const result = await apiGet('/sessions/' + encodeURIComponent(selectedId.value))
    // 快速切换会话时,旧响应不能覆盖当前选中的历史。
    if (request === detailRequest) detail.value = result
  } catch (e) {
    if (request === detailRequest) detailError.value = e.message
  } finally {
    if (request === detailRequest) detailLoading.value = false
  }
}

watch(selectedId, loadDetail, { immediate: true })

async function load() {
  loading.value = true
  error.value = ''
  try {
    data.value = await apiGet('/sessions?page=' + page.value + '&size=' + size)
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function prevPage() {
  if (page.value > 0) {
    page.value--
    load()
  }
}

function nextPage() {
  if ((page.value + 1) * size < data.value.total) {
    page.value++
    load()
  }
}

onMounted(load)
</script>

<template>
  <h1>会话列表</h1>
  <p class="subtitle">全部入口(CLI / Web / 调度器)持久化的会话,按最后活跃倒序</p>

  <div v-if="loading" class="state-block">加载中…</div>
  <div v-else-if="error" class="state-block error">
    {{ error }}
    <br />
    <button class="retry" @click="load">重试</button>
  </div>
  <div v-else-if="!data.content.length" class="state-block">暂无会话</div>
  <template v-else>
    <div class="table-scroll">
    <table class="data">
      <thead>
        <tr>
          <th>会话 ID</th>
          <th>Profile</th>
          <th>渠道</th>
          <th>用户</th>
          <th>状态</th>
          <th>最后活跃</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="s in data.content" :key="s.sessionId">
          <td class="mono">
            <router-link :to="{ path: '/sessions', query: { id: s.sessionId } }"
              :aria-current="selectedId === s.sessionId ? 'true' : undefined">
              {{ s.sessionId }}
            </router-link>
          </td>
          <td>{{ s.profileName }}</td>
          <td>{{ s.channel }}</td>
          <td>{{ s.userId }}</td>
          <td>
            <span class="tag" :class="s.status">{{ s.status }}</span>
          </td>
          <td class="mono">{{ s.lastActiveAt }}</td>
        </tr>
      </tbody>
    </table>
    </div>
    <div class="pager">
      <button :disabled="page === 0" @click="prevPage">上一页</button>
      <span>第 {{ page + 1 }} 页 · 共 {{ data.total }} 条</span>
      <button :disabled="(page + 1) * size >= data.total" @click="nextPage">下一页</button>
    </div>
  </template>

  <section v-if="selectedId" class="session-detail" aria-live="polite" aria-label="会话历史">
    <div class="detail-heading">
      <div>
        <h2>会话历史</h2>
        <p class="mono detail-id">{{ selectedId }}</p>
      </div>
      <router-link to="/sessions">收起历史</router-link>
    </div>
    <div v-if="detailLoading" class="state-block">正在读取会话历史…</div>
    <div v-else-if="detailError" class="state-block error">
      {{ detailError }}<br />
      <button class="retry" @click="loadDetail">重试</button>
    </div>
    <div v-else-if="!detail || !detail.messages.length" class="state-block">此会话暂无消息</div>
    <template v-else>
      <p class="subtitle">{{ detail.profileName }} · {{ detail.status === 'archived' ? '已归档' : '进行中' }}
        · 共 {{ detail.totalMessages }} 条，显示最近 {{ detail.messages.length }} 条</p>
      <article v-for="(message, index) in detail.messages" :key="index" class="message-card">
        <span class="tag">{{ { user: '用户', assistant: '助手', tool: '工具', system: '系统' }[message.role] || message.role }}</span>
        <pre v-if="message.content" class="message-content">{{ message.content }}</pre>
        <div v-for="call in message.toolCalls || []" :key="call.id" class="tool-call">
          <span class="mono">{{ call.name }}</span>
          <pre class="message-content mono">{{ call.arguments }}</pre>
        </div>
      </article>
    </template>
  </section>
</template>
