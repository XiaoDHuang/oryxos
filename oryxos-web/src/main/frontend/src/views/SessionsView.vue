<script setup>
import { onMounted, ref } from 'vue'
import { apiGet } from '../api'

const loading = ref(true)
const error = ref('')
const page = ref(0)
const size = 20
const data = ref({ content: [], total: 0, page: 0, size })

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
          <td class="mono">{{ s.sessionId }}</td>
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
    <div class="pager">
      <button :disabled="page === 0" @click="prevPage">上一页</button>
      <span>第 {{ page + 1 }} 页 · 共 {{ data.total }} 条</span>
      <button :disabled="(page + 1) * size >= data.total" @click="nextPage">下一页</button>
    </div>
  </template>
</template>
