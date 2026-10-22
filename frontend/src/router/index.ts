/**
 * 路由表。
 *
 * ★ 所有业务页都在 MainLayout 下（它有侧边栏和顶栏），登录页独立。
 * ★ 路由守卫只做"未登录跳登录"这一件事，
 *   权限（会员）由后端返回 403 时再处理，不在前端猜。
 */
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { getToken } from '../api/request'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/LoginView.vue'),
    meta: { public: true, title: '登录' },
  },
  {
    path: '/',
    component: () => import('../layouts/MainLayout.vue'),
    children: [
      {
        path: '',
        name: 'desk',
        component: () => import('../views/DeskView.vue'),
        meta: { title: '记账台' },
      },
      {
        path: 'bills',
        name: 'bills',
        component: () => import('../views/BillsView.vue'),
        meta: { title: '账单' },
      },
      {
        path: 'stats',
        name: 'stats',
        component: () => import('../views/StatsView.vue'),
        meta: { title: '统计' },
      },
      {
        path: 'budget',
        name: 'budget',
        component: () => import('../views/BudgetView.vue'),
        meta: { title: '预算' },
      },
      {
        path: 'accounts',
        name: 'accounts',
        component: () => import('../views/AccountsView.vue'),
        meta: { title: '账户' },
      },
      {
        path: 'settings',
        name: 'settings',
        component: () => import('../views/SettingsView.vue'),
        meta: { title: '设置' },
      },
      // 联调台：开发期工具，保留入口但不放进导航
      {
        path: 'console',
        name: 'console',
        component: () => import('../ConsolePage.vue'),
        meta: { title: '接口联调台' },
      },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to) => {
  if (to.meta.public) return true
  if (!getToken()) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  return true
})

router.afterEach((to) => {
  const t = (to.meta.title as string) || ''
  document.title = t ? `${t} · 随心记` : '随心记'
})

export default router
