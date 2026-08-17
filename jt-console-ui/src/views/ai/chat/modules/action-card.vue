<script setup lang="ts">
import { computed, ref } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { request } from '@/service/request';
import type { AiActionEvent } from '@/service/ai-stream';

/**
 * 动作确认卡片。
 *
 * 执行映射是**前端自持**的白名单，不使用服务端下发的路径——模型不该有能力指定调用哪个 URL。
 * 调用用的是当前用户自己的令牌，因此权限、业务校验与审计全部走既有通道，AI 侧没有任何特权。
 */
defineOptions({ name: 'ActionCard' });

const props = defineProps<{ action: AiActionEvent }>();

// 把执行结果回报给页面，页面会在下一轮告诉模型。
// 不回报的话会陷入死循环：用户说「确认失败了」，模型不知道发生过什么，只会再让他去确认一次。
const emit = defineEmits<{ settled: [outcome: string] }>();

const authStore = useAuthStore();
const state = ref<'pending' | 'running' | 'done' | 'failed' | 'cancelled'>('pending');
const message = ref('');

/** 动作类型到既有接口的映射。新增动作必须同时在这里登记，否则前端不认。 */
const ROUTES: Record<string, (params: any) => { url: string; method: 'post' | 'put' | 'delete'; body?: any }> = {
  vehicle_create: p => ({ url: '/vehicles', method: 'post', body: p }),
  vehicle_update: p => ({ url: `/vehicles/${encodeURIComponent(p.deviceId)}`, method: 'put', body: p }),
  vehicle_delete: p => ({ url: `/vehicles/${encodeURIComponent(p.deviceId)}`, method: 'delete' }),
  fleet_create: p => ({ url: '/fleets', method: 'post', body: p }),
  fleet_update: p => ({ url: `/fleets/${p.id}`, method: 'put', body: p }),
  fleet_delete: p => ({ url: `/fleets/${p.id}`, method: 'delete' }),
  fleet_members: p => ({ url: `/fleets/${p.id}/vehicles`, method: 'put', body: p }),
  geofence_create: p => ({ url: '/geofences', method: 'post', body: p }),
  geofence_update: p => ({ url: `/geofences/${p.id}`, method: 'put', body: p }),
  geofence_delete: p => ({ url: `/geofences/${p.id}`, method: 'delete' }),
  alarm_acknowledge: p => ({ url: `/alarms/${p.alarmId}/acknowledge`, method: 'post', body: { note: p.note } }),
  alarm_close: p => ({ url: `/alarms/${p.alarmId}/close`, method: 'post', body: { note: p.note } }),
  send_text: p => ({ url: '/commands/text', method: 'post', body: p }),
  tenant_create: p => ({ url: '/platform/tenants', method: 'post', body: p }),
  tenant_update: p => ({ url: `/platform/tenants/${p.id}`, method: 'put', body: p }),
  tenant_disable: p => ({ url: `/platform/tenants/${p.id}/disable`, method: 'post', body: p }),
  plan_create: p => ({ url: '/platform/plans', method: 'post', body: p }),
  plan_update: p => ({ url: `/platform/plans/${p.id}`, method: 'put', body: p })
};

const supported = computed(() => Boolean(ROUTES[props.action.type]));
const allowed = computed(() => authStore.userInfo.buttons?.includes(props.action.requiredPermission) ?? false);
const paramRows = computed(() => Object.entries(props.action.params ?? {}));

async function execute() {
  const route = ROUTES[props.action.type];
  if (!route) return;
  state.value = 'running';
  try {
    const { url, method, body } = route(props.action.params ?? {});
    const { error } = await request({ url, method, data: body });
    if (error) {
      state.value = 'failed';
      message.value = describe(error);
      emit('settled', `${props.action.label}执行失败：${message.value}`);
      return;
    }
    state.value = 'done';
    message.value = '已执行';
    emit('settled', `${props.action.label}执行成功`);
  } catch (failure) {
    state.value = 'failed';
    message.value = describe(failure);
    emit('settled', `${props.action.label}执行失败：${message.value}`);
  }
}

/**
 * 取出后端真正的失败原因。
 *
 * axios 封装把所有业务失败统一包成 'the backend request error'，直接显示它等于告诉用户
 * 「出错了」——而后端其实明确说了「车队编码已存在」这类可以据此行动的原因。业务码在
 * response.data.msg 里，必须优先取它。
 */
function describe(failure: unknown): string {
  const payload = (failure as any)?.response?.data;
  if (payload?.msg && String(payload.msg).trim()) return String(payload.msg);
  const raw = (failure as any)?.message;
  if (raw && raw !== 'the backend request error') return String(raw);
  return '执行失败，请稍后重试';
}

function cancel() {
  state.value = 'cancelled';
  message.value = '已取消';
  emit('settled', `${props.action.label}被用户取消`);
}
</script>

<template>
  <NCard size="small" class="my-2 border-primary/40" :bordered="true">
    <template #header>
      <div class="flex items-center gap-2">
        <NTag size="small" type="warning">{{ action.label }}</NTag>
        <span class="text-14px font-medium">{{ action.title }}</span>
      </div>
    </template>

    <p v-if="action.reason" class="mb-2 text-13px text-gray-500">{{ action.reason }}</p>

    <!-- 参数必须完整展示：确认这一步的价值有一半在于让用户看见车牌被听错了一个字 -->
    <NDescriptions v-if="paramRows.length" :column="1" size="small" bordered label-placement="left">
      <NDescriptionsItem v-for="[key, value] in paramRows" :key="key" :label="key">
        <span class="break-all">{{ value === null || value === undefined ? '—' : String(value) }}</span>
      </NDescriptionsItem>
    </NDescriptions>

    <template #action>
      <div class="flex items-center gap-2">
        <template v-if="state === 'pending'">
          <NButton v-if="!supported" size="small" disabled>该动作暂不支持</NButton>
          <NButton v-else-if="!allowed" size="small" disabled>
            缺少权限：{{ action.requiredPermission }}
          </NButton>
          <template v-else>
            <NButton size="small" type="primary" @click="execute">确认执行</NButton>
            <NButton size="small" @click="cancel">取消</NButton>
          </template>
        </template>
        <NSpin v-else-if="state === 'running'" size="small" />
        <NTag v-else-if="state === 'done'" size="small" type="success">{{ message }}</NTag>
        <NTag v-else-if="state === 'failed'" size="small" type="error">{{ message }}</NTag>
        <NTag v-else size="small">{{ message }}</NTag>
      </div>
    </template>
  </NCard>
</template>
