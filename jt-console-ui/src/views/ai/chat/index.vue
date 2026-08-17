<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { useDialog, useMessage } from 'naive-ui';
import { streamChat } from '@/service/ai-stream';
import type { AiActionEvent, AiChatMessage, AiToolEvent, AiUsageEvent } from '@/service/ai-stream';
import {
  clearAiMessages,
  deleteAiConversation,
  fetchAiConversations,
  fetchAiMessages
} from '@/service/api/ai';
import type { AiConversation } from '@/service/api/ai';
import MarkdownView from './modules/markdown-view.vue';
import ActionCard from './modules/action-card.vue';

defineOptions({ name: 'AiChat' });

interface ToolTrace {
  name: string;
  brief: string;
  done: boolean;
  ok: boolean;
}

interface Bubble {
  role: 'user' | 'assistant';
  content: string;
  tools: ToolTrace[];
  actions: AiActionEvent[];
}

const dialog = useDialog();
const message = useMessage();

const conversations = ref<AiConversation[]>([]);
const activeId = ref<number | null>(null);
const bubbles = ref<Bubble[]>([]);
const input = ref('');
const streaming = ref(false);
const loading = ref(false);
const usage = ref<AiUsageEvent | null>(null);
const errorText = ref('');
const listRef = ref<HTMLElement | null>(null);
/**
 * 尚未回报给模型的动作执行结果。
 *
 * 模型自己看不到卡片被点了没有、成没成功。不把结果带回去的话会陷入死循环：
 * 用户说「确认失败了」，模型不知道发生过什么，只会再让他去确认一次。
 */
const pendingOutcomes = ref<string[]>([]);
let abort: (() => void) | null = null;
let followUpTimer: ReturnType<typeof setTimeout> | null = null;

/**
 * 卡片执行完就自动续一轮，不必等用户再打一句话。
 *
 * 点完确认看到「已执行」却没有下文，会让人以为流程断了。攒 300 毫秒再发是因为一条消息里
 * 可能有多张卡片，用户会连点几下——那应该合成一轮，而不是每点一次就烧一次调用额度。
 */
function recordOutcome(outcome: string) {
  pendingOutcomes.value.push(outcome);
  if (followUpTimer) clearTimeout(followUpTimer);
  followUpTimer = setTimeout(() => {
    followUpTimer = null;
    if (!streaming.value && pendingOutcomes.value.length) runTurn(null);
  }, 300);
}

const SUGGESTIONS = [
  '现在有多少台车在线？',
  '昨天哪些车告警最多？',
  '帮我建一台车，车牌粤B12345，设备号 13800138000'
];

async function scrollToBottom() {
  await nextTick();
  const el = listRef.value;
  if (el) el.scrollTop = el.scrollHeight;
}

async function loadConversations() {
  const { data } = await fetchAiConversations();
  conversations.value = data ?? [];
}

/** 打开一个历史会话。历史里只还原文本——工具活动与动作卡片是当轮的临时状态，不持久化。 */
async function openConversation(id: number) {
  if (streaming.value) return;
  loading.value = true;
  try {
    const { data } = await fetchAiMessages(id);
    activeId.value = id;
    bubbles.value = (data ?? []).map(m => ({
      role: m.role,
      content: m.content,
      tools: [],
      actions: []
    }));
    errorText.value = '';
    void scrollToBottom();
  } finally {
    loading.value = false;
  }
}

/** 新建对话：先清空视图并解绑会话，真正的会话行等第一次提问时才创建——避免点一下就攒出一堆空对话。 */
function newConversation() {
  if (streaming.value) return;
  activeId.value = null;
  bubbles.value = [];
  errorText.value = '';
}

function clearCurrent() {
  const id = activeId.value;
  if (!id || streaming.value) return;
  dialog.warning({
    title: '清空对话记录',
    content: '将删除这个对话里的全部消息，对话本身保留。此操作不可撤销。',
    positiveText: '清空',
    negativeText: '取消',
    onPositiveClick: async () => {
      await clearAiMessages(id);
      bubbles.value = [];
      message.success('已清空');
      await loadConversations();
    }
  });
}

function removeConversation(item: AiConversation) {
  if (streaming.value) return;
  dialog.warning({
    title: '删除对话',
    content: `确定删除「${item.title || '新对话'}」？此操作不可撤销。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      await deleteAiConversation(item.id);
      if (activeId.value === item.id) newConversation();
      message.success('已删除');
      await loadConversations();
    }
  });
}

function send(text?: string) {
  const question = (text ?? input.value).trim();
  if (!question || streaming.value) return;
  input.value = '';
  runTurn(question);
}

/**
 * 跑一轮对话。
 *
 * @param question 用户这句话；为 null 表示这是动作执行后的自动续轮，不显示用户气泡——
 *                 用户没说话，凭空多出一条「[系统] …」的用户消息只会让对话看起来很怪
 */
function runTurn(question: string | null) {
  if (streaming.value) return;

  errorText.value = '';
  if (question) {
    bubbles.value.push({ role: 'user', content: question, tools: [], actions: [] });
  }
  const reply: Bubble = { role: 'assistant', content: '', tools: [], actions: [] };
  bubbles.value.push(reply);
  streaming.value = true;
  void scrollToBottom();

  // 只送最终文本，不送工具调用记录——那些只在当轮循环内存在。
  const history: AiChatMessage[] = bubbles.value
    .filter(b => b.role === 'user' || b.content)
    .map(b => ({ role: b.role, content: b.content }));

  // 卡片的执行结果作为一条系统口吻的消息带给模型；它不进 bubbles，因此界面上看不到。
  if (pendingOutcomes.value.length) {
    const note = `[系统] 动作执行结果：${pendingOutcomes.value.join('；')}。`
      + '请据此简短告知用户结果；若失败，说明原因并给出下一步建议。';
    history.push({ role: 'user', content: note });
    pendingOutcomes.value = [];
  }
  // 末条必须是用户消息，否则模型会接着自己上一句往下说。
  if (!history.length || history[history.length - 1].role !== 'user') return;

  abort = streamChat(
    history,
    {
      onMeta(meta) {
        // 新会话的 id 由后端建好回传；后续轮次带上它才能续在同一段对话里。
        if (meta.conversationId) activeId.value = meta.conversationId;
      },
      onDelta(chunk) {
        reply.content += chunk;
        void scrollToBottom();
      },
      onTool(event: AiToolEvent) {
        if (event.phase === 'start') {
          reply.tools.push({ name: event.name, brief: event.brief, done: false, ok: true });
        } else {
          const trace = [...reply.tools].reverse().find(t => t.name === event.name && !t.done);
          if (trace) {
            trace.done = true;
            trace.ok = event.ok !== false;
          }
        }
        void scrollToBottom();
      },
      onAction(event) {
        reply.actions.push(event);
        void scrollToBottom();
      },
      onUsage(event) {
        usage.value = event;
      },
      async onDone() {
        streaming.value = false;
        abort = null;
        await loadConversations();
      },
      onError(_code, msg) {
        streaming.value = false;
        abort = null;
        errorText.value = msg;
        if (!reply.content) bubbles.value.pop();
      }
    },
    activeId.value
  );
}

function stop() {
  abort?.();
  abort = null;
  streaming.value = false;
}

onMounted(async () => {
  await loadConversations();
  // 默认接着最近一次聊，而不是每次进来都对着空白页。
  if (conversations.value.length) await openConversation(conversations.value[0].id);
});

onBeforeUnmount(() => {
  if (followUpTimer) clearTimeout(followUpTimer);
  abort?.();
});
</script>

<template>
  <div class="h-full flex gap-12px overflow-hidden">
    <NCard
      :bordered="false"
      size="small"
      class="w-240px flex-shrink-0 card-wrapper"
      content-class="flex-col-stretch overflow-hidden"
    >
      <template #header>
        <span class="text-14px">对话</span>
      </template>
      <template #header-extra>
        <NButton size="tiny" type="primary" secondary :disabled="streaming" @click="newConversation">
          <template #icon>
            <SvgIcon icon="mdi:plus" />
          </template>
          新建
        </NButton>
      </template>
      <div class="flex-1 overflow-y-auto">
        <p v-if="!conversations.length" class="py-16px text-center text-13px text-gray-400">还没有对话</p>
        <div
          v-for="item in conversations"
          :key="item.id"
          class="group mb-4px flex cursor-pointer items-center gap-4px rd-6px px-8px py-6px hover:bg-gray-100 dark:hover:bg-dark-600"
          :class="activeId === item.id ? 'bg-primary/10 text-primary' : ''"
          @click="openConversation(item.id)"
        >
          <SvgIcon icon="mdi:message-text-outline" class="flex-shrink-0 text-14px" />
          <span class="flex-1 truncate text-13px">{{ item.title || '新对话' }}</span>
          <NButton size="tiny" quaternary class="opacity-0 group-hover:opacity-100" @click.stop="removeConversation(item)">
            <template #icon>
              <SvgIcon icon="mdi:delete-outline" />
            </template>
          </NButton>
        </div>
      </div>
    </NCard>

    <NCard
      :bordered="false"
      size="small"
      class="flex-1 card-wrapper"
      content-class="flex-col-stretch overflow-hidden"
    >
      <template #header>
        <div class="flex items-center gap-8px">
          <SvgIcon icon="mdi:robot-outline" class="text-18px" />
          <span>AI 助手</span>
        </div>
      </template>
      <template #header-extra>
        <div class="flex items-center gap-12px">
          <span v-if="usage" class="text-12px text-gray-400">
            本月已用 {{ usage.monthlyUsed }}{{ usage.monthlyLimit > 0 ? ` / ${usage.monthlyLimit}` : '（不限）' }}
          </span>
          <NButton v-if="activeId && bubbles.length" size="tiny" quaternary :disabled="streaming" @click="clearCurrent">
            <template #icon>
              <SvgIcon icon="mdi:broom" />
            </template>
            清空记录
          </NButton>
        </div>
      </template>

      <NSpin :show="loading" class="flex-1 overflow-hidden" content-class="h-full">
        <div ref="listRef" class="h-full overflow-y-auto px-4px py-8px">
          <div v-if="!bubbles.length" class="flex-col-center gap-12px py-48px text-gray-400">
            <SvgIcon icon="mdi:robot-happy-outline" class="text-48px" />
            <p class="text-14px">问我平台上的任何数据，也可以让我帮你建档、建围栏、处置告警。</p>
            <div class="flex flex-wrap justify-center gap-8px">
              <NButton v-for="s in SUGGESTIONS" :key="s" size="small" secondary @click="send(s)">{{ s }}</NButton>
            </div>
          </div>

          <div v-for="(bubble, index) in bubbles" :key="index" class="mb-16px">
            <div v-if="bubble.role === 'user'" class="flex justify-end">
              <div class="max-w-75% rd-8px bg-primary px-12px py-8px text-white">{{ bubble.content }}</div>
            </div>
            <div v-else class="flex justify-start">
              <div class="max-w-90% w-full">
                <NCollapse v-if="bubble.tools.length" class="mb-8px">
                  <NCollapseItem :title="`执行了 ${bubble.tools.length} 次查询`" name="tools">
                    <div v-for="(tool, i) in bubble.tools" :key="i" class="flex items-center gap-6px py-2px text-13px">
                      <SvgIcon
                        :icon="
                          tool.done
                            ? tool.ok
                              ? 'mdi:check-circle-outline'
                              : 'mdi:alert-circle-outline'
                            : 'mdi:loading'
                        "
                        :class="tool.done ? (tool.ok ? 'text-success' : 'text-error') : 'animate-spin'"
                      />
                      <span class="text-gray-500">{{ tool.brief }}</span>
                    </div>
                  </NCollapseItem>
                </NCollapse>

                <div class="rd-8px bg-gray-100 px-12px py-8px dark:bg-dark-700">
                  <MarkdownView :content="bubble.content" />
                  <NSpin v-if="streaming && index === bubbles.length - 1 && !bubble.content" size="small" />
                </div>

                <ActionCard
                  v-for="action in bubble.actions"
                  :key="action.proposalId"
                  :action="action"
                  @settled="recordOutcome"
                />
              </div>
            </div>
          </div>

          <NAlert v-if="errorText" type="error" :bordered="false" class="mt-8px">{{ errorText }}</NAlert>
        </div>
      </NSpin>

      <div class="flex items-end gap-8px pt-8px">
        <NInput
          v-model:value="input"
          type="textarea"
          placeholder="问点什么，或让我帮你做点什么…（Enter 发送，Shift+Enter 换行）"
          :autosize="{ minRows: 1, maxRows: 5 }"
          :disabled="streaming"
          @keydown.enter.exact.prevent="send()"
        />
        <NButton v-if="streaming" type="warning" @click="stop">停止</NButton>
        <NButton v-else type="primary" :disabled="!input.trim()" @click="send()">发送</NButton>
      </div>
    </NCard>
  </div>
</template>
