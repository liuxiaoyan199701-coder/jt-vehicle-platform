<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { NButton, NEmpty, NSpin, useMessage } from 'naive-ui';
import { useRouter } from 'vue-router';
import type { Briefing, BriefingItem } from '@/service/api';
import { fetchBriefing, refreshBriefing } from '@/service/api';

/**
 * 今日要点。
 *
 * <p>这块占据看板主位而不是角落，是整个页面的立意：数据看板的问题从来不是缺数字，
 * 而是把「哪个数字今天不正常」的判断全留给了人。这里由 AI 替人先读一遍。
 *
 * <p><b>每条要点的数字都不经模型</b>——服务端用普通代码算出候选发现，模型只负责挑选与措辞，
 * 引用不到候选的整条会在服务端丢弃。所以下面展示的数值是可信的，用等宽字体呈现以便纵向扫读。
 */
defineOptions({ name: 'AiBriefing' });

const router = useRouter();
const message = useMessage();

const loading = ref(true);
const refreshing = ref(false);
const briefing = ref<Briefing | null>(null);

const items = computed(() => briefing.value?.items ?? []);
const status = computed(() => briefing.value?.status ?? 'PENDING');

/** 严重度决定左侧色带。用颜色而不是文字标签——一列色带能被眼睛一次扫完，一列标签不能。 */
const SEVERITY_CLASS: Record<string, string> = {
  CRITICAL: 'sev-critical',
  WARN: 'sev-warn',
  INFO: 'sev-info'
};

const CATEGORY_ICON: Record<string, string> = {
  OFFLINE: 'mdi:wifi-off',
  ALARM: 'mdi:alert-outline',
  MILEAGE: 'mdi:speedometer-slow',
  CAMERA: 'mdi:camera-outline',
  FLEET: 'mdi:truck-outline'
};

/** 只显示前三项数据：卡片里塞满键值对会把那句话本身淹没。 */
function briefFacts(item: BriefingItem) {
  return Object.entries(item.facts ?? {})
    .filter(([, value]) => value !== null && value !== '')
    .slice(0, 3);
}

function shortTime(value: string | null) {
  return value ? value.replace('T', ' ').slice(5, 16) : '';
}

async function load() {
  loading.value = true;
  try {
    const { data } = await fetchBriefing();
    briefing.value = data ?? null;
  } finally {
    loading.value = false;
  }
}

async function reanalyse() {
  refreshing.value = true;
  try {
    const { data, error } = await refreshBriefing();
    if (error) {
      message.error(error.message || '重新分析失败');
      return;
    }
    briefing.value = data ?? null;
    message.success('已重新分析');
  } finally {
    refreshing.value = false;
  }
}

function follow(item: BriefingItem) {
  if (!item.link) return;
  void router.push({ name: item.link.routeName, query: item.link.query });
}

onMounted(load);
</script>

<template>
  <NCard :bordered="false" size="small" class="briefing h-full">
    <template #header>
      <div class="flex items-center gap-8px">
        <SvgIcon icon="mdi:lightbulb-on-outline" class="text-18px text-primary" />
        <span class="text-16px font-semibold">今日要点</span>
        <span v-if="briefing?.updatedAt" class="briefing-stamp">{{ shortTime(briefing.updatedAt) }}</span>
      </div>
    </template>
    <template #header-extra>
      <NButton size="tiny" quaternary :loading="refreshing" @click="reanalyse">
        <template #icon><SvgIcon icon="mdi:refresh" /></template>
        重新分析
      </NButton>
    </template>

    <NSpin :show="loading">
      <!-- 生成失败与「今天没事」必须分开说：这两者的处理方式完全相反 -->
      <NAlert v-if="status === 'FAILED'" type="warning" :bordered="false" class="text-13px">
        今日要点生成失败，看板数据不受影响。可点「重新分析」重试。
      </NAlert>
      <NAlert v-else-if="status === 'PENDING'" type="info" :bordered="false" class="text-13px">
        正在准备今日要点，稍后自动出现，也可以点「重新分析」立即生成。
      </NAlert>

      <div v-else-if="items.length" class="flex flex-col gap-6px">
        <div
          v-for="item in items"
          :key="item.id"
          class="briefing-row"
          :class="[SEVERITY_CLASS[item.severity] ?? 'sev-info', { 'is-clickable': Boolean(item.link) }]"
          :role="item.link ? 'button' : undefined"
          :tabindex="item.link ? 0 : undefined"
          @click="follow(item)"
          @keydown.enter="follow(item)"
        >
          <SvgIcon
            :icon="CATEGORY_ICON[item.category] ?? 'mdi:information-outline'"
            class="briefing-icon"
          />
          <div class="min-w-0 flex-1">
            <p class="briefing-text">{{ item.text }}</p>
            <!-- 数值用等宽字体：多行纵向对齐才扫得动，且这些数字全部来自服务端计算 -->
            <div v-if="briefFacts(item).length" class="briefing-facts">
              <span v-for="[key, value] in briefFacts(item)" :key="key">
                {{ key }}<b>{{ value }}</b>
              </span>
            </div>
          </div>
          <SvgIcon v-if="item.link" icon="mdi:chevron-right" class="briefing-chevron" />
        </div>

        <p v-if="briefing?.filtered" class="briefing-note">
          部分要点涉及你的数据范围之外的车辆，已隐藏。
        </p>
        <p v-if="status === 'DEGRADED'" class="briefing-note">
          本次由系统直接生成（AI 暂时不可用），措辞较机械，数据不受影响。
        </p>
      </div>

      <!-- 没有要点是正常且常见的结果。不写「一切正常」——那句话每天出现就没人看了 -->
      <NEmpty v-else-if="!loading" description="今天没有需要特别关注的情况" size="small" class="py-20px" />
    </NSpin>
  </NCard>
</template>

<style scoped>
.briefing :deep(.n-card__content) {
  padding-top: 4px;
}

.briefing-stamp {
  font-size: 12px;
  font-weight: 400;
  color: var(--n-text-color-3, #8a8f98);
  font-variant-numeric: tabular-nums;
}

/* 左侧色带是这块的主要视觉手段：一列颜色能被眼睛一次扫完，一列文字标签不能 */
.briefing-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 12px 10px 10px;
  border-radius: 6px;
  border-left: 3px solid transparent;
  background: rgb(0 0 0 / 2.5%);
  transition: background-color 200ms ease, border-color 200ms ease;
}

.dark .briefing-row {
  background: rgb(255 255 255 / 4%);
}

.briefing-row.is-clickable {
  cursor: pointer;
}

.briefing-row.is-clickable:hover {
  background: rgb(0 0 0 / 5%);
}

.dark .briefing-row.is-clickable:hover {
  background: rgb(255 255 255 / 8%);
}

.briefing-row.is-clickable:focus-visible {
  outline: 2px solid var(--n-color-target, #1e40af);
  outline-offset: 1px;
}

/* 严重度配色：红/琥珀/蓝，与设计系统的「蓝色数据 + 琥珀强调」一致 */
.sev-critical {
  border-left-color: #d03050;
}

.sev-warn {
  border-left-color: #f59e0b;
}

.sev-info {
  border-left-color: #3b82f6;
}

.briefing-icon {
  flex-shrink: 0;
  margin-top: 2px;
  font-size: 16px;
  color: var(--n-text-color-3, #8a8f98);
}

.briefing-text {
  font-size: 14px;
  line-height: 1.55;
  color: var(--n-text-color-1, #1f2225);
}

.briefing-facts {
  display: flex;
  flex-wrap: wrap;
  gap: 2px 14px;
  margin-top: 3px;
  font-size: 12px;
  color: var(--n-text-color-3, #6b7280);
}

.briefing-facts b {
  margin-left: 4px;
  font-weight: 600;
  font-family: 'Fira Code', ui-monospace, SFMono-Regular, Menlo, monospace;
  font-variant-numeric: tabular-nums;
  color: var(--n-text-color-2, #41505e);
}

.briefing-chevron {
  flex-shrink: 0;
  align-self: center;
  font-size: 16px;
  color: var(--n-text-color-3, #b0b4ba);
}

.briefing-note {
  margin-top: 2px;
  font-size: 12px;
  color: var(--n-text-color-3, #8a8f98);
}

@media (prefers-reduced-motion: reduce) {
  .briefing-row {
    transition: none;
  }
}
</style>
