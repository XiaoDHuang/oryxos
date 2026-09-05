import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import SessionsView from './views/SessionsView.vue'
import ProfilesView from './views/ProfilesView.vue'
import ToolsView from './views/ToolsView.vue'
import MemoryView from './views/MemoryView.vue'
import StatusView from './views/StatusView.vue'
import './assets/main.css'

// history 模式:Spring 侧对 /admin/** 未命中路径回落 index.html,子路由刷新不 404。
const router = createRouter({
  history: createWebHistory('/admin/'),
  routes: [
    { path: '/', redirect: '/sessions' },
    { path: '/sessions', component: SessionsView },
    { path: '/profiles', component: ProfilesView },
    { path: '/tools', component: ToolsView },
    { path: '/memory', component: MemoryView },
    { path: '/status', component: StatusView },
  ],
})

createApp(App).use(router).mount('#app')
