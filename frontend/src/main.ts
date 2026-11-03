import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import OverviewView from './views/OverviewView.vue'
import ScopeView from './views/ScopeView.vue'
import './style.css'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: OverviewView },
    { path: '/scope', component: ScopeView },
  ],
})

createApp(App).use(createPinia()).use(router).mount('#app')
