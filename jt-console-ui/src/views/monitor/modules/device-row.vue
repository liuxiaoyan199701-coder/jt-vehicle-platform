<script setup lang="ts">
import { computed } from 'vue';
import type { DeviceRow } from '@/utils/device-store';

defineOptions({ name: 'MonitorDeviceRow' });

/**
 * 列表里的一行车。
 *
 * 刻意只用原生元素：虚拟滚动下每滚过一行都要新建一套节点，
 * 若行内常驻 NTag / NBadge / NTooltip / NButton，滚动时创建销毁的就是组件实例而非 DOM。
 * 提示语改由原生 title 承载，文案与原先一致。
 *
 * 行高固定，靠 props 全等让 Vue 跳过没变化的行——所以这里不做任何内部可变状态。
 */
const props = defineProps<{ row: DeviceRow; selected: boolean }>();

const emit = defineEmits<{
  (event: 'select' | 'video' | 'profile' | 'command', row: DeviceRow): void;
}>();

const positioned = computed(() => props.row.gcjLat != null && props.row.gcjLng != null);

const speedText = computed(() => {
  const speed = props.row.speedKph;
  return speed == null ? '-' : speed.toFixed(1);
});

const timeText = computed(() => {
  const value = props.row.deviceTime;
  return value ? value.replace('T', ' ').replace('Z', '') : '-';
});

const alarmText = computed(() => {
  const { alarms, activeAlarmCount } = props.row;
  if (alarms.length) {
    return alarms.length > 1 ? `${alarms[0]} +${alarms.length - 1}` : alarms[0];
  }
  return activeAlarmCount ? `${activeAlarmCount} 条告警` : '';
});

const alarmTitle = computed(() => (props.row.alarms.length ? props.row.alarms.join('、') : ''));
</script>

<template>
  <div
    class="device-row"
    :class="{ 'device-row--selected': selected }"
    @click="emit('select', row)"
  >
    <div class="device-row__head">
      <span class="device-row__label" :title="row.label">{{ row.label }}</span>
      <span
        class="device-row__dot"
        :class="row.online ? 'device-row__dot--online' : 'device-row__dot--offline'"
        :title="row.online ? '在线' : '离线'"
      ></span>
    </div>

    <div class="device-row__meta">
      <span v-if="!positioned" class="device-row__unpositioned">未定位 · 终端未上报位置</span>
      <span v-else class="device-row__stats">{{ speedText }} km/h · {{ timeText }}</span>
      <span v-if="alarmText" class="device-row__alarm" :title="alarmTitle">{{ alarmText }}</span>
    </div>

    <div class="device-row__actions">
      <!-- title 挂在外层 span 上：禁用的 button 在多数浏览器里收不到悬停事件，提示就出不来 -->
      <span :title="row.online ? '查看视频' : '设备离线，无法开流'">
        <button
          type="button"
          class="device-row__action device-row__action--primary"
          :disabled="!row.online"
          @click.stop="emit('video', row)"
        >
          视频
        </button>
      </span>
      <span title="运营详情">
        <button type="button" class="device-row__action" @click.stop="emit('profile', row)">详情</button>
      </span>
      <span :title="row.online ? '远程控制' : '设备离线，无法下发指令'">
        <button
          type="button"
          class="device-row__action device-row__action--warning"
          :disabled="!row.online"
          @click.stop="emit('command', row)"
        >
          控制
        </button>
      </span>
    </div>
  </div>
</template>

<style scoped>
.device-row {
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 3px;
  height: 100%;
  padding: 0 12px;
  cursor: pointer;
  border-bottom: 1px solid var(--n-border-color, rgb(239 239 245));
  transition: background-color 0.2s;
}

.device-row:hover {
  background-color: rgb(0 0 0 / 3%);
}

.device-row--selected {
  background-color: rgb(24 160 88 / 10%);
}

.device-row__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.device-row__label {
  overflow: hidden;
  font-size: 14px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.device-row__dot {
  flex-shrink: 0;
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.device-row__dot--online {
  background-color: #18a058;
}

.device-row__dot--offline {
  background-color: #c2c2c2;
}

.device-row__meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  line-height: 16px;
}

.device-row__stats {
  overflow: hidden;
  color: rgb(0 0 0 / 45%);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.device-row__unpositioned {
  overflow: hidden;
  color: #f0a020;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.device-row__alarm {
  flex-shrink: 0;
  max-width: 50%;
  overflow: hidden;
  padding: 0 5px;
  color: #d03050;
  font-size: 11px;
  line-height: 15px;
  white-space: nowrap;
  text-overflow: ellipsis;
  background-color: rgb(208 48 80 / 10%);
  border-radius: 2px;
}

/* 动作只在悬停或选中时出现：常驻的话，滚动时每行都要新建三个按钮 */
.device-row__actions {
  position: absolute;
  top: 50%;
  right: 8px;
  display: none;
  gap: 4px;
  transform: translateY(-50%);
}

.device-row:hover .device-row__actions,
.device-row--selected .device-row__actions {
  display: flex;
}

.device-row__action {
  padding: 2px 7px;
  color: rgb(0 0 0 / 60%);
  font-size: 12px;
  line-height: 18px;
  background-color: rgb(255 255 255 / 92%);
  border: 1px solid rgb(0 0 0 / 12%);
  border-radius: 3px;
  cursor: pointer;
  transition: all 0.15s;
}

.device-row__action:hover:not(:disabled) {
  border-color: currentcolor;
}

.device-row__action:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.device-row__action--primary {
  color: #18a058;
}

.device-row__action--warning {
  color: #f0a020;
}

/* 深色主题下的对比度 */
html.dark .device-row:hover {
  background-color: rgb(255 255 255 / 6%);
}

html.dark .device-row__stats {
  color: rgb(255 255 255 / 45%);
}

html.dark .device-row__action {
  color: rgb(255 255 255 / 70%);
  background-color: rgb(24 24 28 / 92%);
  border-color: rgb(255 255 255 / 18%);
}
</style>
