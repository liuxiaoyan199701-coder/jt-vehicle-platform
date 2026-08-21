<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useMessage } from 'naive-ui';
import {
  createAlarmRule,
  deleteAlarmRule,
  fetchAlarmRules,
  fetchVehicles,
  setAlarmRuleEnabled,
  updateAlarmRule,
  type AlarmRule,
  type AlarmRuleMutation,
  type AlarmRuleType,
  type AlarmLevel,
  type Vehicle
} from '@/service/api';

defineOptions({ name: 'RuleIndex' });

const TYPE_LABEL: Record<AlarmRuleType, string> = {
  SPEED_LIMIT: '超速阈值',
  IDLE_TIMEOUT: '怠速超时',
  FATIGUE_DRIVING: '疲劳驾驶'
};

const LEVEL_LABEL: Record<AlarmLevel, string> = {
  LOW: '低',
  MEDIUM: '中',
  HIGH: '高',
  CRITICAL: '严重'
};

const message = useMessage();
const rules = ref<AlarmRule[]>([]);
const vehicles = ref<Vehicle[]>([]);
const loading = ref(false);
const keyword = ref('');
const editorVisible = ref(false);
const isEdit = ref(false);
const editingId = ref<number | null>(null);
const submitting = ref(false);

const form = reactive<AlarmRuleMutation>({
  name: '',
  type: 'SPEED_LIMIT',
  thresholdKph: 80,
  durationMinutes: 0,
  level: 'MEDIUM',
  enabled: true,
  vehicleIds: []
});

const typeOptions = (Object.keys(TYPE_LABEL) as AlarmRuleType[]).map(value => ({
  label: TYPE_LABEL[value],
  value
}));
const levelOptions = (Object.keys(LEVEL_LABEL) as AlarmLevel[]).map(value => ({
  label: LEVEL_LABEL[value],
  value
}));

const filtered = computed(() => {
  const word = keyword.value.trim().toLowerCase();
  if (!word) return rules.value;
  return rules.value.filter(item => item.name.toLowerCase().includes(word));
});

const vehicleOptions = computed(() =>
  vehicles.value.map(vehicle => ({
    label: `${vehicle.plateNo} (${vehicle.deviceId})`,
    value: vehicle.deviceId
  }))
);

const needsDuration = computed(() => form.type !== 'SPEED_LIMIT');

onMounted(load);

async function load() {
  loading.value = true;
  const [ruleResult, vehicleResult] = await Promise.all([fetchAlarmRules(), fetchVehicles()]);
  loading.value = false;
  if (ruleResult.error) {
    message.error(ruleResult.error.message || '规则列表加载失败');
  } else {
    rules.value = ruleResult.data ?? [];
  }
  vehicles.value = vehicleResult.data ?? [];
}

function openCreate() {
  isEdit.value = false;
  editingId.value = null;
  Object.assign(form, {
    name: '',
    type: 'SPEED_LIMIT',
    thresholdKph: 80,
    durationMinutes: 0,
    level: 'MEDIUM',
    enabled: true,
    vehicleIds: []
  });
  editorVisible.value = true;
}

function openEdit(item: AlarmRule) {
  isEdit.value = true;
  editingId.value = item.id;
  Object.assign(form, {
    name: item.name,
    type: item.type,
    thresholdKph: item.thresholdKph,
    durationMinutes: item.durationMinutes,
    level: item.level,
    enabled: item.enabled,
    vehicleIds: [...item.vehicleIds]
  });
  editorVisible.value = true;
}

function validate() {
  if (!form.name.trim()) return '请填写规则名称';
  if (!Number.isFinite(form.thresholdKph) || form.thresholdKph <= 0 || form.thresholdKph > 200) return '阈值必须在 0 到 200 之间';
  if (needsDuration.value && (!Number.isFinite(form.durationMinutes) || form.durationMinutes <= 0)) return '持续时长必须大于 0';
  return '';
}

async function submit() {
  const error = validate();
  if (error) {
    message.warning(error);
    return;
  }
  const payload: AlarmRuleMutation = {
    ...form,
    name: form.name.trim(),
    durationMinutes: needsDuration.value ? form.durationMinutes : 0,
    vehicleIds: [...new Set(form.vehicleIds)]
  };
  submitting.value = true;
  const result = isEdit.value && editingId.value != null
    ? await updateAlarmRule(editingId.value, payload)
    : await createAlarmRule(payload);
  if (result.error || !result.data) {
    submitting.value = false;
    message.error(result.error?.message || '规则保存失败');
    return;
  }
  submitting.value = false;
  message.success(isEdit.value ? '规则已更新' : '规则已创建');
  editorVisible.value = false;
  await load();
}

async function toggleEnabled(item: AlarmRule, enabled: boolean) {
  const { error } = await setAlarmRuleEnabled(item.id, enabled);
  if (error) {
    message.error(error.message || '规则状态更新失败');
    return;
  }
  item.enabled = enabled;
  message.success(enabled ? '规则已启用' : '规则已停用');
}

async function remove(item: AlarmRule) {
  const { error } = await deleteAlarmRule(item.id);
  if (error) {
    message.error(error.message || '规则删除失败');
    return;
  }
  message.success('规则已删除');
  await load();
}
</script>

<template>
  <div class="p-16px">
    <NCard :bordered="false" size="small">
      <template #header>
        <div class="flex items-center justify-between gap-8px">
          <span>告警规则</span>
          <NButton type="primary" size="small" @click="openCreate">
            <template #icon><SvgIcon icon="lucide:plus" /></template>
            新增规则
          </NButton>
        </div>
      </template>
      <div class="pb-12px">
        <NInput v-model:value="keyword" clearable placeholder="搜索规则" style="max-width: 320px" />
      </div>
      <NSpin :show="loading">
        <NEmpty v-if="!loading && filtered.length === 0" description="暂无告警规则" class="py-40px" />
        <div v-for="item in filtered" :key="item.id" class="rule-row">
          <div class="min-w-0 flex-1">
            <div class="flex items-center justify-between gap-8px">
              <span class="font-medium">{{ item.name }}</span>
              <NSwitch :value="item.enabled" size="small" @click.stop @update:value="v => toggleEnabled(item, v)" />
            </div>
            <div class="mt-4px text-12px text-gray-500">
              {{ TYPE_LABEL[item.type] }} · 阈值 {{ item.thresholdKph }} km/h
              <template v-if="item.durationMinutes"> · 持续 {{ item.durationMinutes }} 分钟</template>
              · {{ LEVEL_LABEL[item.level] }} · {{ item.assignedVehicleCount }} 台车
            </div>
            <NSpace class="mt-6px" size="small">
              <NButton text type="primary" size="tiny" @click.stop="openEdit(item)">编辑</NButton>
              <NPopconfirm @positive-click="remove(item)">
                <template #trigger><NButton text type="error" size="tiny" @click.stop>删除</NButton></template>
                删除后历史告警仍保留。
              </NPopconfirm>
            </NSpace>
          </div>
        </div>
      </NSpin>
    </NCard>

    <NModal v-model:show="editorVisible" preset="card" :title="isEdit ? '编辑规则' : '新增规则'" style="max-width: 480px">
      <NForm label-placement="top">
        <NFormItem label="规则名称" required><NInput v-model:value="form.name" maxlength="80" show-count /></NFormItem>
        <NFormItem label="规则类型" required>
          <NSelect v-model:value="form.type" :options="typeOptions" />
        </NFormItem>
        <NGrid :cols="2" :x-gap="10">
          <NGi><NFormItem label="阈值 (km/h)" required><NInputNumber v-model:value="form.thresholdKph" :min="1" :max="200" class="w-full" /></NFormItem></NGi>
          <NGi>
            <NFormItem :label="needsDuration ? '持续时长 (分钟)' : '持续时长'">
              <NInputNumber v-model:value="form.durationMinutes" :min="0" :max="1440" :disabled="!needsDuration" class="w-full" placeholder="超速规则不用填" />
            </NFormItem>
          </NGi>
        </NGrid>
        <NFormItem label="告警级别" required><NSelect v-model:value="form.level" :options="levelOptions" /></NFormItem>
        <NFormItem label="启用"><NSwitch v-model:value="form.enabled" /></NFormItem>
        <NFormItem label="分配车辆">
          <NSelect v-model:value="form.vehicleIds" :options="vehicleOptions" multiple filterable clearable placeholder="选择已建档车辆" />
        </NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="editorVisible = false">取消</NButton>
          <NButton type="primary" :loading="submitting" @click="submit">保存</NButton>
        </NSpace>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.rule-row { padding: 10px 12px; border-top: 1px solid rgb(239 239 245); }
.rule-row:hover { background: rgb(24 160 88 / 7%); }
</style>
