<script setup>
import { ref } from 'vue'

const navOpen = ref(false)

// public 资产经 BASE_URL 拼接,绕开 Vue 模板对绝对路径的静态资源解析
const logoSrc = import.meta.env.BASE_URL + 'logo.svg'

const items = [
  { to: '/sessions', label: '会话列表' },
  { to: '/profiles', label: 'Profile 列表' },
  { to: '/tools', label: 'Tool 列表' },
  { to: '/memory', label: '长期记忆' },
  { to: '/status', label: '运行状态' },
]
</script>

<template>
  <button class="menu-toggle" @click="navOpen = !navOpen">☰ 菜单</button>
  <div class="shell">
    <nav class="sidenav" :class="{ open: navOpen }" @click="navOpen = false">
      <div class="brand">
        <img :src="logoSrc" alt="OryxOS" />
        <span>OryxOS 管理台</span>
      </div>
      <router-link
        v-for="item in items"
        :key="item.to"
        :to="item.to"
        class="nav-item"
        active-class="active"
      >
        {{ item.label }}
      </router-link>
    </nav>
    <main class="content">
      <router-view />
    </main>
  </div>
</template>
