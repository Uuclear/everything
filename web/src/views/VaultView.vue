<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  NLayout,
  NLayoutHeader,
  NLayoutContent,
  NButton,
  NSpace,
  NCard,
  NInput,
  NList,
  NListItem,
  NThing,
  NEmpty,
  NModal,
  NSpin,
  useMessage,
} from 'naive-ui'
import { useAuthStore } from '../stores/auth'
import { api, getAccessToken, type RemoteRecord } from '../api/client'
import { fromBase64, openRecord, sealRecord, toBase64 } from '../crypto/envelope'

interface Note {
  id: string
  title: string
  body: string
  updatedAt: number
}

const router = useRouter()
const message = useMessage()
const auth = useAuthStore()

// 刷新页面后令牌还在但内存中的主密钥已消失，需要重新输入主密码登录解锁。
const unlockPassword = ref('')
const unlocking = ref(false)

const notes = ref<Note[]>([])
const loading = ref(false)
const showCreate = ref(false)
const newTitle = ref('')
const newBody = ref('')
const saving = ref(false)
let pollTimer: number | undefined

const sortedNotes = computed(() => [...notes.value].sort((a, b) => b.updatedAt - a.updatedAt))

onMounted(async () => {
  if (!getAccessToken()) {
    router.replace({ name: 'welcome' })
    return
  }
  if (auth.unlocked) await refresh()
  // SSE 预留：EventSource 无法携带 Authorization 头，阶段 1 先用轮询，
  // 后续改为签名查询令牌通道（见 docs/api.md）。
  pollTimer = window.setInterval(() => {
    if (auth.unlocked) void refresh()
  }, 10_000)
})

onUnmounted(() => {
  if (pollTimer) window.clearInterval(pollTimer)
})

async function unlock() {
  unlocking.value = true
  try {
    await auth.login(auth.username, unlockPassword.value)
    unlockPassword.value = ''
    message.success('资料库已解锁')
    await refresh()
  } catch {
    message.error('主密码错误')
  } finally {
    unlocking.value = false
  }
}

async function refresh() {
  loading.value = true
  try {
    const sodium = auth.sodium!
    const mk = auth.masterKey!
    const { records: remote } = await api.listRecords(0)
    const decrypted: Note[] = []
    for (const r of remote as RemoteRecord[]) {
      if (r.module !== 'note' || r.deleted) continue
      try {
        const plain = openRecord(sodium, mk, fromBase64(r.ciphertext), r.id, r.module, r.version)
        const obj = JSON.parse(new TextDecoder().decode(plain)) as { title: string; body: string }
        decrypted.push({ id: r.id, title: obj.title, body: obj.body, updatedAt: r.updated_at })
      } catch {
        // 单条解密失败不影响其余记录（可能来自尚未授权的共享空间）
      }
    }
    notes.value = decrypted
  } catch {
    message.error('同步失败')
  } finally {
    loading.value = false
  }
}

async function createNote() {
  if (!newTitle.value) {
    message.warning('请填写标题')
    return
  }
  saving.value = true
  try {
    const sodium = auth.sodium!
    const mk = auth.masterKey!
    const id = crypto.randomUUID()
    const version = 1
    const now = Date.now()
    const plaintext = new TextEncoder().encode(
      JSON.stringify({ title: newTitle.value, body: newBody.value }),
    )
    const sealed = sealRecord(sodium, mk, plaintext, id, 'note', version)
    await api.pushRecords([
      {
        id,
        module: 'note',
        type: 'secure_note',
        ciphertext: toBase64(sealed),
        version,
        created_at: now,
        updated_at: now,
        deleted: false,
      },
    ])
    newTitle.value = ''
    newBody.value = ''
    showCreate.value = false
    message.success('已加密保存并同步')
    await refresh()
  } catch {
    message.error('保存失败')
  } finally {
    saving.value = false
  }
}

function logout() {
  auth.logout()
  router.replace({ name: 'welcome' })
}
</script>

<template>
  <n-layout class="page">
    <!-- 锁屏：主密钥只存在于内存 -->
    <div v-if="!auth.unlocked" class="lock">
      <n-card class="lock-card" title="资料库已锁定">
        <n-input
          v-model:value="unlockPassword"
          type="password"
          show-password-on="click"
          placeholder="输入主密码解锁（{{ auth.username }}）"
          @keyup.enter="unlock"
        />
        <n-space class="lock-actions">
          <n-button type="primary" :loading="unlocking" @click="unlock">解锁</n-button>
          <n-button @click="logout">切换账户</n-button>
        </n-space>
      </n-card>
    </div>

    <template v-else>
      <n-layout-header bordered class="header">
        <strong>Everything · 加密笔记</strong>
        <n-space>
          <n-button size="small" @click="refresh">同步</n-button>
          <n-button size="small" type="primary" @click="showCreate = true">新建</n-button>
          <n-button size="small" @click="logout">退出</n-button>
        </n-space>
      </n-layout-header>
      <n-layout-content class="content">
        <n-spin :show="loading">
          <n-card v-if="sortedNotes.length === 0">
            <n-empty description="还没有记录，点击右上角「新建」创建第一条加密笔记" />
          </n-card>
          <n-list v-else bordered>
            <n-list-item v-for="n in sortedNotes" :key="n.id">
              <n-thing :title="n.title" :description="new Date(n.updatedAt).toLocaleString()">
                <pre class="body">{{ n.body }}</pre>
              </n-thing>
            </n-list-item>
          </n-list>
        </n-spin>
      </n-layout-content>

      <n-modal v-model:show="showCreate" preset="card" title="新建加密笔记" class="create-modal">
        <n-space vertical>
          <n-input v-model:value="newTitle" placeholder="标题（本地加密后才上传）" />
          <n-input
            v-model:value="newBody"
            type="textarea"
            :rows="6"
            placeholder="正文"
          />
          <n-button type="primary" :loading="saving" @click="createNote">加密保存</n-button>
        </n-space>
      </n-modal>
    </template>
  </n-layout>
</template>

<style scoped>
.page {
  min-height: 100vh;
}
.lock {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1f2a44 0%, #0f1525 100%);
}
.lock-card {
  width: 400px;
  max-width: 92vw;
}
.lock-actions {
  margin-top: 14px;
}
.header {
  height: 56px;
  padding: 0 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
}
.content {
  max-width: 760px;
  margin: 0 auto;
  padding: 20px;
}
.body {
  white-space: pre-wrap;
  word-break: break-word;
  margin: 6px 0 0;
  font-family: inherit;
}
:deep(.create-modal) {
  width: 520px;
  max-width: 92vw;
}
</style>
