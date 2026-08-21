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
  // 拍照与文本下发同属「会真的发到路上那台车」的一类，走同一个指令代理，
  // 参数范围校验由后端 CommandProxyController 统一把关，前端不重复实现。
  //
  // 这里是唯一一处做参数改名的路由：模型侧统一用 channel（与 photo_gallery、live_video 一致），
  // 而 0x8801 的指令接口历来叫 channelId。把转译放在白名单里，模型面前就只剩一个词。
  take_photo: p => ({
    url: '/commands/photo',
    method: 'post',
    body: {
      deviceId: p.deviceId,
      channelId: p.channel ?? p.channelId ?? 1,
      count: p.count ?? 1,
      resolution: p.resolution ?? 2
    }
  }),
  upgrade: p => ({ url: '/commands/upgrade', method: 'post', body: p }),
  tenant_create: p => ({ url: '/platform/tenants', method: 'post', body: p }),
  tenant_update: p => ({ url: `/platform/tenants/${p.id}`, method: 'put', body: p }),
  tenant_disable: p => ({ url: `/platform/tenants/${p.id}/disable`, method: 'post', body: p }),
  plan_create: p => ({ url: '/platform/plans', method: 'post', body: p }),
  plan_update: p => ({ url: `/platform/plans/${p.id}`, method: 'put', body: p })
};

const supported = computed(() => Boolean(ROUTES[props.action.type]));
const allowed = computed(() => authStore.userInfo.buttons?.includes(props.action.requiredPermission) ?? false);

/**
 * 字段名到中文标签。
 *
 * 确认这一步的价值在于让用户一眼看出「车牌被听错了一个字」，而 `plateNo`、`deviceId` 这种
 * 原始字段名会让人先花几秒去认字段——认字段的注意力就是从核对数据里挪走的。
 * 表里没有的键原样显示，好过显示不出来。
 */
const FIELD_LABELS: Record<string, string> = {
  deviceId: '设备号',
  plateNo: '车牌号',
  plateColor: '车牌颜色',
  tenantId: '所属租户',
  departmentId: '所属部门',
  name: '名称',
  code: '编码',
  id: '标识',
  remark: '备注',
  note: '备注',
  alarmId: '告警',
  fleetId: '车队',
  vehicleIds: '车辆',
  geofenceId: '围栏',
  shape: '形状',
  points: '边界点',
  radius: '半径（米）',
  centerLat: '中心纬度',
  centerLng: '中心经度',
  text: '下发内容',
  expiresAt: '有效期',
  planId: '套餐',
  maxVehicles: '车辆上限',
  maxAccounts: '账号上限',
  status: '状态'
};

const paramRows = computed(() =>
  Object.entries(props.action.params ?? {}).map(([key, value]) => ({
    key,
    label: FIELD_LABELS[key] ?? key,
    text: format(value)
  }))
);

/** 空值显示成占位符而不是 "null"——后者会让人以为真的要把这个字段写成 null。 */
function format(value: unknown): string {
  if (value === null || value === undefined || value === '') return '—';
  if (Array.isArray(value)) return value.length ? value.join('、') : '—';
  if (typeof value === 'boolean') return value ? '是' : '否';
  return String(value);
}

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

    <p v-if="action.reason" class="mb-2 text-13px text-gray-600 dark:text-gray-300">{{ action.reason }}</p>

    <!-- 参数必须完整展示：确认这一步的价值有一半在于让用户看见车牌被听错了一个字 -->
    <NDescriptions v-if="paramRows.length" :column="1" size="small" bordered label-placement="left">
      <NDescriptionsItem v-for="row in paramRows" :key="row.key" :label="row.label">
        <span class="break-all">{{ row.text }}</span>
      </NDescriptionsItem>
    </NDescriptions>

    <template #action>
      <div class="flex items-center gap-2">
        <template v-if="state === 'pending'">
          <!-- 禁用态必须说明原因：光是灰掉一个「确认执行」，用户只会反复点然后以为坏了 -->
          <NButton v-if="!supported" size="small" disabled>该动作暂不支持</NButton>
          <NButton v-else-if="!allowed" size="small" disabled>
            缺少权限：{{ action.requiredPermission }}
          </NButton>
          <template v-else>
            <NButton size="small" type="primary" @click="execute">确认执行</NButton>
            <NButton size="small" @click="cancel">取消</NButton>
          </template>
        </template>
        <!-- 执行中带上文字：裸的转圈圈说不清是在执行还是在加载，而这一步是有副作用的 -->
        <div v-else-if="state === 'running'" class="flex items-center gap-6px text-13px text-gray-600 dark:text-gray-300">
          <NSpin size="small" />
          <span>正在执行…</span>
        </div>
        <NTag v-else-if="state === 'done'" size="small" type="success">{{ message }}</NTag>
        <NTag v-else-if="state === 'failed'" size="small" type="error">{{ message }}</NTag>
        <NTag v-else size="small">{{ message }}</NTag>
      </div>
    </template>
  </NCard>
</template>
