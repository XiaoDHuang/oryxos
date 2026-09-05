<script setup>
import { onMounted, ref } from 'vue'
import { apiGet } from '../api'

const loading = ref(true)
const error = ref('')
const info = ref(null)
const health = ref(null)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [infoData, healthData] = await Promise.all([apiGet('/info'), apiGet('/health')])
    info.value = infoData
    health.value = healthData
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <h1>运行状态</h1>
  <p class="subtitle">进程健康、版本与各 Provider 连通状态</p>

  <div v-if="loading" class="state-block">加载中…</div>
  <div v-else-if="error" class="state-block error">
    {{ error }}
    <br />
    <button class="retry" @click="load">重试</button>
  </div>
  <template v-else>
    <div class="card" style="margin-bottom: 18px">
      <p>
        <span class="dot" :class="health && health.status === 'ok' ? 'ok' : 'fail'"></span>
        <strong style="color: var(--text-1)">{{ info.name }}</strong>
        <span class="mono" style="color: var(--text-3)"> v{{ info.version }}</span>
      </p>
      <p style="margin-bottom: 0; color: var(--text-3)">{{ info.description }}</p>
    </div>

    <h1 style="font-size: 1.05rem">Providers</h1>
    <div v-if="!info.providers || !info.providers.length" class="state-block">未声明任何 Provider</div>
    <table v-else class="data">
      <thead>
        <tr>
          <th>名称</th>
          <th>状态</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="p in info.providers" :key="p.name">
          <td class="mono">{{ p.name }}</td>
          <td>
            <span class="dot" :class="p.status === 'registered' ? 'ok' : 'warn'"></span>
            {{ p.status }}
          </td>
        </tr>
      </tbody>
    </table>
  </template>
</template>
