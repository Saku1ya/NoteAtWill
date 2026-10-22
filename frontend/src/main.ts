import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
// ★ 令牌必须在 Element Plus 之后引入，否则覆盖不生效
import './styles/tokens.css'
import App from './App.vue'
import router from './router'

/**
 * 应用入口。
 *
 * ★ 样式顺序：element-plus → tokens.css
 *   反过来 Element 的 --el-* 会盖掉我们的覆写。
 *
 * ★ Pinia 要在 router 之前 use：路由守卫里可能用到 store。
 */
createApp(App).use(createPinia()).use(router).use(ElementPlus).mount('#app')
