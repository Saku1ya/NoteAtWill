<script setup lang="ts">
/**
 * 设置页：账本 / 分类 / AI 人设 / 个人资料。
 *
 * ★ 分类是**两棵独立的树**（支出、收入），切换类型必须重新拉。
 * ★ 系统预置分类不可删（`isPreset`），只有自己建的才能删。
 * ★ 改密码用的是**短信验证码**，不是旧密码 —— 这是接口契约，踩过。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createBook,
  createCategory,
  deleteBook,
  deleteCategory,
  listBooks,
  treeCategories,
  updateBook,
  updateCategory,
} from '../api/base'
import { changePassword } from '../api/user'
import {
  activatePersona,
  createPersona,
  deletePersona,
  updatePersona,
  getActivePersona,
  listMyPersonas,
  listPresets,
} from '../api/persona'
import { BusinessError } from '../api/request'
import { useBookStore, useUserStore } from '../stores'
import type { BookVO, CategoryTreeVO, PersonaPresetVO, PersonaVO } from '../api/types'

const userStore = useUserStore()
const bookStore = useBookStore()

type Tab = 'book' | 'category' | 'persona' | 'account'
const tab = ref<Tab>('book')

const tabs: { k: Tab; l: string }[] = [
  { k: 'book', l: '账本' },
  { k: 'category', l: '分类' },
  { k: 'persona', l: 'AI 人设' },
  { k: 'account', l: '账号' },
]

// ============================================================================
// 账本
// ============================================================================

const books = ref<BookVO[]>([])
const bookDialog = ref(false)
const bookEditing = ref<number | null>(null)
const bookForm = reactive({ name: '', icon: '', color: '' })
const bookSaving = ref(false)

async function loadBooks() {
  try {
    books.value = await listBooks()
  } catch {
    books.value = []
  }
}

function openBookCreate() {
  bookEditing.value = null
  bookForm.name = ''
  bookDialog.value = true
}

function openBookEdit(b: BookVO) {
  bookEditing.value = b.id
  bookForm.name = b.name
  bookDialog.value = true
}

async function saveBook() {
  if (!bookForm.name.trim()) {
    ElMessage.error('账本名不能为空')
    return
  }
  bookSaving.value = true
  try {
    const payload = { name: bookForm.name.trim(), icon: bookForm.icon || undefined, color: bookForm.color || undefined }
    if (bookEditing.value) await updateBook(bookEditing.value, payload)
    else await createBook(payload)
    ElMessage.success('已保存')
    bookDialog.value = false
    await loadBooks()
    await bookStore.fetchBooks()
  } catch (e) {
    ElMessage.error(e instanceof BusinessError ? e.message : '保存失败')
  } finally {
    bookSaving.value = false
  }
}

async function removeBook(b: BookVO) {
  try {
    await ElMessageBox.confirm(
      '账本里还有账单时不能删。请先把账单处理掉。',
      `删除「${b.name}」？`,
      { confirmButtonText: '删除', cancelButtonText: '算了', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await deleteBook(b.id)
    ElMessage.success('已删除')
    await loadBooks()
    await bookStore.fetchBooks()
  } catch (e) {
    ElMessage.error(e instanceof BusinessError ? e.message : '删除失败')
  }
}

// ============================================================================
// 分类
// ============================================================================

const catType = ref<'EXPENSE' | 'INCOME'>('EXPENSE')
const catTree = ref<CategoryTreeVO[]>([])
const catLoading = ref(false)

async function loadCats() {
  catLoading.value = true
  try {
    catTree.value = await treeCategories(catType.value)
  } catch {
    catTree.value = []
  } finally {
    catLoading.value = false
  }
}

function switchCatType(t: 'EXPENSE' | 'INCOME') {
  catType.value = t
  loadCats()
}

/** 行内新增。
 *  ★ 用 `TOP` 表示"新增一级分类"，避免和 parentId 的 0 混淆：
 *    后端的 0 是合法 parentId，但它是数字，`addingUnder === 0` 容易被
 *    判成 falsy 而失效（null 与 0 在模板里的 `===` 比较是对的，但可读性差）。
 */
const TOP = -1
const addingUnder = ref<number | null>(null)
const newCatName = ref('')

function startAdd(parentId: number) {
  addingUnder.value = parentId
  newCatName.value = ''
}

/** 开始新增一级分类（parentId = 0）。 */
function startAddTop() {
  addingUnder.value = TOP
  newCatName.value = ''
}

async function commitAdd(parentId: number) {
  const name = newCatName.value.trim()
  if (!name) {
    addingUnder.value = null
    return
  }
  try {
    await createCategory({
      parentId,
      name,
      type: catType.value,
      icon: undefined,
      sort: undefined,
    })
    ElMessage.success('已添加')
    addingUnder.value = null
    await loadCats()
  } catch (e) {
    ElMessage.error(e instanceof BusinessError ? e.message : '添加失败')
  }
}

/** 提交一级分类 —— parentId 传 0（后端的"顶级"约定）。 */
function commitAddTop() {
  return commitAdd(0)
}

async function renameCat(node: CategoryTreeVO) {
  if (node.isPreset) {
    ElMessage.warning('系统预置分类不能改名，但可以禁用')
    return
  }
  try {
    const { value } = await ElMessageBox.prompt('新名字', '改分类名', {
      inputValue: node.name,
      confirmButtonText: '保存',
      cancelButtonText: '算了',
    })
    if (!value?.trim()) return
    await updateCategory(node.id, {
      name: value.trim(),
      type: node.type,
      icon: node.icon ?? undefined,
      sort: node.sort,
    })
    ElMessage.success('已改名')
    await loadCats()
  } catch (e) {
    if (e instanceof BusinessError) ElMessage.error(e.message)
  }
}

async function removeCat(node: CategoryTreeVO) {
  if (node.isPreset) {
    ElMessage.warning('系统预置分类不能删')
    return
  }
  try {
    await ElMessageBox.confirm('分类下还有账单时会删不掉。', `删除「${node.name}」？`, {
      confirmButtonText: '删除',
      cancelButtonText: '算了',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await deleteCategory(node.id)
    ElMessage.success('已删除')
    await loadCats()
  } catch (e) {
    ElMessage.error(e instanceof BusinessError ? e.message : '删除失败')
  }
}

// ============================================================================
// AI 人设
// ============================================================================

const presets = ref<PersonaPresetVO[]>([])
const personas = ref<PersonaVO[]>([])
const activePersona = ref<PersonaVO | null>(null)
const personaLoading = ref(false)

async function loadPersonas() {
  personaLoading.value = true
  try {
    const [p, my, act] = await Promise.allSettled([
      listPresets(),
      listMyPersonas(),
      getActivePersona(),
    ])
    if (p.status === 'fulfilled') presets.value = p.value
    if (my.status === 'fulfilled') personas.value = my.value
    activePersona.value = act.status === 'fulfilled' ? act.value : null
  } finally {
    personaLoading.value = false
  }
}

// ---------------------------------------------------------------------------
// 新建 / 编辑角色
//
// ★ 后端两种创建方式靠 `presetId` 区分：
//   有 presetId → 先复制该预设（这里不用，因为复制走 usePreset）
//   无 presetId → **name 必填**，其余字段都可空
// ---------------------------------------------------------------------------

const personaDialog = ref(false)
/** null = 新建；有值 = 编辑该 id */
const personaEditingId = ref<number | null>(null)
const personaSaving = ref(false)

const personaForm = reactive<{
  name: string
  gender: '' | 'MALE' | 'FEMALE' | 'OTHER'
  relation: string
  address: string
  personality: string
}>({
  name: '',
  // ★ 默认「其他」而不是空串：后端不传 gender 时会兜底成 OTHER，
  //   界面和后端保持一致，不要让用户以为"没指定"，实际却存了个值。
  gender: 'OTHER',
  relation: '',
  address: '',
  personality: '',
})

/** 关系与称呼给几个常用值做提示，但允许自由填（后端不校验枚举） */
const RELATION_HINTS = ['朋友', '家人', '恋人', '管家', '学姐', '宠物', '同事']
const ADDRESS_HINTS = ['你', '主人', '老板', '亲爱的', '铲屎官']
/**
 * 性别选项。
 *
 * ★ 没有「不指定」这一项：后端 `PersonaService` 里
 *   `gender` 不传时会被兜底成 `OTHER`（不是 null），
 *   所以界面上写"不指定"会让用户以为真的没存，实际存的是"其它"。
 *   直接如实呈现三选一。
 */
const GENDER_OPTIONS = [
  { k: 'MALE', l: '男' },
  { k: 'FEMALE', l: '女' },
  { k: 'OTHER', l: '其他' },
] as const

function resetPersonaForm() {
  personaForm.name = ''
  personaForm.gender = 'OTHER'
  personaForm.relation = ''
  personaForm.address = ''
  personaForm.personality = ''
}

function openPersonaCreate() {
  personaEditingId.value = null
  resetPersonaForm()
  personaDialog.value = true
}

function openPersonaEdit(p: PersonaVO) {
  personaEditingId.value = p.id
  personaForm.name = p.name
  personaForm.gender = (p.gender as typeof personaForm.gender) ?? 'OTHER'
  personaForm.relation = p.relation ?? ''
  personaForm.address = p.address ?? ''
  personaForm.personality = p.personality ?? ''
  personaDialog.value = true
}

async function savePersona() {
  const name = personaForm.name.trim()
  if (!name) {
    ElMessage.error('角色名字不能为空')
    return
  }
  personaSaving.value = true
  try {
    const payload = {
      name,
      gender: personaForm.gender || undefined,
      relation: personaForm.relation.trim() || undefined,
      address: personaForm.address.trim() || undefined,
      personality: personaForm.personality.trim() || undefined,
    }
    if (personaEditingId.value) {
      await updatePersona(personaEditingId.value, payload)
      ElMessage.success('已保存')
    } else {
      await createPersona(payload)
      ElMessage.success(`「${name}」已创建`)
    }
    personaDialog.value = false
    await loadPersonas()
  } catch (e) {
    ElMessage.error(e instanceof BusinessError ? e.message : '保存失败')
  } finally {
    personaSaving.value = false
  }
}

async function usePreset(p: PersonaPresetVO) {
  try {
    await createPersona({
      // ★ 必须带上 presetId：后端靠它走"复制预设"分支，
      //   并把来源写进 t_ai_persona.preset_id 做溯源。
      //   只传字段的话 preset_id 会一直是 NULL（此前的 bug）。
      presetId: p.id,
      name: p.name,
      avatar: p.avatar ?? undefined,
      gender: p.gender ?? undefined,
      relation: p.relation ?? undefined,
      address: p.address ?? undefined,
      personality: p.personality ?? undefined,
    })
    ElMessage.success(`已复制「${p.name}」到我的角色`)
    await loadPersonas()
  } catch (e) {
    ElMessage.error(e instanceof BusinessError ? e.message : '创建失败')
  }
}

async function activate(p: PersonaVO) {
  try {
    await activatePersona(p.id)
    ElMessage.success('已启用')
    await loadPersonas()
  } catch (e) {
    ElMessage.error(e instanceof BusinessError ? e.message : '启用失败')
  }
}

async function removePersona(p: PersonaVO) {
  try {
    await ElMessageBox.confirm('删除后这个角色的设定就没了。', `删除「${p.name}」？`, {
      confirmButtonText: '删除',
      cancelButtonText: '算了',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await deletePersona(p.id)
    ElMessage.success('已删除')
    await loadPersonas()
  } catch (e) {
    ElMessage.error(e instanceof BusinessError ? e.message : '删除失败')
  }
}

// ============================================================================
// 账号
// ============================================================================

const pwForm = reactive({ code: '', newPassword: '' })
const pwBusy = ref(false)
const countdown = ref(0)
let timer: ReturnType<typeof setInterval> | null = null

const canSendCode = computed(() => countdown.value === 0)

async function sendPwCode() {
  if (!canSendCode.value) return
  const { sendCode } = await import('../api/auth')
  try {
    await sendCode(userStore.user?.phone ?? '')
    countdown.value = 60
    if (timer) clearInterval(timer)
    timer = setInterval(() => {
      countdown.value -= 1
      if (countdown.value <= 0 && timer) {
        clearInterval(timer)
        timer = null
      }
    }, 1000)
    ElMessage.success('已发送，dev 环境去后端日志看「模拟短信」')
  } catch (e) {
    ElMessage.error(e instanceof BusinessError ? e.message : '发送失败')
  }
}

async function submitPassword() {
  if (!pwForm.code || !pwForm.newPassword) {
    ElMessage.error('验证码和新密码都要填')
    return
  }
  pwBusy.value = true
  try {
    // ★ 改密码用的是短信验证码，不是旧密码
    await changePassword(pwForm.code, pwForm.newPassword)
    ElMessage.success('密码已更新')
    pwForm.code = ''
    pwForm.newPassword = ''
  } catch (e) {
    ElMessage.error(e instanceof BusinessError ? e.message : '修改失败')
  } finally {
    pwBusy.value = false
  }
}

// ============================================================================

onMounted(() => {
  loadBooks()
  loadCats()
  loadPersonas()
})
</script>

<template>
  <div class="settings">
    <!-- 左侧 tab -->
    <aside class="side">
      <button
        v-for="t in tabs"
        :key="t.k"
        :class="['tab', { 'tab--on': tab === t.k }]"
        @click="tab = t.k"
      >
        {{ t.l }}
      </button>
    </aside>

    <!-- 内容 -->
    <section class="pane">
      <!-- ==================== 账本 ==================== -->
      <template v-if="tab === 'book'">
        <div class="pane-head">
          <span class="naw-label">账本</span>
          <button class="naw-textbtn head-act" @click="openBookCreate">+ 新建账本</button>
        </div>

        <div v-if="!books.length" class="naw-empty">还没有账本</div>

        <div v-else class="rows">
          <div v-for="b in books" :key="b.id" class="row">
            <span class="nm">{{ b.name }}</span>
            <span v-if="b.id === bookStore.currentId" class="now">当前</span>
            <span class="acts">
              <button class="naw-textbtn" @click="openBookEdit(b)">改名</button>
              <button
                class="naw-textbtn naw-textbtn--seal"
                :disabled="books.length <= 1"
                @click="removeBook(b)"
              >
                删除
              </button>
            </span>
          </div>
        </div>
      </template>

      <!-- ==================== 分类 ==================== -->
      <template v-else-if="tab === 'category'">
        <div class="pane-head">
          <span class="naw-label">分类</span>
          <div class="type-switch">
            <button
              :class="['ts', { 'ts--on': catType === 'EXPENSE' }]"
              @click="switchCatType('EXPENSE')"
            >
              支出
            </button>
            <button
              :class="['ts', { 'ts--on': catType === 'INCOME' }]"
              @click="switchCatType('INCOME')"
            >
              收入
            </button>
            <!-- ★ 一级分类入口放在这里：之前只有每个大类下的「+ 子类」，
                 用户找不到"新建一级分类"，只能在一级分类里找子类的子类。 -->
            <button class="ts ts--add" @click="startAddTop()">+ 一级分类</button>
          </div>
        </div>

        <!-- 一级分类新增行：提交 parentId = 0 -->
        <div v-if="addingUnder === TOP" class="addrow addrow--top">
          <span class="addrow-label">新的一级分类</span>
          <input
            v-model="newCatName"
            class="naw-input addinput"
            placeholder="如 宠物、育儿"
            maxlength="20"
            autofocus
            @keyup.enter="commitAddTop()"
            @keyup.esc="addingUnder = null"
          />
          <button class="naw-textbtn" @click="commitAddTop()">确定</button>
          <button class="naw-textbtn" @click="addingUnder = null">取消</button>
        </div>

        <div v-if="catLoading" class="naw-empty"><span class="naw-dots"><i /><i /><i /></span></div>
        <div v-else-if="!catTree.length" class="naw-empty">这一类还没有分类</div>

        <div v-else class="cats">
          <div v-for="p in catTree" :key="p.id" class="cgroup">
            <div class="chead">
              <span class="cname">{{ p.name }}</span>
              <span class="cmeta">{{ p.children.length }} 个子类</span>
              <span class="cacts">
                <button class="naw-textbtn" @click="startAdd(p.id)">+ 子类</button>
                <button v-if="!p.isPreset" class="naw-textbtn" @click="renameCat(p)">改名</button>
                <button
                  v-if="!p.isPreset"
                  class="naw-textbtn naw-textbtn--seal"
                  @click="removeCat(p)"
                >
                  删除
                </button>
              </span>
            </div>

            <div v-if="addingUnder === p.id" class="addrow">
              <input
                v-model="newCatName"
                class="naw-input addinput"
                placeholder="子分类名"
                maxlength="20"
                @keyup.enter="commitAdd(p.id)"
                @keyup.esc="addingUnder = null"
              />
              <button class="naw-textbtn" @click="commitAdd(p.id)">确定</button>
              <button class="naw-textbtn" @click="addingUnder = null">取消</button>
            </div>

            <div v-if="p.children.length" class="kids">
              <span v-for="c in p.children" :key="c.id" class="kid">
                {{ c.name }}
                <button
                  v-if="!c.isPreset"
                  class="kx"
                  title="删除"
                  @click="removeCat(c)"
                >
                  ×
                </button>
              </span>
            </div>
          </div>
        </div>
      </template>

      <!-- ==================== AI 人设 ==================== -->
      <template v-else-if="tab === 'persona'">
        <div class="pane-head">
          <span class="naw-label">AI 人设</span>
          <!-- ★ 新建入口：之前只能从下面 5 个预设里复制，无法从零创建 -->
          <button class="naw-textbtn head-act" @click="openPersonaCreate">+ 新建角色</button>
        </div>

        <div v-if="personaLoading" class="naw-empty"><span class="naw-dots"><i /><i /><i /></span></div>

        <template v-else>
          <div class="sub-head">
            <span class="naw-label">当前启用</span>
          </div>
          <div v-if="activePersona" class="active-card">
            <span class="an">{{ activePersona.name }}</span>
            <span class="ad">{{ activePersona.personality }}</span>
          </div>
          <div v-else class="naw-empty">还没启用人设，AI 会用默认口吻</div>

          <div class="sub-head">
            <span class="naw-label">我的角色</span>
          </div>
          <div v-if="!personas.length" class="naw-empty">
            还没有自己的角色。点右上角「+ 新建角色」，或者从下面的预设里挑一个来改。
          </div>
          <div v-else class="rows">
            <div v-for="p in personas" :key="p.id" class="row">
              <span class="nm">{{ p.name }}</span>
              <span class="pd">
                {{ [p.relation, p.address].filter(Boolean).join(' · ') || '未填关系与称呼' }}
              </span>
              <span class="acts">
                <button class="naw-textbtn" @click="openPersonaEdit(p)">改</button>
                <button class="naw-textbtn" @click="activate(p)">启用</button>
                <button class="naw-textbtn naw-textbtn--seal" @click="removePersona(p)">删除</button>
              </span>
            </div>
          </div>

          <div class="sub-head">
            <span class="naw-label">系统预设 <span class="naw-label__sub">复制后可改</span></span>
          </div>
          <div class="presets">
            <div v-for="p in presets" :key="p.id" class="preset">
              <span class="pn">{{ p.name }}</span>
              <span class="pp">{{ p.personality }}</span>
              <button class="naw-textbtn" @click="usePreset(p)">用这个</button>
            </div>
          </div>
        </template>
      </template>

      <!-- ==================== 账号 ==================== -->
      <template v-else>
        <div class="pane-head">
          <span class="naw-label">账号</span>
        </div>

        <div class="info">
          <div class="irow"><span class="ik">手机号</span><span class="iv">{{ userStore.user?.phone }}</span></div>
          <div class="irow"><span class="ik">昵称</span><span class="iv">{{ userStore.user?.nickname || '未设置' }}</span></div>
          <div class="irow">
            <span class="ik">会员</span>
            <span class="iv">{{ userStore.isMember ? '终身会员' : '普通用户' }}</span>
          </div>
        </div>

        <div class="sub-head">
          <span class="naw-label">修改密码</span>
        </div>
        <p class="pwtip">
          用手机验证码验证身份，<b>不需要旧密码</b>。验证码 60 秒一条，登录和改密码共用配额。
        </p>

        <div class="pwform">
          <label class="pf">
            <span class="k">验证码</span>
            <div class="prow">
              <input v-model="pwForm.code" class="naw-input" maxlength="6" placeholder="6 位数字" />
              <button class="naw-textbtn" :disabled="!canSendCode" @click="sendPwCode">
                {{ countdown > 0 ? `${countdown}s` : '获取验证码' }}
              </button>
            </div>
          </label>

          <label class="pf">
            <span class="k">新密码</span>
            <input v-model="pwForm.newPassword" type="password" class="naw-input" placeholder="6–32 位" />
          </label>

          <button class="naw-btn pwsubmit" :disabled="pwBusy" @click="submitPassword">
            {{ pwBusy ? '提交中…' : '更新密码' }}
          </button>
        </div>
      </template>
    </section>
  </div>

  <!-- 账本弹层 -->
  <div v-if="bookDialog" class="mask" @click.self="bookDialog = false">
    <div class="dialog">
      <div class="dialog-head">
        <span>{{ bookEditing ? '改账本名' : '新建账本' }}</span>
        <button class="x" @click="bookDialog = false">×</button>
      </div>
      <label class="df">
        <span class="k">名称</span>
        <input v-model="bookForm.name" class="naw-input" maxlength="20" placeholder="如 日常账本" />
      </label>
      <div class="dialog-foot">
        <button class="naw-textbtn" @click="bookDialog = false">取消</button>
        <button class="naw-btn" :disabled="bookSaving" @click="saveBook">
          {{ bookSaving ? '保存中…' : '保存' }}
        </button>
      </div>
    </div>
  </div>

  <!-- ==================== AI 人设弹层 ==================== -->
  <div v-if="personaDialog" class="mask" @click.self="personaDialog = false">
    <div class="dialog dialog--wide">
      <div class="dialog-head">
        <span>{{ personaEditingId ? '修改角色' : '新建角色' }}</span>
        <button class="x" @click="personaDialog = false">×</button>
      </div>

      <p class="dialog-tip">
        这是 AI 跟你说话时的身份。只有「名字」必填，其他留空就用中性语气。
      </p>

      <div class="pfields">
        <label class="df df--full">
          <span class="k">名字 <em>必填</em></span>
          <input
            v-model="personaForm.name"
            class="naw-input"
            maxlength="20"
            placeholder="如 小满、老周"
            @keyup.enter="savePersona"
          />
        </label>

        <div class="df df--full">
          <span class="k">性别</span>
          <div class="chips">
            <button
              v-for="g in GENDER_OPTIONS"
              :key="g.k"
              type="button"
              :class="['chipbtn', { 'chipbtn--on': personaForm.gender === g.k }]"
              @click="personaForm.gender = g.k"
            >
              {{ g.l }}
            </button>
          </div>
        </div>

        <label class="df">
          <span class="k">与你的关系</span>
          <input
            v-model="personaForm.relation"
            class="naw-input"
            maxlength="20"
            placeholder="朋友 / 管家 / 宠物…"
          />
          <div class="hints">
            <button
              v-for="h in RELATION_HINTS"
              :key="h"
              type="button"
              class="hintbtn"
              @click="personaForm.relation = h"
            >
              {{ h }}
            </button>
          </div>
        </label>

        <label class="df">
          <span class="k">它怎么称呼你</span>
          <input
            v-model="personaForm.address"
            class="naw-input"
            maxlength="20"
            placeholder="你 / 主人 / 老板…"
          />
          <div class="hints">
            <button
              v-for="h in ADDRESS_HINTS"
              :key="h"
              type="button"
              class="hintbtn"
              @click="personaForm.address = h"
            >
              {{ h }}
            </button>
          </div>
        </label>

        <label class="df df--full">
          <span class="k">性格设定 <em>{{ personaForm.personality.length }} / 200</em></span>
          <textarea
            v-model="personaForm.personality"
            class="naw-input ptext"
            maxlength="200"
            rows="3"
            placeholder="如 说话简短，偶尔提醒你别太省；看到乱花钱会直接说。"
          />
        </label>
      </div>

      <div class="dialog-foot">
        <span class="tip">创建后记得点「启用」，AI 才会用它说话</span>
        <button class="naw-textbtn" @click="personaDialog = false">取消</button>
        <button class="naw-btn" :disabled="personaSaving" @click="savePersona">
          {{ personaSaving ? '保存中…' : personaEditingId ? '保存' : '创建' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.settings {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 148px 1fr;
  padding: 0 var(--naw-page-x);
  overflow: hidden;
}

/* 左 tab */
.side {
  padding: 16px 20px 0 0;
  border-right: 1px solid var(--naw-rule);
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.tab {
  text-align: left;
  background: none;
  border: none;
  border-left: 2px solid transparent;
  padding: 7px 10px;
  font-size: var(--naw-fs-md);
  color: var(--naw-ink-2);
  cursor: pointer;
  transition:
    color 0.14s,
    border-color 0.14s;
}

.tab:hover {
  color: var(--naw-ink);
}

.tab--on {
  color: var(--naw-ink);
  border-left-color: var(--naw-seal);
}

/* 内容 */
.pane {
  padding: 16px 0 16px 26px;
  overflow-y: auto;
  scrollbar-gutter: stable;
  min-height: 0;
}

.pane-head {
  display: flex;
  align-items: baseline;
  padding-bottom: 10px;
  border-bottom: 1.5px solid var(--naw-ink);
}

.head-act {
  margin-left: auto;
  font-size: var(--naw-fs-sm);
}

.sub-head {
  margin-top: 20px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--naw-rule);
}

/* 通用行 */
.rows {
  padding-top: 4px;
}

.row {
  display: flex;
  align-items: baseline;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid var(--naw-rule);
}

.row .nm {
  font-size: var(--naw-fs-md);
  color: var(--naw-ink);
}

.row .now,
.row .pd {
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-3);
}

.row .now {
  color: var(--naw-seal);
  border: 1px solid var(--naw-seal);
  padding: 0 5px;
  border-radius: 2px;
}

.row .acts {
  margin-left: auto;
  display: flex;
  gap: 12px;
  opacity: 0;
  transition: opacity 0.14s;
}

.row:hover .acts {
  opacity: 1;
}

.row .acts .naw-textbtn {
  font-size: var(--naw-fs-xs);
}

/* 分类 */
.type-switch {
  margin-left: auto;
  display: flex;
  gap: 12px;
}

.ts {
  background: none;
  border: none;
  padding: 0 0 1px;
  font-size: var(--naw-fs-sm);
  color: var(--naw-ink-3);
  border-bottom: 1px solid transparent;
  cursor: pointer;
}

.ts--on {
  color: var(--naw-ink);
  border-bottom-color: var(--naw-seal);
}

/* ★ 一级分类入口：与"支出/收入"同排，但用加号前缀表达它是"动作"而非"切换" */
.ts--add {
  margin-left: 6px;
  padding-left: 10px;
  border-left: 1px solid var(--naw-rule);
  color: var(--naw-ink-2);
}

.ts--add:hover {
  color: var(--naw-ink);
  border-bottom-color: var(--naw-ink-3);
}

.cats {
  padding-top: 6px;
}

.cgroup {
  padding: 12px 0;
  border-bottom: 1px solid var(--naw-rule);
}

.chead {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.cname {
  font-size: var(--naw-fs-md);
  color: var(--naw-ink);
}

.cmeta {
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-4);
}

.cacts {
  margin-left: auto;
  display: flex;
  gap: 12px;
  opacity: 0;
  transition: opacity 0.14s;
}

.cgroup:hover .cacts {
  opacity: 1;
}

.cacts .naw-textbtn {
  font-size: var(--naw-fs-xs);
}

.addrow {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
}

/* 一级分类新增行：整行铺开，和下面的分类组用虚线隔开 */
.addrow--top {
  padding: 12px 0;
  margin-top: 10px;
  border-bottom: 1px solid var(--naw-rule);
}

.addrow-label {
  flex-shrink: 0;
  font-size: var(--naw-fs-xs);
  letter-spacing: 0.1em;
  color: var(--naw-ink-3);
}

.addinput {
  max-width: 180px;
  padding: 3px 7px;
}

.kids {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 14px;
  margin-top: 8px;
  padding-left: 2px;
}

.kid {
  font-size: var(--naw-fs-sm);
  color: var(--naw-ink-2);
  display: inline-flex;
  align-items: center;
  gap: 3px;
}

.kx {
  background: none;
  border: none;
  padding: 0;
  color: var(--naw-ink-4);
  cursor: pointer;
  font-size: 12px;
  line-height: 1;
  opacity: 0;
}

.kid:hover .kx {
  opacity: 1;
}

.kx:hover {
  color: var(--naw-seal);
}

/* 人设 */
.active-card {
  padding: 12px 0;
  border-bottom: 1px solid var(--naw-rule);
}

.an {
  font-size: var(--naw-fs-lg);
  color: var(--naw-ink);
}

.ad {
  margin-left: 10px;
  font-size: var(--naw-fs-sm);
  color: var(--naw-ink-3);
}

.presets {
  padding-top: 6px;
}

.preset {
  display: grid;
  grid-template-columns: 110px 1fr auto;
  align-items: baseline;
  gap: 12px;
  padding: 9px 0;
  border-bottom: 1px solid var(--naw-rule);
}

.pn {
  font-size: var(--naw-fs-base);
  color: var(--naw-ink);
}

.pp {
  font-size: var(--naw-fs-sm);
  color: var(--naw-ink-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 账号 */
.info {
  padding-top: 6px;
}

.irow {
  display: flex;
  align-items: baseline;
  gap: 16px;
  padding: 9px 0;
  border-bottom: 1px solid var(--naw-rule);
}

.ik {
  width: 60px;
  font-size: var(--naw-fs-sm);
  color: var(--naw-ink-3);
}

.iv {
  font-size: var(--naw-fs-base);
  color: var(--naw-ink);
}

.pwtip {
  margin: 10px 0 0;
  font-size: var(--naw-fs-sm);
  line-height: 1.75;
  color: var(--naw-ink-3);
  max-width: 460px;
}

.pwtip b {
  color: var(--naw-ink-2);
  font-weight: 400;
}

.pwform {
  margin-top: 14px;
  max-width: 300px;
}

.pf {
  display: block;
  margin-bottom: 12px;
}

.pf .k {
  display: block;
  font-size: var(--naw-fs-xs);
  letter-spacing: 0.12em;
  color: var(--naw-ink-3);
  margin-bottom: 5px;
}

.prow {
  display: flex;
  align-items: center;
  gap: 10px;
}

.prow .naw-input {
  flex: 1;
}

.pwsubmit {
  margin-top: 6px;
}

/* 弹层 */
.mask {
  position: fixed;
  inset: 0;
  z-index: 100;
  background: rgba(28, 25, 23, 0.28);
  display: grid;
  place-items: center;
}

.dialog {
  width: 340px;
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
  justify-content: flex-end;
  gap: 14px;
  margin-top: 18px;
  padding-top: 12px;
  border-top: 1px solid var(--naw-rule);
}

/* ==================== AI 人设弹层 ==================== */
.dialog--wide {
  width: 460px;
}

.dialog-tip {
  margin: 12px 0 0;
  font-size: var(--naw-fs-sm);
  line-height: 1.7;
  color: var(--naw-ink-3);
}

/* 字段网格：两列，整行字段跨两列 */
.pfields {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
}

.df--full {
  grid-column: 1 / -1;
}

/* 「必填」这类标记：小字，朱红色 */
.df .k em {
  font-style: normal;
  margin-left: 4px;
  color: var(--naw-seal);
  letter-spacing: 0;
}

/* 性别：胶囊按钮组 */
.chips {
  display: flex;
  gap: 8px;
}

.chipbtn {
  padding: 4px 14px;
  background: transparent;
  border: 1px solid var(--naw-rule-2);
  border-radius: 2px;
  font-family: inherit;
  font-size: var(--naw-fs-base);
  color: var(--naw-ink-2);
  cursor: pointer;
  transition:
    border-color 0.14s,
    color 0.14s,
    background 0.14s;
}

.chipbtn:hover {
  border-color: var(--naw-ink-3);
  color: var(--naw-ink);
}

/* 选中：墨底白字，和全站"选中"一致 */
.chipbtn--on {
  background: var(--naw-ink);
  border-color: var(--naw-ink);
  color: var(--naw-paper);
}

/* 提示词胶囊：点了就填进去，省得手打 */
.hints {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin-top: 6px;
}

.hintbtn {
  padding: 1px 8px;
  background: var(--naw-paper-2);
  border: 1px solid transparent;
  border-radius: 2px;
  font-family: inherit;
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-3);
  cursor: pointer;
  transition:
    color 0.12s,
    border-color 0.12s;
}

.hintbtn:hover {
  color: var(--naw-ink);
  border-color: var(--naw-rule-2);
}

.ptext {
  resize: vertical;
  min-height: 62px;
  line-height: 1.7;
  font-family: inherit;
}

.dialog-foot .tip {
  margin-right: auto;
  font-size: var(--naw-fs-xs);
  color: var(--naw-ink-4);
  line-height: 1.5;
  max-width: 190px;
}
</style>
