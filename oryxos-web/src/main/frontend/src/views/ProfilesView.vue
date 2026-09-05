<script setup>
import { onMounted, ref } from 'vue'
import { apiGet } from '../api'

const loading = ref(true)
const error = ref('')
const profiles = ref([])

async function load() {
  loading.value = true
  error.value = ''
  try {
    profiles.value = (await apiGet('/profiles')) || []
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <h1>Profile 列表</h1>
  <p class="subtitle">已注册的 Agent 配置(只读;通过 API 定义 Agent 是后续版本的正题)</p>

  <div v-if="loading" class="state-block">加载中…</div>
  <div v-else-if="error" class="state-block error">
    {{ error }}
    <br />
    <button class="retry" @click="load">重试</button>
  </div>
  <div v-else-if="!profiles.length" class="state-block">暂无 Profile</div>
  <table v-else class="data">
    <thead>
      <tr>
        <th>名称</th>
        <th>Agent</th>
        <th>Provider</th>
        <th>模型</th>
        <th>描述</th>
      </tr>
    </thead>
    <tbody>
      <tr v-for="p in profiles" :key="p.name">
        <td class="mono">{{ p.name }}</td>
        <td>{{ p.agentName || '—' }}</td>
        <td>{{ p.provider || '—' }}</td>
        <td class="mono">{{ p.model || '—' }}</td>
        <td>{{ p.description || '—' }}</td>
      </tr>
    </tbody>
  </table>
</template>
