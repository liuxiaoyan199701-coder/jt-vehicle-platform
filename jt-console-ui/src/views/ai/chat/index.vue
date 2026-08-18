<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { useDialog, useMessage } from 'naive-ui';
import { useAppStore } from '@/store/modules/app';
import { streamChat } from '@/service/ai-stream';
import type {
  AiActionEvent,
  AiChatMessage,
  AiToolEvent,
  AiUsageEvent,
  AiViewEvent
} from '@/service/ai-stream';
import {
  clearAiMessages,
  deleteAiConversation,
  fetchAiConversations,
  fetchAiMessages
} from '@/service/api/ai';
import type { AiConversation } from '@/service/api/ai';
import MarkdownView from './modules/markdown-view.vue';
import ActionCard from './modules/action-card.vue';
import ConversationList from './modules/conversation-list.vue';
import ViewBlock from './modules/view-block.vue';
import ViewPanel from './modules/view-panel.vue';

defineOptions({ name: 'AiChat' });

interface ToolTrace {
  name: string;
  brief: string;
  done: boolean;
  ok: boolean;
}

interface Bubble {
  /**
   * 稳定标识。列表渲染不能用数组下标作 key——流式期间最后一条气泡在持续变化，
   * 下标 key 会让 Vue 复用错组件实例，表现为工具面板的展开状态莫名其妙地跳到别的气泡上。
   */
  id: number;
  role: 'user' | 'assistant';
  content: string;
  tools: ToolTrace[];
  views: AiViewEvent[];
  actions: AiActionEvent[];
}

const appStore = useAppStore();
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
const inputRef = ref<{ focus: () => void } | null>(null);
/** 用户是否已经往上翻走了。翻走时不再自动滚动，改为提示有新内容。 */
const stuckToBottom = ref(true);
/** 小屏下会话列表收在抽屉里。 */
const drawerVisible = ref(false);
/**
 * 放大区域当前承载的视图。单实例——切换即销毁前一个。
 *
 * 它是会话级状态，清空气泡数组不会碰它，所以切换/新建/清空三处都要显式关掉，
 * 否则会出现「换了一个会话，右边还挂着上一个会话的视频在拉流」。
 */
const panelView = ref<AiViewEvent | null>(null);

function openPanel(view: AiViewEvent) {
  panelView.value = view;
}

function closePanel() {
  panelView.value = null;
}
/**
 * 中文输入法组词中。组词期间的回车是「选词」而不是「发送」，
 * 不挡住的话，用拼音打到一半按回车选字就会把半截话发出去——中文用户每天都会踩。
 */
let composing = false;
let bubbleSeq = 0;

/**
 * 尚未回报给模型的动作执行结果。
 *
 * 模型自己看不到卡片被点了没有、成没成功。不把结果带回去的话会陷入死循环：
 * 用户说「确认失败了」，模型不知道发生过什么，只会再让他去确认一次。
 */
const pendingOutcomes = ref<string[]>([]);
let abort: (() => void) | null = null;
let followUpTimer: ReturnType<typeof setTimeout> | null = null;
let scrollFrame = 0;

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

/** 打开历史会话时，只为最近这么多条消息还原视图。 */
const RESTORE_VIEW_LIMIT = 20;

/**
 * 从留痕里还原视图。
 *
 * 版本号不认识就当没有视图——**宁可不显示，也不要拿一份结构对不上的数据去渲染**，
 * 后者会在气泡里留下一块坏掉的区域，而用户完全不知道发生了什么。
 */
function restoreViews(trace: string | null | undefined): AiViewEvent[] {
  if (!trace) return [];
  try {
    const parsed = JSON.parse(trace);
    if (parsed?.v !== 1 || !Array.isArray(parsed.views)) return [];
    return parsed.views as AiViewEvent[];
  } catch {
    return [];
  }
}

const SUGGESTIONS = [
  '现在有多少台车在线？',
  '昨天哪些车告警最多？',
  '帮我建一台车，车牌粤B12345，设备号 13800138000'
];

const usageText = computed(() => {
  const value = usage.value;
  if (!value) return '';
  return value.monthlyLimit > 0
    ? `本月已用 ${value.monthlyUsed} / ${value.monthlyLimit}`
    : `本月已用 ${value.monthlyUsed}（不限）`;
});

/** 离底部这个距离以内就算「贴着底」，继续跟随；超出说明用户在往回看。 */
const STICK_THRESHOLD_PX = 80;

function onListScroll() {
  const el = listRef.value;
  if (!el) return;
  stuckToBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight <= STICK_THRESHOLD_PX;
}

/**
 * 滚到底部。
 *
 * <p>两点讲究：一是**只在用户本来就贴着底时才滚**——否则用户往上翻看前文时，每来一个字都会被
 * 拽回底部，根本读不下去；二是用 requestAnimationFrame 合并，流式一秒几十个字，
 * 每个字都 nextTick 加一次读写布局是纯粹的浪费。
 */
function scrollToBottom(force = false) {
  if (!force && !stuckToBottom.value) return;
  if (scrollFrame) return;
  scrollFrame = requestAnimationFrame(() => {
    scrollFrame = 0;
    const el = listRef.value;
    if (el) el.scrollTop = el.scrollHeight;
  });
}

/** 用户点「回到底部」后重新开始跟随。 */
function jumpToBottom() {
  stuckToBottom.value = true;
  scrollToBottom(true);
}

async function loadConversations() {
  const { data } = await fetchAiConversations();
  conversations.value = data ?? [];
}

function pushBubble(bubble: Omit<Bubble, 'id'>) {
  bubbleSeq += 1;
  bubbles.value.push({ id: bubbleSeq, ...bubble });
  return bubbles.value[bubbles.value.length - 1];
}

/** 打开一个历史会话。历史里只还原文本——工具活动与动作卡片是当轮的临时状态，不持久化。 */
async function openConversation(id: number) {
  if (streaming.value) return;
  loading.value = true;
  try {
    const { data } = await fetchAiMessages(id);
    activeId.value = id;
    const stored = data ?? [];
    // 只还原最近若干条的视图：更早的图基本没人回看，而每还原一张就要建一个地图实例。
    const restoreFrom = Math.max(0, stored.length - RESTORE_VIEW_LIMIT);
    bubbles.value = stored.map((m, index) => {
      bubbleSeq += 1;
      return {
        id: bubbleSeq,
        role: m.role,
        content: m.content,
        tools: [],
        views: index >= restoreFrom ? restoreViews(m.toolTrace) : [],
        actions: []
      };
    });
    errorText.value = '';
    stuckToBottom.value = true;
    drawerVisible.value = false;
    closePanel();
    await nextTick();
    scrollToBottom(true);
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
  stuckToBottom.value = true;
  drawerVisible.value = false;
  closePanel();
  inputRef.value?.focus();
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
      closePanel();
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

/**
 * 回车发送。
 *
 * 组词期间直接放行给输入法：中文用户按回车是在选词，此时发送会把半截拼音发出去。
 * `composing` 由 composition 事件维护，`isComposing` 再兜一层——部分输入法在
 * compositionend 之后仍会补发一个带 isComposing 的回车。
 */
function onEnter(event: KeyboardEvent) {
  if (composing || event.isComposing) return;
  event.preventDefault();
  send();
}

function onCompositionStart() {
  composing = true;
}

function onCompositionEnd() {
  composing = false;
}

function send(text?: string) {
  const question = (text ?? input.value).trim();
  if (!question || streaming.value) return;
  input.value = '';
  stuckToBottom.value = true;
  runTurn(question);
  inputRef.value?.focus();
}

async function copyReply(content: string) {
  try {
    await navigator.clipboard.writeText(content);
    message.success('已复制');
  } catch {
    message.error('复制失败，请手动选择文本');
  }
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
    pushBubble({ role: 'user', content: question, tools: [], views: [], actions: [] });
  }
  // 必须取回数组里的那一份而不是继续用刚才那个字面量：ref 数组存的是响应式代理，
  // 改原始对象虽然数据也变了，却不会通知视图——表现就是「没有流式效果，全文最后一次性冒出来」。
  const reply = pushBubble({ role: 'assistant', content: '', tools: [], views: [], actions: [] });
  streaming.value = true;
  scrollToBottom(true);

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
        scrollToBottom();
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
        scrollToBottom();
      },
      onView(event) {
        reply.views.push(event);
        scrollToBottom();
      },
      onAction(event) {
        reply.actions.push(event);
        scrollToBottom();
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

/** 工具面板标题随进度变化。全程写「执行了 N 次查询」会让进行中看起来像已经完事了。 */
function toolSummary(tools: ToolTrace[]) {
  const running = tools.filter(t => !t.done).length;
  if (running) return `正在查询…（已完成 ${tools.length - running}/${tools.length}）`;
  const failed = tools.filter(t => !t.ok).length;
  return failed ? `执行了 ${tools.length} 次查询，${failed} 次失败` : `执行了 ${tools.length} 次查询`;
}

onMounted(async () => {
  await loadConversations();
  // 默认接着最近一次聊，而不是每次进来都对着空白页。
  if (conversations.value.length) await openConversation(conversations.value[0].id);
  inputRef.value?.focus();
});

onBeforeUnmount(() => {
  if (followUpTimer) clearTimeout(followUpTimer);
  if (scrollFrame) cancelAnimationFrame(scrollFrame);
  abort?.();
});
</script>

<template>
  <div class="h-full flex gap-12px overflow-hidden">
    <!-- 小屏下侧栏收进抽屉：240px 固定宽在 375px 上会把对话区挤到只剩一条缝 -->
    <NCard
      v-if="!appStore.isMobile"
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
      <ConversationList
        :conversations="conversations"
        :active-id="activeId"
        class="flex-1 overflow-hidden"
        @open="openConversation"
        @remove="removeConversation"
      />
    </NCard>

    <NCard
      :bordered="false"
      size="small"
      class="flex-1 card-wrapper"
      content-class="flex-col-stretch overflow-hidden"
    >
      <template #header>
        <div class="flex items-center gap-8px">
          <NButton
            v-if="appStore.isMobile"
            size="tiny"
            quaternary
            aria-label="打开对话列表"
            @click="drawerVisible = true"
          >
            <template #icon>
              <SvgIcon icon="mdi:menu" />
            </template>
          </NButton>
          <SvgIcon icon="mdi:robot-outline" class="text-18px" />
          <span>AI 助手</span>
        </div>
      </template>
      <template #header-extra>
        <div class="flex items-center gap-12px">
          <span v-if="usageText" class="text-12px text-gray-500 dark:text-gray-400">{{ usageText }}</span>
          <NButton
            v-if="activeId && bubbles.length"
            size="tiny"
            quaternary
            :disabled="streaming"
            @click="clearCurrent"
          >
            <template #icon>
              <SvgIcon icon="mdi:broom" />
            </template>
            清空记录
          </NButton>
        </div>
      </template>

      <NSpin :show="loading" class="relative flex-1 overflow-hidden" content-class="h-full">
        <!--
 aria-live 让读屏软件念出流式追加的回复；没有它，整段回答对读屏用户是完全静默的。
             用 polite 而不是 assertive：逐字打断用户当前朗读会更糟。 
-->
        <div
          ref="listRef"
          class="h-full overflow-y-auto px-4px py-8px"
          role="log"
          aria-live="polite"
          aria-relevant="additions text"
          :aria-busy="streaming"
          @scroll.passive="onListScroll"
        >
          <div v-if="!bubbles.length" class="flex-col-center gap-12px py-48px">
            <SvgIcon icon="mdi:robot-happy-outline" class="text-48px text-gray-400" />
            <p class="max-w-420px text-center text-14px text-gray-600 dark:text-gray-300">
              问我平台上的任何数据，也可以让我帮你建档、建围栏、处置告警。
            </p>
            <div class="flex flex-wrap justify-center gap-8px">
              <NButton v-for="s in SUGGESTIONS" :key="s" size="small" secondary @click="send(s)">{{ s }}</NButton>
            </div>
          </div>

          <div v-for="bubble in bubbles" :key="bubble.id" class="mb-16px">
            <div v-if="bubble.role === 'user'" class="flex justify-end">
              <!--
 底纹用主色、文字保持正文色：主题色是用户可配的，白字压在主色上时对比度会随
                   配色变化（默认色实测仅 4.09:1，不达标）。让文字沿用正文色则与主题色无关。 
-->
              <div class="max-w-75% whitespace-pre-wrap break-words rd-8px bg-primary/12 px-12px py-8px">
                {{ bubble.content }}
              </div>
            </div>
            <div v-else class="flex justify-start">
              <div class="max-w-90% w-full">
                <NCollapse v-if="bubble.tools.length" class="mb-8px">
                  <NCollapseItem :title="toolSummary(bubble.tools)" name="tools">
                    <div v-for="(tool, i) in bubble.tools" :key="i" class="flex items-center gap-6px py-2px text-13px">
                      <SvgIcon
                        :icon="
                          tool.done
                            ? tool.ok
                              ? 'mdi:check-circle-outline'
                              : 'mdi:alert-circle-outline'
                            : 'mdi:loading'
                        "
                        :class="tool.done ? (tool.ok ? 'text-success' : 'text-error') : 'tool-spin'"
                      />
                      <span class="text-gray-600 dark:text-gray-300">{{ tool.brief }}</span>
                    </div>
                  </NCollapseItem>
                </NCollapse>

                <div class="group relative rd-8px bg-gray-100 px-12px py-8px dark:bg-dark-700">
                  <!--
 正文限宽：markdown 里的长段落铺满整个面板时一行会超过 100 个字，
                       眼睛回到行首会找错行。65~75 字符是公认的舒适区间。 
-->
                  <MarkdownView v-if="bubble.content" :content="bubble.content" class="max-w-[72ch]" />
                  <!-- 打字指示器而不是转圈：聊天场景里三点跳动是既定语汇，一眼就知道「它在想」 -->
                  <div v-else-if="streaming" class="flex items-center gap-4px py-4px" aria-label="AI 正在思考">
                    <span class="typing-dot" />
                    <span class="typing-dot" />
                    <span class="typing-dot" />
                  </div>
                  <NButton
                    v-if="bubble.content && !streaming"
                    size="tiny"
                    quaternary
                    class="absolute right-4px top-4px opacity-0 focus-visible:opacity-100 group-hover:opacity-100"
                    aria-label="复制这条回复"
                    @click="copyReply(bubble.content)"
                  >
                    <template #icon>
                      <SvgIcon icon="mdi:content-copy" />
                    </template>
                  </NButton>
                </div>

                <!--
                  顺序固定为 文字 → 视图 → 动作卡。事件到达顺序与语义顺序天然不一致（工具在文字
                  之前执行），不按到达顺序穿插是刻意的：流式期间穿插会让布局不停跳动。
                -->
                <ViewBlock
                  v-for="view in bubble.views"
                  :key="view.viewId"
                  :view="view"
                  @enlarge="openPanel"
                />

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

        <!-- 用户往上翻走时才出现。有了它，「不打断阅读」才不会变成「不知道下面已经写完了」 -->
        <Transition name="fade">
          <NButton
            v-if="!stuckToBottom && bubbles.length"
            size="small"
            secondary
            circle
            class="absolute bottom-12px left-1/2 z-10 shadow-md -translate-x-1/2"
            aria-label="回到最新消息"
            @click="jumpToBottom"
          >
            <template #icon>
              <SvgIcon icon="mdi:arrow-down" />
            </template>
          </NButton>
        </Transition>
      </NSpin>

      <div class="pt-8px">
        <div class="flex items-end gap-8px">
          <NInput
            ref="inputRef"
            v-model:value="input"
            type="textarea"
            placeholder="问点什么，或让我帮你做点什么…（Enter 发送，Shift+Enter 换行）"
            :autosize="{ minRows: 1, maxRows: 5 }"
            :disabled="streaming"
            aria-label="向 AI 助手提问"
            @compositionstart="onCompositionStart"
            @compositionend="onCompositionEnd"
            @keydown.enter.exact="onEnter"
          />
          <NButton v-if="streaming" type="warning" @click="stop">停止</NButton>
          <NButton v-else type="primary" :disabled="!input.trim()" @click="send()">发送</NButton>
        </div>
        <!-- AI 生成内容必须明示，尤其是这里的回答会直接引导用户去改平台数据 -->
        <p class="mt-6px text-center text-12px text-gray-500 dark:text-gray-400">
          回答由 AI 生成，涉及建档、删除等操作前请核对卡片上的参数。
        </p>
      </div>
    </NCard>

    <!--
      放大区域。刻意作为本页的子组件而不 teleport 到 body：只有这样路由离开时才会自动卸载，
      视频的连接与解码器才会跟着释放。
    -->
    <ViewPanel v-if="!appStore.isMobile" :view="panelView" @close="closePanel" />

    <!-- 小屏专用：会话列表走抽屉，与告警页的详情抽屉保持同一交互模式 -->
    <NDrawer v-model:show="drawerVisible" :width="280" placement="left">
      <NDrawerContent title="对话" closable body-content-class="p-8px!">
        <NButton
          class="mb-8px w-full"
          size="small"
          type="primary"
          secondary
          :disabled="streaming"
          @click="newConversation"
        >
          <template #icon>
            <SvgIcon icon="mdi:plus" />
          </template>
          新建对话
        </NButton>
        <ConversationList
          :conversations="conversations"
          :active-id="activeId"
          @open="openConversation"
          @remove="removeConversation"
        />
      </NDrawerContent>
    </NDrawer>
  </div>
</template>

<style scoped>
.typing-dot {
  width: 6px;
  height: 6px;
  border-radius: 9999px;
  background-color: rgb(var(--primary-color));
  animation: typing 1.2s ease-in-out infinite;
}

.typing-dot:nth-child(2) {
  animation-delay: 0.16s;
}

.typing-dot:nth-child(3) {
  animation-delay: 0.32s;
}

@keyframes typing {
  0%,
  60%,
  100% {
    opacity: 0.25;
    transform: translateY(0);
  }
  30% {
    opacity: 1;
    transform: translateY(-3px);
  }
}

.tool-spin {
  animation: tool-spin 1s linear infinite;
}

@keyframes tool-spin {
  to {
    transform: rotate(360deg);
  }
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

/* 前庭功能敏感的用户会被持续跳动的元素影响。动效去掉后状态仍然靠文字与颜色表达，信息不丢。 */
@media (prefers-reduced-motion: reduce) {
  .typing-dot,
  .tool-spin {
    animation: none;
  }

  .typing-dot {
    opacity: 0.6;
  }

  .fade-enter-active,
  .fade-leave-active {
    transition: none;
  }
}
</style>
