<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { NBadge, NButton, NEmpty, NPopover, NSpin, useMessage } from 'naive-ui';
import { useRouter } from 'vue-router';
import type { NoticeItem } from '@/service/api';
import { fetchNotices, fetchUnreadNoticeCount, markAllNoticesRead, markNoticeRead } from '@/service/api';
import { useAuthStore } from '@/store/modules/auth';
import { LIVE_NOTICE_EVENT } from '@/hooks/use-live-socket';
import {
  NOTICE_BADGE_MAX,
  briefNoticeFacts,
  noticeCategoryIcon,
  noticeSeverityClass,
  noticeTimeLabel
} from '@/utils/notice-display';

/**
 * 顶栏通知铃铛。
 *
 * 放在顶栏而不是某个页面里，是这块功能的立意：平台已经能算出「京A12345 已经 26 小时没上报」，
 * 缺的只是把它送到人眼前——而人不会正好停在首页。
 *
 * **未读数以定时拉取为准，WebSocket 只是加速**。实时通道只挂在首页与监控页两个页面上，
 * 指望它送达等于要求用户正好停在那两页；所以这里每 60 秒拉一次，
 * 恰好有页面连着 socket 时顺带快一步。
 */
defineOptions({ name: 'NoticeBell' });

const router = useRouter();
const message = useMessage();
const authStore = useAuthStore();

/** 通知呈现的就是首页要点里那些发现，权限也就是同一个。 */
const visible = computed(() => authStore.hasPermission('dashboard:view'));

const POLL_INTERVAL_MS = 60000;
const PAGE_SIZE = 20;

const unread = ref(0);
const items = ref<NoticeItem[]>([]);
const loading = ref(false);
const marking = ref(false);
const open = ref(false);
const filtered = ref(false);
let timer: ReturnType<typeof setInterval> | null = null;

async function loadUnread() {
  if (!visible.value) return;
  const { data } = await fetchUnreadNoticeCount();
  unread.value = data?.count ?? 0;
}

async function loadList() {
  if (!visible.value) return;
  loading.value = true;
  try {
    const { data } = await fetchNotices(1, PAGE_SIZE);
    items.value = data?.items ?? [];
    filtered.value = Boolean(data?.filtered);
  } finally {
    loading.value = false;
  }
}

/**
 * 推送到达时重新拉，而不是把推来的内容插进列表。
 *
 * 推送与接口各说一套的话，刷新一下页面内容就变了，用户无从判断该信哪个。
 */
function onPushed() {
  void loadUnread();
  if (open.value) void loadList();
}

/** 标签页在后台时不拉：那 60 秒一次的请求对没在看的人毫无价值。 */
function tick() {
  if (document.visibilityState === 'visible') void loadUnread();
}

async function follow(item: NoticeItem) {
  await markOne(item);
  if (!item.link) return;
  open.value = false;
  void router.push({ name: item.link.routeName, query: item.link.query });
}

async function markOne(item: NoticeItem) {
  if (item.read) return;
  item.read = true;
  unread.value = Math.max(0, unread.value - 1);
  const { error } = await markNoticeRead(item.id);
  if (error) {
    // 标失败就把界面恢复原状，否则未读数会和服务端悄悄对不上。
    item.read = false;
    void loadUnread();
  }
}

async function markAll() {
  marking.value = true;
  try {
    const { error } = await markAllNoticesRead();
    if (error) {
      message.error(error.message || '标记已读失败');
      return;
    }
    await Promise.all([loadUnread(), loadList()]);
  } finally {
    marking.value = false;
  }
}

watch(open, opened => {
  if (opened) void loadList();
});

onMounted(() => {
  if (!visible.value) return;
  void loadUnread();
  timer = setInterval(tick, POLL_INTERVAL_MS);
  window.addEventListener(LIVE_NOTICE_EVENT, onPushed);
  document.addEventListener('visibilitychange', tick);
});

onBeforeUnmount(() => {
  if (timer) clearInterval(timer);
  timer = null;
  window.removeEventListener(LIVE_NOTICE_EVENT, onPushed);
  document.removeEventListener('visibilitychange', tick);
});
</script>

<template>
  <NPopover v-if="visible" v-model:show="open" trigger="click" placement="bottom-end" :width="360" raw>
    <!--
      刻意不给按钮再套一层 tooltip：ButtonIcon 内部是 NTooltip，
      塞进 NPopover 的 trigger 里就成了两层浮层争同一个元素的显隐。
      徽标本身已经说明了它是什么，面板标题再说一次。
    -->
    <template #trigger>
      <ButtonIcon :aria-label="unread ? `${unread} 条未读通知` : '通知'">
        <NBadge :value="unread" :max="NOTICE_BADGE_MAX" :show="unread > 0">
          <SvgIcon icon="mdi:bell-outline" class="text-icon-large" />
        </NBadge>
      </ButtonIcon>
    </template>

    <div class="notice-panel">
      <div class="notice-head">
        <span class="notice-title">通知</span>
        <NButton v-if="unread > 0" size="tiny" quaternary :loading="marking" @click="markAll">
          全部已读
        </NButton>
      </div>

      <NSpin :show="loading">
        <div v-if="items.length" class="notice-list">
          <div
            v-for="item in items"
            :key="item.id"
            class="notice-row"
            :class="[noticeSeverityClass(item.severity), { 'is-read': item.read }]"
            role="button"
            :tabindex="0"
            @click="follow(item)"
            @keydown.enter="follow(item)"
          >
            <SvgIcon :icon="noticeCategoryIcon(item.category)" class="notice-icon" />
            <div class="min-w-0 flex-1">
              <p class="notice-text">{{ item.summary }}</p>
              <!-- 数值用等宽字体：多行纵向对齐才扫得动，且这些数字全部来自服务端计算 -->
              <div v-if="briefNoticeFacts(item.facts).length" class="notice-facts">
                <span v-for="[key, value] in briefNoticeFacts(item.facts)" :key="key">
                  {{ key }}<b>{{ value }}</b>
                </span>
              </div>
              <span class="notice-stamp">{{ noticeTimeLabel(item.createdAt) }}</span>
            </div>
            <span v-if="!item.read" class="notice-dot" aria-label="未读"></span>
          </div>

          <p v-if="filtered" class="notice-note">部分通知涉及你的数据范围之外的车辆，已隐藏。</p>
        </div>

        <!-- 没有通知是正常且常见的结果。不写「一切正常」——那句话每天出现就没人看了 -->
        <NEmpty v-else-if="!loading" description="暂无通知" size="small" class="py-24px" />
      </NSpin>
    </div>
  </NPopover>
</template>

<style scoped>
.notice-panel {
  max-height: 60vh;
  overflow-y: auto;
  padding: 10px 12px 12px;
  border-radius: 8px;
  background: var(--n-color, #fff);
  box-shadow: 0 6px 24px rgb(0 0 0 / 12%);
}

.dark .notice-panel {
  background: var(--n-color, #18181c);
}

.notice-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.notice-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--n-text-color-1, #1f2225);
}

.notice-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

/* 左侧色带是这块的主要视觉手段，与首页要点同一套语言 */
.notice-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 9px 10px;
  border-radius: 6px;
  border-left: 3px solid transparent;
  background: rgb(0 0 0 / 2.5%);
  cursor: pointer;
  transition:
    background-color 200ms ease,
    opacity 200ms ease;
}

.dark .notice-row {
  background: rgb(255 255 255 / 4%);
}

.notice-row:hover {
  background: rgb(0 0 0 / 5%);
}

.dark .notice-row:hover {
  background: rgb(255 255 255 / 8%);
}

.notice-row:focus-visible {
  outline: 2px solid var(--n-color-target, #1e40af);
  outline-offset: 1px;
}

/* 已读淡下去而不是消失：「上周提醒过我什么」要还能翻得到 */
.notice-row.is-read {
  opacity: 0.6;
}

.sev-critical {
  border-left-color: #d03050;
}

.sev-warn {
  border-left-color: #f59e0b;
}

.sev-info {
  border-left-color: #3b82f6;
}

.notice-icon {
  flex-shrink: 0;
  margin-top: 2px;
  font-size: 16px;
  color: var(--n-text-color-3, #8a8f98);
}

.notice-text {
  font-size: 13px;
  line-height: 1.5;
  color: var(--n-text-color-1, #1f2225);
}

.notice-facts {
  display: flex;
  flex-wrap: wrap;
  gap: 2px 12px;
  margin-top: 3px;
  font-size: 12px;
  color: var(--n-text-color-3, #6b7280);
}

.notice-facts b {
  margin-left: 4px;
  font-weight: 600;
  font-family: 'Fira Code', ui-monospace, SFMono-Regular, Menlo, monospace;
  font-variant-numeric: tabular-nums;
  color: var(--n-text-color-2, #41505e);
}

.notice-stamp {
  display: inline-block;
  margin-top: 3px;
  font-size: 12px;
  color: var(--n-text-color-3, #8a8f98);
  font-variant-numeric: tabular-nums;
}

.notice-dot {
  flex-shrink: 0;
  align-self: center;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #d03050;
}

.notice-note {
  margin-top: 4px;
  font-size: 12px;
  color: var(--n-text-color-3, #8a8f98);
}

@media (prefers-reduced-motion: reduce) {
  .notice-row {
    transition: none;
  }
}
</style>
