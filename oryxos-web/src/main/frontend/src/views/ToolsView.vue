<script setup>
import { onMounted, ref } from 'vue'
import { apiGet } from '../api'

const loading = ref(true)
const error = ref('')
const tools = ref([])

async function load() {
  loading.value = true
  error.value = ''
  try {
    tools.value = (await apiGet('/tools')) || []
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <h1>Tool 列表</h1>
  <p class="subtitle">已注册的工具(内置 / MCP / 插件统一包装为 OryxTool)</p>

  <div v-if="loading" class="state-block">加载中…</div>
  <div v-else-if="error" class="state-block error">
    {{ error }}
    <br />
    <button class="retry" @click="load">重试</button>
  </div>
  <div v-else-if="!tools.length" class="state-block">暂无 Tool</div>
  <table v-else class="data">
    <thead>
      <tr>
        <th>名称</th>
        <th>描述</th>
      </tr>
    </thead>
    <tbody>
      <tr v-for="t in tools" :key="t.name">
        <td class="mono">{{ t.name }}</td>
        <td>{{ t.description }}</td>
      </tr>
    </tbody>
  </table>
</template>
