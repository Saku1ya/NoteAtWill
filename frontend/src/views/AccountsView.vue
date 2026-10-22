<script setup lang="ts">
/**
 * 账户页。
 *
 * ★★ 最重要的认知：**余额是派生缓存，唯一真相是资金流水**。
 *   `balance == init_balance + SUM(IN) - SUM(OUT)`。
 *   所以页面里**没有任何"直接改余额"的入口** —— 只能通过
 *   记账（产生 BILL 流水）、转账（产生 TRANSFER 流水）或
 *   余额校正（产生 ADJUST 流水）来改变它。
 *
 * ★ 删除账户前必须确认没有流水（后端会拒绝，抛 ACCOUNT_IN_USE）。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  accountCheck,
  adjustAccount,
  cancelTransfer,
  createAccount,
  deleteAccount,
  listAccountFlows,
  listAccounts,
  transfer,
  updateAccount,
} from '../api/base'
import { BusinessError } from '../api/request'
import type { AccountCheckVO, AccountFlowVO, AccountVO } from '../api/types'

const accounts = ref<AccountVO[]>([])
const loading = ref(true)
const activeId = ref<number | null>(null)

const flows = ref<AccountFlowVO[]>([])
const flowPage = ref(1)
const flowType = ref<'ALL' | 'BILL' | 'TRANSFER' | 'ADJUST'>('ALL')
const flowsLoading = ref(false)
const flowTotal = ref(0)

const check = ref<AccountCheckVO[] | null>(null)

async function load() {
  loading.value = true
  try {
    accounts.value = await listAccounts()
    if (!activeId.value && accounts.value.length) activeId.value = accounts.value[0].id
    if (activeId.value) await loadFlows()
  } catch (e) {
    ElMessage.error(e instanceof BusinessError ? e.message : '加载账户失败')
  } finally {
    loading.value = false
  }
}

async function loadFlows() {
  if (!activeId.value) return
  flowsLoading.value = true
  try {
    const list = await listAccountFlows(
      activeId.value,
      flowPage.value,
      15,
      flowType.value === 'ALL' ? undefined : flowType.value,
    )
    flows.value = list
    // ★ 后端这个接口返回纯数组（不带分页外壳），总数只能按返回长度判断有无下一页
    flowTotal.value = list.length < 15 ? (flowPage.value - 1) * 15 + list.length : flowPage.value * 15 + 1
  } catch {
    flows.value = []
  } finally {
    flowsLoading.value = false
  }
}

async function runCheck() {
  try {
    const result = await accountCheck()
    check.value = result
    const bad = result.filter((i) => !i.matched)
    if (!bad.length) ElMessage.success('对账通过，所有账户余额与流水一致')
    else ElMessage.warning(`有 ${bad.length} 个账户对不上，请看下方`)
  } catch (e) {
    ElMessage.error(e instanceof BusinessError ? e.message : '对账失败')
  }
}

onMounted(load)

const active = computed(() => accounts.value.find((a) => a.id === activeId.value) ?? null)

function pick(id: number) {
  activeId.value = id
  flowPage.value = 1
  check.value = null
  loadFlows()
}

function money(s: string | number | null | undefined) {
  return Number(s ?? 0).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })
}

const totalBalance = computed(() => accounts.value.reduce((s, a) => s + Number(a.balance), 0))

const typeLabel = (t: string) =>
  ({ CASH: '现金', WECHAT: '微信', ALIPAY: '支付宝', BANK: '银行卡', OTHER: '其他' })[t] ?? t

const flowLabel = (t: string) =>
  ({ BILL: '记账', TRANSFER: '转账', ADJUST: '校正' })[t] ?? t

// ============================================================================
// 新增 / 编辑
// ============================================================================

const dialogOpen = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)
const form = reactive<{ name: string; type: AccountVO['type']; initBalance: string; icon: string }>({
  name: '',
  type: 'WECHAT',
  initBalance: '0.00',
  icon: '',
})

function openCreate() {
  editingId.value = null
  form.name = ''
  form.type = 'WECHAT'
  form.initBalance = '0.00'
  dialogOpen.value = true
}

function openEdit(a: AccountVO) {
  editingId.value = a.id
  form.name = a.name
  form.type = a.type
  form.initBalance = a.initBalance
  dialogOpen.value = true
}

async function save() {
  if (!form.name.trim()) {
    ElMessage.error('账户名不能为空')
    return
  }
  saving.value = true
  try {
    const payload = {
      name: form.name.trim(),
      type: form.type,
      initBalance: form.initBalance,
      icon: form.icon || undefined,
    }
    if (editingId.value) await updateAccount(editingId.value, payload)
    else await createAccount(payload)
    ElMessage.success(editingId.value ? '已更新' : '已创建')
    dialogOpen.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof BusinessError ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(a: AccountVO) {
  try {
    await ElMessageBox.confirm(
      '只有没有任何资金流水的账户才能删除。有流水的账户请先处理账目。',
      `删除「${a.name}」？`,
      { confirmButtonText: '删除', cancelButtonText: '算了', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await deleteAccount(a.id)
    ElMessage.success('已删除')
    if (activeId.value === a.id) activeId.value = null
    await load()
  } catch (e) {
    ElMessage.error(e instanceof BusinessError ? e.message : '删除失败')
  }
}

// ============================================================================
// 转账
// ============================================================================

const transferOpen = ref(false)
const transferring = ref(false)
const tForm = reactive({ fromAccountId: null as number | null, toAccountId: null as number | null, amount: '', flowTime: nowStr(), remark: '' })

function nowStr() {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:00`
}

function openTransfer() {
  tForm.fromAccountId = activeId.value
  tForm.toAccountId = null
  tForm.amount = ''
  tForm.flowTime = nowStr()
  tForm.remark = ''
  transferOpen.value = true
}

async function doTransfer() {
  if (!tForm.fromAccountId || !tForm.toAccountId) {
    ElMessage.error('请选择转出和转入账户')
    return
  }
  if (tForm.fromAccountId === tForm.toAccountId) {
    ElMessage.error('转出和转入不能是同一个账户')
    return
  }
  if (!(Number(tForm.amount) > 0)) {
    ElMessage.error('金额要大于 0')
    return
  }
  transferring.value = true
  try {
    await transfer({
      fromAccountId: tForm.fromAccountId,
      toAccountId: tForm.toAccountId,
      amount: tForm.amount,
      remark: tForm.remark || null,
    })
    ElMessage.success('已转账')
    transferOpen.value = false
    // ★ 转账会同时改两个账户的余额，必须整体重拉
    await load()
  } catch (e) {
    ElMessage.error(e instanceof BusinessError ? e.message : '转账失败')
  } finally {
    transferring.value = false
  }
}

/** 撤销转账（按批次号删两条流水） */
async function undoTransfer(transferNo: string) {
  try {
    await ElMessageBox.confirm('撤销会删掉这笔转账的两条流水，两个账户余额都会回滚。', '撤销这次转账？', {
      confirmButtonText: '撤销',
      cancelButtonText: '算了',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await cancelTransfer(transferNo)
    ElMessage.success('已撤销')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof BusinessError ? e.message : '撤销失败')
  }
}

// ============================================================================
// 余额校正
// ============================================================================

const adjustOpen = ref(false)
const adjusting = ref(false)
const aForm = reactive({ targetBalance: '', remark: '' })

function openAdjust() {
  aForm.targetBalance = active.value?.balance ?? '0.00'
  aForm.remark = ''
  adjustOpen.value = true
}

async function doAdjust() {
  if (!activeId.value) return
  adjusting.value = true
  try {
    await adjustAccount(activeId.value, {
      targetBalance: aForm.targetBalance,
      remark: aForm.remark || '手动校正',
    })
    ElMessage.success('已校正，差额记入一条校正流水')
    adjustOpen.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof BusinessError ? e.message : '校正失败')
  } finally {
    adjusting.value = false
  }
}
</script>

<template>
  <div class="accounts">
    <!-- ==================== 左：账户列表 ==================== -->
    <section class="left">
      <div class="left-head">
        <span class="naw-label">
          账户
          <span class="naw-label__sub">合计 <b class="naw-num">{{ money(totalBalance) }}</b></span>
        </span>
        <div class="head-acts">
          <button class="naw-textbtn" @click="openTransfer">转账</button>
          <button class="naw-textbtn" @click="runCheck">对账</button>
          <button class="naw-textbtn" @click="openCreate">+ 账户</button>
        </div>
      </div>

      <div v-if="loading" class="naw-empty"><span class="naw-dots"><i /><i /><i /></span></div>
      <div v-else-if="!accounts.length" class="empty">
        <p>还没有账户。</p>
        <p class="sub">账户用来跟踪余额；不建账户也能记账，只是余额不会变。</p>
        <button class="naw-btn" @click="openCreate">建一个</button>
      </div>

      <div v-else class="alist">
        <div
          v-for="a in accounts"
          :key="a.id"
          :class="['acard', { 'acard--on': activeId === a.id }]"
          @click="pick(a.id)"
        >
          <div class="acard-top">
            <span class="aname">{{ a.name }}</span>
            <span class="atype">{{ typeLabel(a.type) }}</span>
            <b :class="['naw-num', { 'naw-num--seal': Number(a.balance) < 0 }]">
              {{ money(a.balance) }}
            </b>
          </div>
          <div class="acard-meta">初始 {{ money(a.initBalance) }}</div>
          <div class="acard-acts">
            <button class="naw-textbtn" @click.stop="openEdit(a)">改</button>
            <button class="naw-textbtn naw-textbtn--seal" @click.stop="remove(a)">删</button>
          </div>
        </div>
      </div>

      <!-- 对账结果 -->
      <div v-if="check && check.length" class="check">
        <span class="naw-label">对账结果</span>
        <div v-for="i in check" :key="i.accountId" class="check-row">
          <span class="cn">{{ i.name }}</span>
          <span :class="['cv', 'naw-num', { 'cv--bad': !i.matched }]">{{ money(i.diff) }}</span>
          <span :class="['cs', { 'cs--bad': !i.matched }]">
            {{ i.matched ? '平' : '不平' }}
          </span>
        </div>
      </div>
    </section>

    <!-- ==================== 右：流水 ==================== -->
    <aside class="right">
      <template v-if="active">
        <div class="right-head">
          <span class="naw-label">{{ active.name }} 的资金流水</span>
        </div>

        <div class="filters">
          <button
            v-for="t in [
              { k: 'ALL', l: '全部' },
              { k: 'BILL', l: '记账' },
              { k: 'TRANSFER', l: '转账' },
              { k: 'ADJUST', l: '校正' },
            ]"
            :key="t.k"
            :class="['ft', { 'ft--on': flowType === t.k }]"
            @click="(flowType = t.k as typeof flowType), (flowPage = 1), loadFlows()"
          >
            {{ t.l }}
          </button>
          <button class="naw-textbtn adjust" @click="openAdjust">校正余额</button>
        </div>

        <div v-if="flowsLoading" class="naw-empty"><span class="naw-dots"><i /><i /><i /></span></div>
        <div v-else-if="!flows.length" class="naw-empty">没有流水记录</div>

        <div v-else class="flows">
          <div v-for="f in flows" :key="f.id" class="frow">
            <div class="frow-top">
              <span class="ftype">{{ flowLabel(f.flowType) }}</span>
              <span class="ftime mono">{{ f.flowTime.slice(5, 16) }}</span>
              <span
                :class="['famt', 'naw-num', { 'naw-num--income': f.direction === 'IN' }]"
              >{{ f.direction === 'IN' ? '+' : '−' }}{{ money(f.amount) }}</span>
            </div>
            <div class="frow-meta">
              <span v-if="f.remark" class="fremark">{{ f.remark }}</span>
              <span v-if="f.flowType === 'TRANSFER' && f.counterpartAccountName" class="fother">
                {{ f.direction === 'OUT' ? '转给' : '来自' }} {{ f.counterpartAccountName }}
              </span>
              <button
                v-if="f.flowType === 'TRANSFER' && f.transferNo"
                class="naw-textbtn undo"
                @click="undoTransfer(f.transferNo)"
              >
                撤销
              </button>
            </div>
          </div>

          <div v-if="flowTotal > 15" class="pager">
            <el-pagination
              v-model:current-page="flowPage"
              :page-size="15"
              :total="flowTotal"
              layout="prev, pager, next"
              small
              background
              @current-change="loadFlows"
            />
          </div>
        </div>
      </template>

      <div v-else class="right-empty">
        <p>选左边一个账户</p>
        <p class="sub">这里显示它的资金流水。</p>
      </div>
    </aside>
  </div>

  <!-- ==================== 账户弹层 ==================== -->
  <div v-if="dialogOpen" class="mask" @click.self="dialogOpen = false">
    <div class="dialog">
      <div class="dialog-head">
        <span>{{ editingId ? '修改账户' : '新建账户' }}</span>
        <button class="x" @click="dialogOpen = false">×</button>
      </div>

      <label class="df">
        <span class="k">名称</span>
        <input v-model="form.name" class="naw-input" maxlength="20" placeholder="如 微信、招行卡" />
      </label>

      <label class="df">
        <span class="k">类型</span>
        <select v-model="form.type" class="naw-input">
          <option value="CASH">现金</option>
          <option value="WECHAT">微信</option>
          <option value="ALIPAY">支付宝</option>
          <option value="BANK">银行卡</option>
          <option value="OTHER">其他</option>
        </select>
      </label>

      <label class="df">
        <span class="k">初始余额</span>
        <input v-model="form.initBalance" class="naw-input naw-num" inputmode="decimal" />
      </label>

      <div class="dialog-foot">
        <span class="tip">建好之后余额只能通过流水改（记账/转账/校正）</span>
        <button class="naw-textbtn" @click="dialogOpen = false">取消</button>
        <button class="naw-btn" :disabled="saving" @click="save">
          {{ saving ? '保存中…' : '保存' }}
        </button>
      </div>
    </div>
  </div>

  <!-- ==================== 转账弹层 ==================== -->
  <div v-if="transferOpen" class="mask" @click.self="transferOpen = false">
    <div class="dialog">
      <div class="dialog-head">
        <span>账户转账</span>
        <button class="x" @click="transferOpen = false">×</button>
      </div>

      <label class="df">
        <span class="k">转出</span>
        <select v-model.number="tForm.fromAccountId" class="naw-input">
          <option :value="null" disabled>请选择</option>
          <option v-for="a in accounts" :key="a.id" :value="a.id">
            {{ a.name }}（{{ money(a.balance) }}）
          </option>
        </select>
      </label>

      <label class="df">
        <span class="k">转入</span>
        <select v-model.number="tForm.toAccountId" class="naw-input">
          <option :value="null" disabled>请选择</option>
          <option v-for="a in accounts" :key="a.id" :value="a.id">
            {{ a.name }}（{{ money(a.balance) }}）
          </option>
        </select>
      </label>

      <label class="df">
        <span class="k">金额</span>
        <input v-model="tForm.amount" class="naw-input naw-num" inputmode="decimal" placeholder="0.00" />
      </label>

      <label class="df">
        <span class="k">时间</span>
        <input v-model="tForm.flowTime" class="naw-input naw-mono" />
      </label>

      <label class="df">
        <span class="k">备注</span>
        <input v-model="tForm.remark" class="naw-input" maxlength="50" placeholder="选填" />
      </label>

      <div class="dialog-foot">
        <span class="tip">转账会产生两条流水（转出 OUT / 转入 IN），同一事务</span>
        <button class="naw-textbtn" @click="transferOpen = false">取消</button>
        <button class="naw-btn" :disabled="transferring" @click="doTransfer">
          {{ transferring ? '转账中…' : '转账' }}
        </button>
      </div>
    </div>
  </div>

  <!-- ==================== 校正弹层 ==================== -->
  <div v-if="adjustOpen" class="mask" @click.self="adjustOpen = false">
    <div class="dialog">
      <div class="dialog-head">
        <span>校正余额</span>
        <button class="x" @click="adjustOpen = false">×</button>
      </div>

      <div class="cur">
        当前余额 <b class="naw-num">{{ money(active?.balance) }}</b>
      </div>

      <label class="df">
        <span class="k">目标余额</span>
        <input v-model="aForm.targetBalance" class="naw-input naw-num" inputmode="decimal" />
      </label>

      <label class="df">
        <span class="k">原因</span>
        <input v-model="aForm.remark" class="naw-input" maxlength="50" placeholder="如 银行流水对不上" />
      </label>

      <div class="dialog-foot">
        <span class="tip">差额会记成一条 ADJUST 流水，不直接改字段</span>
        <button class="naw-textbtn" @click="adjustOpen = false">取消</button>
        <button class="naw-btn" :disabled="adjusting" @click="doAdjust">
          {{ adjusting ? '校正中…' : '校正' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.accounts {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 1fr 360px;
  padding: 0 var(--naw-page-x);
  overflow: hidden;
}

/* ==================== 左 ==================== */
.left {
  padding: 16px 26px 0 0;
  border-right: 1px solid var(--naw-rule);
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.left-head {
  display: flex;
  align-items: baseline;
  padding-bottom: 10px;
  border-bottom: 1.5px solid var(--naw-ink);
  flex-shrink: 0;
}

.left-head .naw-label__sub b {
  color: var(--naw-ink);
  font-size: var(--naw-fs-md);
}

.head-acts {
  margin-left: auto;
  display: flex;
  gap: 14px;
}

.head-acts .naw-textbtn {
  font-size: var(--naw-fs-sm);
}

.empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: flex-start;
  gap: 10px;
  color: var(--naw-ink-3);
}

.empty p {
  margin: 0;
}

.empty .sub {
  font-size: var(--naw-fs-sm);
  color: var(--naw-ink-4);
}

.alist {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  scrollbar-gutter: stable;
  padding: 12px 0;
}

.acard {
  padding: 11px 13px;
  border: 1px solid var(--naw-rule);
  border-radius: 2px;
  margin-bottom: 8px;
  cursor: pointer;
  transition: border-color 0.14s;
}

.acard:hover {
  border-color: var(--naw-rule-2);
}

.acard--on {
  background: var(--naw-paper-2);
  border-color: var(--naw-rule-2);
  box-shadow: inset 3px 0 0 var(--naw-seal);
}

.acard-top {
  display: flex;
  align-items: baseline;
  gap: 9px;
}

.aname {
  font-size: var(--naw-fs-md);
  color: var(--naw-ink);
}

.atype {
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-3);
  border: 1px solid var(--naw-rule);
  padding: 0 5px;
  border-radius: 2px;
}

.acard-top b {
  margin-left: auto;
  font-size: var(--naw-fs-xl);
  font-weight: 400;
}

.acard-meta {
  margin-top: 3px;
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-4);
}

.acard-acts {
  display: flex;
  gap: 12px;
  margin-top: 7px;
  opacity: 0;
  transition: opacity 0.14s;
}

.acard:hover .acard-acts {
  opacity: 1;
}

.acard-acts .naw-textbtn {
  font-size: var(--naw-fs-xs);
}

/* 对账 */
.check {
  flex-shrink: 0;
  padding: 12px 0;
  border-top: 1px solid var(--naw-rule);
  max-height: 180px;
  overflow-y: auto;
  scrollbar-gutter: stable;
}

.check-row {
  display: grid;
  grid-template-columns: 1fr 90px 40px;
  align-items: baseline;
  gap: 8px;
  padding: 4px 0;
  font-size: var(--naw-fs-sm);
}

.check-row .cn {
  color: var(--naw-ink-2);
}

.check-row .cv {
  text-align: right;
}

.check-row .cv--bad {
  color: var(--naw-seal);
}

.check-row .cs {
  text-align: right;
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-3);
}

.check-row .cs--bad {
  color: var(--naw-seal);
}

/* ==================== 右 ==================== */
.right {
  padding: 16px 0 0 24px;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.right-head {
  padding-bottom: 10px;
  border-bottom: 1.5px solid var(--naw-ink);
  flex-shrink: 0;
}

.filters {
  display: flex;
  align-items: baseline;
  gap: 14px;
  padding: 10px 0;
  border-bottom: 1px solid var(--naw-rule);
  flex-shrink: 0;
}

.ft {
  background: none;
  border: none;
  padding: 0 0 1px;
  font-size: var(--naw-fs-sm);
  color: var(--naw-ink-3);
  border-bottom: 1px solid transparent;
  cursor: pointer;
}

.ft--on {
  color: var(--naw-ink);
  border-bottom-color: var(--naw-seal);
}

.adjust {
  margin-left: auto;
  font-size: var(--naw-fs-xs);
}

.flows {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  scrollbar-gutter: stable;
  padding: 8px 0 12px;
}

.frow {
  padding: 8px 0;
  border-bottom: 1px solid var(--naw-rule);
}

.frow-top {
  display: flex;
  align-items: baseline;
  gap: 9px;
}

.ftype {
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-3);
  border: 1px solid var(--naw-rule);
  padding: 0 5px;
  border-radius: 2px;
}

.ftime {
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-4);
}

.famt {
  margin-left: auto;
  font-size: var(--naw-fs-md);
}

.frow-meta {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-top: 3px;
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-3);
}

.fother {
  color: var(--naw-ink-2);
}

.undo {
  margin-left: auto;
  font-size: var(--naw-fs-xs);
}

.pager {
  padding-top: 10px;
  display: flex;
  justify-content: flex-end;
}

.right-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 6px;
  color: var(--naw-ink-3);
}

.right-empty p {
  margin: 0;
}

.right-empty .sub {
  font-size: var(--naw-fs-sm);
  color: var(--naw-ink-4);
}

/* ==================== 弹层 ==================== */
.mask {
  position: fixed;
  inset: 0;
  z-index: 100;
  background: rgba(28, 25, 23, 0.28);
  display: grid;
  place-items: center;
}

.dialog {
  width: 380px;
  max-height: 86vh;
  overflow-y: auto;
  scrollbar-gutter: stable;
  background: var(--naw-paper);
  border: 1px solid var(--naw-rule-2);
  padding: 20px 22px 18px;
}

.dialog-head {
  display: flex;
  align-items: baseline;
  padding-bottom: 10px;
  border-bottom: 1.5px solid var(--naw-ink);
  font-family: var(--naw-serif);
  font-size: var(--naw-fs-lg);
  letter-spacing: 0.06em;
}

.x {
  margin-left: auto;
  background: none;
  border: none;
  font-size: 18px;
  line-height: 1;
  color: var(--naw-ink-3);
  cursor: pointer;
}

.df {
  display: block;
  margin-top: 13px;
}

.df .k {
  display: block;
  font-size: var(--naw-fs-xs);
  letter-spacing: 0.12em;
  color: var(--naw-ink-3);
  margin-bottom: 5px;
}

.cur {
  margin-top: 12px;
  font-size: var(--naw-fs-sm);
  color: var(--naw-ink-3);
}

.cur b {
  color: var(--naw-ink);
  font-size: var(--naw-fs-lg);
  margin-left: 6px;
}

.dialog-foot {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 18px;
  padding-top: 12px;
  border-top: 1px solid var(--naw-rule);
}

.dialog-foot .tip {
  margin-right: auto;
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-3);
  line-height: 1.5;
  max-width: 170px;
}
</style>
