<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useMessage } from 'naive-ui';
import dayjs from 'dayjs';
import {
  createDriver,
  deleteDriver,
  fetchDriverSessions,
  fetchDrivers,
  fetchIdentityEvents,
  updateDriver,
  type Driver,
  type DriverIdentityEvent,
  type DriverMutation,
  type DriverSession
} from '@/service/api';

defineOptions({ name: 'DriverIndex' });

const message = useMessage();
const tab = ref<'drivers' | 'events'>('drivers');
const drivers = ref<Driver[]>([]);
const total = ref(0);
const keyword = ref('');
const loading = ref(false);

const editorVisible = ref(false);
const isEdit = ref(false);
const editingId = ref<number | null>(null);
const submitting = ref(false);

const form = reactive<DriverMutation>({
  name: '',
  idCard: '',
  licenseNo: '',
  institution: '',
  licenseValidPeriod: '',
  phone: '',
  remark: '',
  departmentId: null
});

// 日期选择器用时间戳，提交时转回 yyyy-MM-dd
const licenseDate = ref<number | null>(null);

// 驾驶记录抽屉
const sessionsVisible = ref(false);
const sessions = ref<DriverSession[]>([]);
const sessionsLoading = ref(false);
const sessionsTitle = ref('');

// 身份事件
const events = ref<DriverIdentityEvent[]>([]);
const eventsLoading = ref(false);
const eventFilter = reactive({ failed: false, unmatched: false });

onMounted(load);

async function load() {
  loading.value = true;
  const { data, error } = await fetchDrivers({ keyword: keyword.value || undefined });
  loading.value = false;
  if (error) {
    message.error(error.message || '司机列表加载失败');
    return;
  }
  drivers.value = data?.items ?? [];
  total.value = data?.total ?? 0;
}

function search() {
  load();
}

function openCreate() {
  isEdit.value = false;
  editingId.value = null;
  Object.assign(form, {
    name: '', idCard: '', licenseNo: '', institution: '', licenseValidPeriod: '',
    phone: '', remark: '', departmentId: null
  });
  licenseDate.value = null;
  editorVisible.value = true;
}

function openEdit(item: Driver) {
  isEdit.value = true;
  editingId.value = item.id;
  Object.assign(form, {
    name: item.name, idCard: item.idCard, licenseNo: item.licenseNo,
    institution: item.institution ?? '', licenseValidPeriod: item.licenseValidPeriod ?? '',
    phone: item.phone ?? '', remark: item.remark ?? '', departmentId: item.departmentId
  });
  licenseDate.value = item.licenseValidPeriod ? dayjs(item.licenseValidPeriod).valueOf() : null;
  editorVisible.value = true;
}

function validate() {
  if (!form.name.trim()) return '请填写司机姓名';
  if (!form.idCard.trim()) return '请填写身份证号';
  if (!form.licenseNo.trim()) return '请填写从业资格证编码';
  return '';
}

async function submit() {
  const error = validate();
  if (error) {
    message.warning(error);
    return;
  }
  submitting.value = true;
  const payload: DriverMutation = {
    ...form,
    name: form.name.trim(),
    idCard: form.idCard.trim(),
    licenseNo: form.licenseNo.trim(),
    licenseValidPeriod: licenseDate.value ? dayjs(licenseDate.value).format('YYYY-MM-DD') : null
  };
  const result = isEdit.value && editingId.value != null
    ? await updateDriver(editingId.value, payload)
    : await createDriver(payload);
  submitting.value = false;
  if (result.error || !result.data) {
    message.error(result.error?.message || '司机保存失败');
    return;
  }
  message.success(isEdit.value ? '司机已更新' : '司机已建档');
  editorVisible.value = false;
  await load();
}

async function remove(item: Driver) {
  const { error } = await deleteDriver(item.id);
  if (error) {
    message.error(error.message || '删除失败');
    return;
  }
  message.success('司机已删除');
  await load();
}

async function openSessions(item: Driver) {
  sessionsTitle.value = `${item.name} 的驾驶记录`;
  sessionsVisible.value = true;
  sessionsLoading.value = true;
  const { data } = await fetchDriverSessions(item.id);
  sessions.value = data ?? [];
  sessionsLoading.value = false;
}

async function loadEvents() {
  eventsLoading.value = true;
  const { data, error } = await fetchIdentityEvents({
    failed: eventFilter.failed || undefined,
    unmatched: eventFilter.unmatched || undefined
  });
  eventsLoading.value = false;
  if (error) {
    message.error(error.message || '事件加载失败');
    return;
  }
  events.value = data ?? [];
}

function expiringSoon(item: Driver): boolean {
  if (!item.licenseValidPeriod) return false;
  const period = dayjs(item.licenseValidPeriod);
  if (!period.isValid()) return false;
  const diff = period.diff(dayjs(), 'day');
  return diff <= 30;
}

function fmt(iso: string) {
  return dayjs(iso).format('MM-DD HH:mm:ss');
}

const statusLabel = (status: number) => (status === 0 ? '插卡上班' : status === 1 ? '拔卡下班' : `状态${status}`);
</script>

<template>
  <div class="p-16px">
    <NCard :bordered="false" size="small">
      <template #header>
        <div class="flex items-center justify-between gap-8px">
          <NTabs v-model:value="tab" type="line" size="small" @update:value="v => v === 'events' && loadEvents()">
            <NTab name="drivers">司机档案</NTab>
            <NTab name="events">身份事件</NTab>
          </NTabs>
          <NButton v-if="tab === 'drivers'" type="primary" size="small" @click="openCreate">
            <template #icon><SvgIcon icon="lucide:plus" /></template>
            新增司机
          </NButton>
        </div>
      </template>

      <template v-if="tab === 'drivers'">
        <div class="pb-12px flex items-center gap-8px">
          <NInput v-model:value="keyword" clearable placeholder="姓名 / 从业资格证编码" class="w-260px" @keyup.enter="search" />
          <NButton size="small" @click="search">搜索</NButton>
          <span class="text-12px text-gray-500">共 {{ total }} 名司机</span>
        </div>

        <NSpin :show="loading">
          <NEmpty v-if="!loading && drivers.length === 0" description="暂无司机档案" class="py-40px" />
          <div v-for="item in drivers" :key="item.id" class="driver-row">
            <div class="min-w-0 flex-1">
              <div class="flex items-center justify-between gap-8px">
                <span class="font-medium">{{ item.name }}</span>
                <NTag v-if="expiringSoon(item)" size="tiny" type="error">证件临期/过期</NTag>
              </div>
              <div class="mt-4px text-12px text-gray-500">
                身份证 {{ item.idCard }} · 资格证 {{ item.licenseNo }}
                <template v-if="item.licenseValidPeriod"> · 有效期至 {{ item.licenseValidPeriod }}</template>
                <template v-if="item.phone"> · {{ item.phone }}</template>
              </div>
              <NSpace class="mt-6px" size="small">
                <NButton text type="primary" size="tiny" @click.stop="openEdit(item)">编辑</NButton>
                <NButton text type="primary" size="tiny" @click.stop="openSessions(item)">驾驶记录</NButton>
                <NPopconfirm @positive-click="remove(item)">
                  <template #trigger><NButton text type="error" size="tiny" @click.stop>删除</NButton></template>
                  删除后历史驾驶区间仍保留。
                </NPopconfirm>
              </NSpace>
            </div>
          </div>
        </NSpin>
      </template>

      <template v-else>
        <div class="pb-12px flex items-center gap-8px">
          <NCheckbox v-model:checked="eventFilter.failed" @update:checked="loadEvents">读取失败</NCheckbox>
          <NCheckbox v-model:checked="eventFilter.unmatched" @update:checked="loadEvents">未建档</NCheckbox>
          <NButton size="small" @click="loadEvents">刷新</NButton>
        </div>
        <NSpin :show="eventsLoading">
          <NEmpty v-if="!eventsLoading && events.length === 0" description="暂无身份事件" class="py-40px" />
          <div v-for="item in events" :key="item.id" class="driver-row">
            <div class="min-w-0 flex-1">
              <div class="flex items-center justify-between gap-8px">
                <span class="font-medium">{{ item.name ?? '未读取到姓名' }}</span>
                <NSpace size="small">
                  <NTag size="tiny" :type="item.cardStatus === 0 ? 'success' : 'error'">
                    {{ item.cardStatus === 0 ? '读取成功' : '读取失败' }}
                  </NTag>
                  <NTag size="tiny" :type="item.driverId ? 'info' : 'warning'">
                    {{ item.driverId ? '已建档' : '未建档' }}
                  </NTag>
                </NSpace>
              </div>
              <div class="mt-4px text-12px text-gray-500">
                {{ statusLabel(item.status) }} · 设备 {{ item.deviceId }} · {{ fmt(item.deviceTime) }}
                <template v-if="item.licenseNo"> · 资格证 {{ item.licenseNo }}</template>
              </div>
            </div>
          </div>
        </NSpin>
      </template>
    </NCard>

    <NModal v-model:show="editorVisible" preset="card" :title="isEdit ? '编辑司机' : '新增司机'" style="max-width: 520px">
      <NForm label-placement="top">
        <NGrid :cols="2" :x-gap="10">
          <NGi><NFormItem label="姓名" required><NInput v-model:value="form.name" /></NFormItem></NGi>
          <NGi><NFormItem label="身份证号" required><NInput v-model:value="form.idCard" /></NFormItem></NGi>
        </NGrid>
        <NFormItem label="从业资格证编码" required><NInput v-model:value="form.licenseNo" /></NFormItem>
        <NGrid :cols="2" :x-gap="10">
          <NGi><NFormItem label="发证机构"><NInput v-model:value="form.institution" /></NFormItem></NGi>
          <NGi><NFormItem label="证件有效期"><NDatePicker v-model:value="licenseDate" type="date" clearable class="w-full" /></NFormItem></NGi>
        </NGrid>
        <NFormItem label="联系电话"><NInput v-model:value="form.phone" /></NFormItem>
        <NFormItem label="备注"><NInput v-model:value="form.remark" type="textarea" :rows="2" /></NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="editorVisible = false">取消</NButton>
          <NButton type="primary" :loading="submitting" @click="submit">保存</NButton>
        </NSpace>
      </template>
    </NModal>

    <NDrawer :show="sessionsVisible" :width="480" placement="right" @update:show="sessionsVisible = $event">
      <NDrawerContent :title="sessionsTitle" closable>
        <NSpin :show="sessionsLoading">
          <NEmpty v-if="!sessionsLoading && sessions.length === 0" description="暂无驾驶记录" class="py-40px" />
          <div v-for="item in sessions" :key="item.id" class="driver-row">
            <div class="text-13px">
              设备 {{ item.deviceId }}
              <span class="text-gray-500">· {{ item.source === 'CARD' ? '刷卡' : '手动' }}</span>
            </div>
            <div class="text-12px text-gray-500">
              {{ fmt(item.startedAt) }} ~ {{ item.endedAt ? fmt(item.endedAt) : '至今' }}
            </div>
          </div>
        </NSpin>
      </NDrawerContent>
    </NDrawer>
  </div>
</template>

<style scoped>
.driver-row { padding: 10px 12px; border-top: 1px solid rgb(239 239 245); }
.driver-row:hover { background: rgb(24 160 88 / 7%); }
</style>
