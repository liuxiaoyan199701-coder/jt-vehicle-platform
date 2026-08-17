<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref } from 'vue';
import { streamChat } from '@/service/ai-stream';
import type { AiActionEvent, AiChatMessage, AiToolEvent, AiUsageEvent } from '@/service/ai-stream';
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

const bubbles = ref<Bubble[]>([]);
const input = ref('');
const streaming = ref(false);
const usage = ref<AiUsageEvent | null>(null);
const errorText = ref('');
const listRef = ref<HTMLElement | null>(null);
let abort: (() => void) | null = null;

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

function send(text?: string) {
  const question = (text ?? input.value).trim();
  if (!question || streaming.value) return;

  errorText.value = '';
  input.value = '';
  bubbles.value.push({ role: 'user', content: question, tools: [], actions: [] });
  const reply: Bubble = { role: 'assistant', content: '', tools: [], actions: [] };
  bubbles.value.push(reply);
  streaming.value = true;
  void scrollToBottom();

  // 只送最终文本，不送工具调用记录——那些只在当轮循环内存在，后端也不需要。
  const history: AiChatMessage[] = bubbles.value
    .filter(b => b.content || b.role === 'user')
    .map(b => ({ role: b.role, content: b.content }));

  abort = streamChat(history, {
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
    onDone() {
      streaming.value = false;
      abort = null;
    },
    onError(_code, msg) {
      streaming.value = false;
      abort = null;
      errorText.value = msg;
      if (!reply.content) bubbles.value.pop();
    }
  });
}

function stop() {
  abort?.();
  abort = null;
  streaming.value = false;
}

onBeforeUnmount(() => abort?.());
</script>

<template>
  <div class="h-full flex-col-stretch gap-12px overflow-hidden">
    <NCard :bordered="false" size="small" class="h-full card-wrapper" content-class="flex-col-stretch overflow-hidden">
      <template #header>
        <div class="flex items-center gap-8px">
          <SvgIcon icon="mdi:robot-outline" class="text-18px" />
          <span>AI 助手</span>
        </div>
      </template>
      <template #header-extra>
        <span v-if="usage" class="text-12px text-gray-400">
          本月已用 {{ usage.monthlyUsed }}{{ usage.monthlyLimit > 0 ? ` / ${usage.monthlyLimit}` : '（不限）' }}
        </span>
      </template>

      <div ref="listRef" class="flex-1 overflow-y-auto px-4px py-8px">
        <div v-if="!bubbles.length" class="flex-col-center gap-12px py-48px text-gray-400">
          <SvgIcon icon="mdi:robot-happy-outline" class="text-48px" />
          <p class="text-14px">问我平台上的任何数据，也可以让我帮你建档、建围栏、处置告警。</p>
          <div class="flex flex-wrap justify-center gap-8px">
            <NButton v-for="s in SUGGESTIONS" :key="s" size="small" secondary @click="send(s)">
              {{ s }}
            </NButton>
          </div>
        </div>

        <div v-for="(bubble, index) in bubbles" :key="index" class="mb-16px">
          <div v-if="bubble.role === 'user'" class="flex justify-end">
            <div class="max-w-75% rd-8px bg-primary px-12px py-8px text-white">{{ bubble.content }}</div>
          </div>
          <div v-else class="flex justify-start">
            <div class="max-w-90% w-full">
              <!-- 工具活动默认折叠：过程可见但不喧宾夺主 -->
              <NCollapse v-if="bubble.tools.length" class="mb-8px">
                <NCollapseItem :title="`执行了 ${bubble.tools.length} 次查询`" name="tools">
                  <div v-for="(tool, i) in bubble.tools" :key="i" class="flex items-center gap-6px py-2px text-13px">
                    <SvgIcon
                      :icon="tool.done ? (tool.ok ? 'mdi:check-circle-outline' : 'mdi:alert-circle-outline') : 'mdi:loading'"
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

              <ActionCard v-for="action in bubble.actions" :key="action.proposalId" :action="action" />
            </div>
          </div>
        </div>

        <NAlert v-if="errorText" type="error" :bordered="false" class="mt-8px">{{ errorText }}</NAlert>
      </div>

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
