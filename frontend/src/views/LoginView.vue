<script setup lang="ts">
/**
 * 登录 / 注册 / 找回密码。
 *
 * ★★ 两个必须记住的接口事实（都踩过）：
 *   1. 登录返回 **只有 `{token, expiresIn}`，没有 user** →
 *      拿到 token 后必须再调 `GET /api/user/me`。
 *   2. 验证码在 dev 环境**直接打在后端日志里**（`【模拟短信】phone=..., code=...`），
 *      页面上没有回显 —— 所以这里给一行提示，告诉开发者去哪看。
 *
 * ★ 验证码 60 秒限流，且**登录与改密码共用同一配额**，倒计时必须做。
 */
import { computed, onBeforeUnmount, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { loginByCode, loginByPassword, register, resetPassword, sendCode } from '../api/auth'
import { BusinessError, setToken } from '../api/request'
import { useBookStore, useUserStore } from '../stores'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const bookStore = useBookStore()

type Mode = 'password' | 'code' | 'register' | 'reset'
const mode = ref<Mode>('password')

const tabs: { key: Mode; label: string }[] = [
  { key: 'password', label: '密码登录' },
  { key: 'code', label: '验证码登录' },
  { key: 'register', label: '注册' },
]

const phone = ref('')
const password = ref('')
const code = ref('')
const nickname = ref('')
const newPassword = ref('')
const busy = ref(false)
const errMsg = ref('')

const countdown = ref(0)
let timer: ReturnType<typeof setInterval> | null = null

const phoneOk = computed(() => /^1[3-9]\d{9}$/.test(phone.value))
const canSendCode = computed(() => phoneOk.value && countdown.value === 0)

function startCountdown() {
  countdown.value = 60
  if (timer) clearInterval(timer)
  timer = setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0 && timer) {
      clearInterval(timer)
      timer = null
    }
  }, 1000)
}

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})

async function onSendCode() {
  if (!canSendCode.value) return
  errMsg.value = ''
  try {
    await sendCode(phone.value)
    startCountdown()
    ElMessage.success('已发送。dev 环境请到后端日志看「【模拟短信】」那一行')
  } catch (e) {
    errMsg.value = e instanceof BusinessError ? e.message : '发送失败，请稍后重试'
  }
}

async function afterAuth(token: string) {
  setToken(token)
  userStore.setTokenAndCache(token)
  const u = await userStore.fetchUser()
  if (!u) {
    errMsg.value = '登录成功但获取用户信息失败，请重试'
    return
  }
  await bookStore.fetchBooks()
  const redirect = (route.query.redirect as string) || '/'
  router.replace(redirect)
}

async function submit() {
  if (busy.value) return
  errMsg.value = ''
  busy.value = true
  try {
    if (mode.value === 'password') {
      const r = await loginByPassword(phone.value, password.value)
      await afterAuth(r.token)
    } else if (mode.value === 'code') {
      const r = await loginByCode(phone.value, code.value)
      await afterAuth(r.token)
    } else if (mode.value === 'register') {
      const r = await register({
        phone: phone.value,
        code: code.value,
        password: password.value,
        nickname: nickname.value || undefined,
      })
      await afterAuth(r.token)
    } else {
      await resetPassword(phone.value, code.value, newPassword.value)
      ElMessage.success('密码已重置，请用新密码登录')
      mode.value = 'password'
      password.value = ''
    }
  } catch (e) {
    errMsg.value = e instanceof BusinessError ? e.message : '网络异常，请检查后端服务'
  } finally {
    busy.value = false
  }
}

function switchMode(m: Mode) {
  mode.value = m
  errMsg.value = ''
}
</script>

<template>
  <div class="login">
    <div class="left">
      <div class="brand">
        <span class="seal">记</span>
        <span class="brand-name">随心记</span>
      </div>
      <p class="claim">说一句，账就记上了。</p>
      <p class="sub">
        它会把「昨晚和朋友吃饭 268，打车回来 34」拆成两笔，<br />
        逐笔摆给你核对，你点头之后才落库。
      </p>
      <div class="demo">
        <span class="demo-label">刚才那句被拆成</span>
        <div class="demo-row">
          <span>和朋友吃饭</span>
          <span class="demo-cat">餐饮 / 聚餐</span>
          <span class="naw-num">268.00</span>
        </div>
        <div class="demo-row">
          <span>打车回来</span>
          <span class="demo-cat demo-cat--miss">缺分类 · 待补</span>
          <span class="naw-num">34.00</span>
        </div>
      </div>
    </div>

    <div class="right">
      <div class="form">
        <div class="tabs">
          <button
            v-for="t in tabs"
            :key="t.key"
            :class="['tab', { 'tab--on': mode === t.key }]"
            @click="switchMode(t.key)"
          >
            {{ t.label }}
          </button>
          <button
            :class="['tab', 'tab--link', { 'tab--on': mode === 'reset' }]"
            @click="switchMode('reset')"
          >
            忘记密码
          </button>
        </div>

        <label class="field">
          <span class="k">手机号</span>
          <input v-model.trim="phone" class="naw-input" placeholder="11 位手机号" maxlength="11" />
        </label>

        <label v-if="mode === 'password' || mode === 'register'" class="field">
          <span class="k">密码</span>
          <input
            v-model="password"
            class="naw-input"
            type="password"
            placeholder="6–32 位"
            @keyup.enter="submit"
          />
        </label>

        <label v-if="mode === 'register'" class="field">
          <span class="k">昵称</span>
          <input v-model.trim="nickname" class="naw-input" placeholder="选填，最多 20 字" />
        </label>

        <label v-if="mode !== 'password'" class="field">
          <span class="k">验证码</span>
          <div class="code-row">
            <input v-model.trim="code" class="naw-input" placeholder="6 位数字" maxlength="6" />
            <button class="naw-textbtn" :disabled="!canSendCode" @click="onSendCode">
              {{ countdown > 0 ? `${countdown}s` : '获取验证码' }}
            </button>
          </div>
        </label>

        <label v-if="mode === 'reset'" class="field">
          <span class="k">新密码</span>
          <input
            v-model="newPassword"
            class="naw-input"
            type="password"
            placeholder="6–32 位"
            @keyup.enter="submit"
          />
        </label>

        <p v-if="errMsg" class="err">{{ errMsg }}</p>

        <button class="submit" :disabled="busy" @click="submit">
          {{
            busy
              ? '处理中…'
              : mode === 'reset'
                ? '重置密码'
                : mode === 'register'
                  ? '注册并登录'
                  : '登录'
          }}
        </button>

        <p class="hint">
          dev 环境的验证码不弹窗，直接打在后端日志里：<br />
          <span class="naw-mono">【模拟短信】phone=…, code=…</span>
        </p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login {
  height: 100vh;
  display: grid;
  grid-template-columns: 1fr 460px;
  background: var(--naw-paper);
}

.left {
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 0 72px;
  border-right: 1px solid var(--naw-rule);
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
}

.seal {
  width: 38px;
  height: 38px;
  display: grid;
  place-items: center;
  border: 2px solid var(--naw-seal);
  border-radius: 3px;
  color: var(--naw-seal);
  font-family: var(--naw-serif);
  font-size: 20px;
  line-height: 1;
  transform: rotate(-7deg);
  box-shadow: inset 0 0 0 1px var(--naw-seal-soft);
}

.brand-name {
  font-family: var(--naw-serif);
  font-size: 20px;
  letter-spacing: 0.14em;
}

.claim {
  margin: 34px 0 0;
  font-family: var(--naw-serif);
  font-size: 30px;
  line-height: 1.35;
  letter-spacing: 0.02em;
}

.sub {
  margin: 14px 0 0;
  font-size: var(--naw-fs-md);
  line-height: 1.85;
  color: var(--naw-ink-2);
}

.demo {
  margin-top: 40px;
  padding-top: 16px;
  border-top: 1.5px solid var(--naw-ink);
  max-width: 420px;
}

.demo-label {
  font-size: var(--naw-fs-xs);
  letter-spacing: 0.14em;
  color: var(--naw-ink-3);
}

.demo-row {
  display: grid;
  grid-template-columns: 1fr auto 82px;
  align-items: baseline;
  gap: 12px;
  padding: 9px 0;
  border-bottom: 1px solid var(--naw-rule);
  font-size: var(--naw-fs-md);
}

.demo-row .naw-num {
  text-align: right;
  font-size: var(--naw-fs-lg);
}

.demo-cat {
  font-size: var(--naw-fs-sm);
  color: var(--naw-ink-3);
}

.demo-cat--miss {
  color: var(--naw-seal);
  border-bottom: 1px dashed var(--naw-seal);
}

.right {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0 48px;
}

.form {
  width: 100%;
  max-width: 320px;
}

.tabs {
  display: flex;
  align-items: baseline;
  gap: 18px;
  padding-bottom: 12px;
  border-bottom: 1.5px solid var(--naw-ink);
}

.tab {
  background: none;
  border: none;
  padding: 0 0 2px;
  font-size: var(--naw-fs-md);
  color: var(--naw-ink-3);
  cursor: pointer;
  border-bottom: 1px solid transparent;
}

.tab:hover {
  color: var(--naw-ink-2);
}

.tab--on {
  color: var(--naw-ink);
  border-bottom-color: var(--naw-seal);
}

.tab--link {
  margin-left: auto;
  font-size: var(--naw-fs-sm);
}

.field {
  display: block;
  margin-top: 16px;
}

.field .k {
  display: block;
  font-size: var(--naw-fs-xs);
  letter-spacing: 0.12em;
  color: var(--naw-ink-3);
  margin-bottom: 6px;
}

.code-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.code-row .naw-input {
  flex: 1;
}

.code-row .naw-textbtn {
  flex-shrink: 0;
  font-size: var(--naw-fs-sm);
  white-space: nowrap;
}

.err {
  margin: 14px 0 0;
  font-size: var(--naw-fs-sm);
  color: var(--naw-seal);
  line-height: 1.6;
}

.submit {
  width: 100%;
  margin-top: 22px;
  padding: 10px 0;
  background: var(--naw-ink);
  color: var(--naw-paper);
  border: none;
  border-radius: 3px;
  font-size: var(--naw-fs-md);
  cursor: pointer;
  transition: opacity 0.14s;
}

.submit:hover {
  opacity: 0.86;
}

.submit:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

.hint {
  margin: 20px 0 0;
  font-size: var(--naw-fs-sm);
  line-height: 1.75;
  color: var(--naw-ink-3);
}
</style>
