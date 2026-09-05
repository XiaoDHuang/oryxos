<script setup>
import { onMounted, ref } from 'vue'
import { apiGet } from '../api'

const loading = ref(true)
const error = ref('')
const backend = ref('')
const content = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await apiGet('/memory')
    backend.value = data ? data.backend : ''
    content.value = data ? data.content : ''
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <h1>长期记忆</h1>
  <p class="subtitle">当前后端的全量记忆视图(核心 + 归档窗口),只读</p>

  <div v-if="loading" class="state-block">加载中…</div>
  <div v-else-if="error" class="state-block error">
    {{ error }}
    <br />
    <button class="retry" @click="load">重试</button>
  </div>
  <template v-else>
    <p><span class="tag">backend: {{ backend }}</span></p>
    <div v-if="!content" class="state-block">长期记忆为空</div>
    <pre v-else class="memory">{{ content }}</pre>
  </template>
</template>
