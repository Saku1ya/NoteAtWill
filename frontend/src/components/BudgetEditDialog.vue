<script setup lang="ts">
/**
 * 预算编辑弹窗（新建 / 修改共用）。
 *
 * ★ 预算页（BudgetView）与记账台（DeskView）共用本组件：
 *   传 target = null  → 新建
 *   传 target = 某条  → 修改
 * ★ 预算**只做月度**，一个账本一个月一条（后端没有 period_type 字段）。
 * ★ `categoryId = null` 表示**账本级预算**（管整个账本），有值才是分类级。
 *   修改时不允许改范围（分类），因为后端按 (账本, 分类, 月份) 定位预算条目。
 */
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createBudget, updateBudget } from '../api/bill'
import { treeCategories } from '../api/base'
import { BusinessError } from '../api/request'
import { useBookStore } from '../stores'
import type { BudgetStatusVO, CategoryTreeVO } from '../api/types'

const props = defineProps<{
  /** 弹窗开关 */
  modelValue: boolean
  /** 传入预算条目则为「修改」，为 null / undefined 则为「新建」 */
  target?: BudgetStatusVO | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'saved'): void
}>()

const bookStore = useBookStore()

const saving = ref(false)
const expenseTree = ref<CategoryTreeVO[]>([])

const form = reactive<{ categoryId: number | null; amount: string; alertThreshold: number }>({
  categoryId: null,
  amount: '',
  alertThreshold: 80,
})

/** 是否处于「修改」模式 */
const isEdit = computed(() => !!props.target)

/** 打开时重置表单并拉取最新的支出分类树 */
watch(
  () => props.modelValue,
  async (open) => {
    if (!open) return
    resetForm()
    await loadTree()
  },
)

async function loadTree() {
  try {
    expenseTree.value = await treeCategories('EXPENSE')
  } catch {
    expenseTree.value = []
  }
}

function resetForm() {
  if (props.target) {
    form.categoryId = props.target.categoryId
    form.amount = props.target.amount
    form.alertThreshold = props.target.alertThreshold
  } else {
    form.categoryId = null
    form.amount = ''
    form.alertThreshold = 80
  }
}

function close() {
  if (!saving.value) emit('update:modelValue', false)
}

async function save() {
  if (!bookStore.currentId) return
  const amt = Number(form.amount)
  if (!(amt > 0)) {
    ElMessage.error('预算金额要大于 0')
    return
  }
  saving.value = true
  try {
    const payload = {
      bookId: bookStore.currentId,
      categoryId: form.categoryId,
      amount: form.amount,
      alertThreshold: form.alertThreshold,
    }
    if (props.target) await updateBudget(props.target.budgetId, payload)
    else await createBudget(payload)
    ElMessage.success(props.target ? '已更新' : '已创建')
    emit('update:modelValue', false)
    emit('saved')
  } catch (e) {
    ElMessage.error(e instanceof BusinessError ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div v-if="modelValue" class="mask" @click.self="close">
    <div class="dialog">
      <div class="dialog-head">
        <span>{{ isEdit ? '修改预算' : '新建预算' }}</span>
        <button class="x" @click="close">×</button>
      </div>

      <label class="df">
        <span class="k">范围</span>
        <select v-model.number="form.categoryId" class="naw-input" :disabled="isEdit">
          <option :value="null">账本总预算（管所有支出）</option>
          <option v-for="p in expenseTree" :key="p.id" :value="p.id">{{ p.name }}</option>
        </select>
      </label>

      <label class="df">
        <span class="k">金额</span>
        <input v-model="form.amount" class="naw-input naw-num" inputmode="decimal" placeholder="0.00" />
      </label>

      <label class="df">
        <span class="k">提醒阈值（%）</span>
        <input v-model.number="form.alertThreshold" class="naw-input" type="number" min="1" max="100" />
      </label>

      <div class="dialog-foot">
        <span class="tip">一个账本一个月一条，重复设会报冲突</span>
        <button class="naw-textbtn" @click="close">取消</button>
        <button class="naw-btn" :disabled="saving" @click="save">
          {{ saving ? '保存中…' : '保存' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
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
  margin-top: 14px;
}

.df .k {
  display: block;
  font-size: var(--naw-fs-xs);
  letter-spacing: 0.12em;
  color: var(--naw-ink-3);
  margin-bottom: 5px;
}

.dialog-foot {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 18px;
  padding-top: 12px;
  border-top: 1px solid var(--naw-rule);
}

.dialog-foot .tip {
  margin-right: auto;
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-3);
}
</style>
