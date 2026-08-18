<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue';
import { NButton, NInput } from 'naive-ui';
import { useRouter } from 'vue-router';
import { streamChat } from '@/service/ai-stream';
import type { AiViewEvent } from '@/service/ai-stream';
import MarkdownView from '@/views/ai/chat/modules/markdown-view.vue';
import ViewBlock from '@/views/ai/chat/modules/view-block.vue';

/**
 * 看板上的「问一句」。
 *
 * <p>复用对话那条完整链路（SSE、内嵌视图、图表），但**结果留在看板上而不是跳去对话页**——
 * 在看板上产生的问题，答案也该出现在看板上；跳走等于让人离开正在看的上下文。
 *
 * <p>刻意只保留最近一次问答，不做历史：这里是「顺手问一句」，不是对话。
 * 需要连续追问时点右上角进 AI 助手页，那边有完整的会话能力。
 */
defineOptions({ name: 'AskBar' });

const router = useRouter();

const input = ref('');
const answer = ref('');
const views = ref<AiViewEvent[]>([]);
const streaming = ref(false);
const errorText = ref('');
let abort: (() => void) | null = null;

const SUGGESTIONS = ['今天哪台车跑得最多', '这周告警趋势', '有哪些车一直离线'];

const hasResult = computed(() => Boolean(answer.value || views.value.length || errorText.value));

function ask(text?: string) {
  const question = (text ?? input.value).trim();
  if (!question || streaming.value) return;
  input.value = '';
  answer.value = '';
  views.value = [];
  errorText.value = '';
  streaming.value = true;

  abort = streamChat(
    [{ role: 'user', content: question }],
    {
      onDelta(chunk) {
        answer.value += chunk;
      },
      onView(event) {
        views.value.push(event);
      },
      onError(_code, msg) {
        errorText.value = msg;
        streaming.value = false;
      },
      onDone() {
        streaming.value = false;
      }
    },
    // 不带会话号：看板上的提问每次都是独立的一问一答，不续在任何对话里。
    null
  );
}

function stop() {
  abort?.();
  streaming.value = false;
}

function clear() {
  stop();
  answer.value = '';
  views.value = [];
  errorText.value = '';
}

/** 想接着聊就去 AI 助手页——那边有会话、有历史、有动作确认。 */
function openChat() {
  void router.push({ name: 'ai_chat' });
}

onBeforeUnmount(() => abort?.());
</script>

<template>
  <NCard :bordered="false" size="small" class="ask h-full">
    <template #header>
      <div class="flex items-center gap-8px">
        <SvgIcon icon="mdi:robot-outline" class="text-18px text-primary" />
        <span class="text-16px font-semibold">问一句</span>
      </div>
    </template>
    <template #header-extra>
      <NButton size="tiny" quaternary @click="openChat">
        去助手
        <template #icon><SvgIcon icon="mdi:arrow-top-right" /></template>
      </NButton>
    </template>

    <NInput
      v-model:value="input"
      placeholder="问点什么…"
      :disabled="streaming"
      @keyup.enter="ask()"
    >
      <template #suffix>
        <NButton v-if="streaming" size="tiny" text type="warning" @click="stop">停止</NButton>
        <NButton v-else size="tiny" text type="primary" :disabled="!input.trim()" @click="ask()">
          <SvgIcon icon="mdi:send" />
        </NButton>
      </template>
    </NInput>

    <!-- 建议问题不是装饰：这块的最大障碍是「不知道能问什么」，给三个具体例子最有效 -->
    <div v-if="!hasResult && !streaming" class="mt-10px flex flex-col gap-4px">
      <button
        v-for="suggestion in SUGGESTIONS"
        :key="suggestion"
        type="button"
        class="ask-suggestion"
        @click="ask(suggestion)"
      >
        <SvgIcon icon="mdi:chevron-right" class="text-13px opacity-60" />
        {{ suggestion }}
      </button>
    </div>

    <div v-if="hasResult" class="ask-result">
      <NAlert v-if="errorText" type="warning" :bordered="false" class="text-13px">
        {{ errorText }}
      </NAlert>
      <MarkdownView v-if="answer" :content="answer" />
      <ViewBlock v-for="view in views" :key="view.viewId" :view="view" @enlarge="openChat" />
      <div class="mt-6px flex justify-end">
        <NButton size="tiny" quaternary @click="clear">清除</NButton>
      </div>
    </div>
  </NCard>
</template>

<style scoped>
.ask :deep(.n-card__content) {
  padding-top: 4px;
}

.ask-suggestion {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 5px 8px;
  border: none;
  border-radius: 5px;
  background: transparent;
  font-size: 13px;
  text-align: left;
  color: var(--n-text-color-2, #41505e);
  cursor: pointer;
  transition: background-color 200ms ease, color 200ms ease;
}

.ask-suggestion:hover {
  background: rgb(0 0 0 / 4%);
  color: var(--n-color-target, #1e40af);
}

.dark .ask-suggestion:hover {
  background: rgb(255 255 255 / 7%);
}

.ask-suggestion:focus-visible {
  outline: 2px solid var(--n-color-target, #1e40af);
  outline-offset: 1px;
}

/* 结果区限高并可滚：它和左边的要点卡同高，内容长了不能把整行撑开 */
.ask-result {
  margin-top: 10px;
  max-height: 320px;
  overflow-y: auto;
}

@media (prefers-reduced-motion: reduce) {
  .ask-suggestion {
    transition: none;
  }
}
</style>
