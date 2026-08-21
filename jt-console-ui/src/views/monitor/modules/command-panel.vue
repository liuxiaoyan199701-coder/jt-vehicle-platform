<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useMessage } from 'naive-ui';
import {
  fetchRecentMedia,
  queryTerminalInfo,
  sendDeviceCommand,
  type LiveStatus,
  type MediaFileItem
} from '@/service/api';

defineOptions({ name: 'CommandPanel' });

const props = defineProps<{ visible: boolean; vehicle: LiveStatus | null }>();
const emit = defineEmits<{ 'update:visible': [value: boolean] }>();

const message = useMessage();

const plateNo = computed(() => props.vehicle?.plateNo ?? props.vehicle?.deviceId ?? '');

// ---------------- 文本下发 ----------------
const text = ref({ content: '', display: true, tts: false, emergency: false });
const textSending = ref(false);

// ---------------- 云台 ----------------
const ptzChannel = ref(1);
const ptzSpeed = ref(128);
const ptzSending = ref(false);
const adjustSending = ref(false);

// ---------------- 车辆控制 ----------------
const lockSending = ref(false);

// ---------------- 拍照 ----------------
const photo = ref({ channel: 1, count: 1, resolution: 2 });
const photoSending = ref(false);
const photos = ref<MediaFileItem[]>([]);
const photosLoading = ref(false);

// ---------------- 电话回拨 ----------------
const callback = ref({ phoneNumber: '', mode: 'call' });
const callbackSending = ref(false);

// ---------------- 临时位置跟踪 ----------------
const track = ref({ interval: 5, validity: 300 });
const trackSending = ref(false);

// ---------------- 终端信息 ----------------
const terminalInfo = ref('');
const infoLoading = ref(false);

const resolutionOptions = [
  { label: '320×240', value: 1 },
  { label: '640×480', value: 2 },
  { label: '800×600', value: 3 },
  { label: '1024×768', value: 4 },
  { label: 'CIF 352×288', value: 6 },
  { label: 'D1 704×576', value: 8 }
];

watch(
  () => props.visible,
  async isVisible => {
    if (isVisible && props.vehicle) {
      await loadPhotos();
    }
  }
);

function requireDevice(): string | null {
  if (!props.vehicle) {
    return null;
  }
  if (!props.vehicle.online) {
    message.warning('设备离线，无法下发指令');
    return null;
  }
  return props.vehicle.deviceId;
}

async function run(command: Parameters<typeof sendDeviceCommand>[0], payload: Record<string, unknown>, sending: { value: boolean }) {
  const deviceId = requireDevice();
  if (!deviceId) return;

  sending.value = true;
  const { data, error } = await sendDeviceCommand(command, { ...payload, deviceId });
  sending.value = false;

  if (error || !data) {
    message.error(error?.message || '指令下发失败');
    return false;
  }
  if (data.success === false) {
    message.error(data.message);
    return false;
  }
  message.success(data.message);
  return true;
}

// ---------------- 文本 ----------------

async function sendText() {
  if (!text.value.content.trim()) {
    message.warning('请输入文本内容');
    return;
  }
  await run('text', { ...text.value }, textSending);
  text.value.content = '';
}

// ---------------- 云台 ----------------

async function sendPtz(direction: number) {
  await run('ptz', { channelNo: ptzChannel.value, param1: direction, param2: ptzSpeed.value }, ptzSending);
}

async function sendAdjust(action: string, param: number) {
  await run('ptz-adjust', { channelNo: ptzChannel.value, action, param }, adjustSending);
}

// ---------------- 车辆控制 ----------------

async function sendLock(lock: boolean) {
  await run('vehicle-control', { lock }, lockSending);
}

// ---------------- 拍照 ----------------

async function takePhoto() {
  const ok = await run('photo', { ...photo.value }, photoSending);
  if (ok) {
    message.info('照片正在上传，稍后刷新列表查看');
    setTimeout(loadPhotos, 3000);
  }
}

async function loadPhotos() {
  if (!props.vehicle) return;
  photosLoading.value = true;
  const { data } = await fetchRecentMedia(props.vehicle.deviceId, 12);
  photos.value = data ?? [];
  photosLoading.value = false;
}

// ---------------- 电话回拨 ----------------

async function sendCallback() {
  if (!/^[0-9]{5,20}$/.test(callback.value.phoneNumber.trim())) {
    message.warning('请输入 5-20 位数字的电话号码');
    return;
  }
  await run('callback', { ...callback.value, phoneNumber: callback.value.phoneNumber.trim() }, callbackSending);
}

// ---------------- 临时位置跟踪 ----------------

async function sendTrackFollow() {
  await run('track-follow', { ...track.value }, trackSending);
}

async function queryTerminal(kind: 'query-params' | 'query-attributes') {
  const deviceId = requireDevice();
  if (!deviceId) return;
  infoLoading.value = true;
  const { data, error } = await queryTerminalInfo(kind, deviceId);
  infoLoading.value = false;
  if (error || !data) {
    message.error(error?.message || '查询失败');
    terminalInfo.value = '';
    return;
  }
  terminalInfo.value = JSON.stringify(data, null, 2);
}

const ptzDirections = [
  { key: 1, label: '上', icon: 'lucide:chevron-up' },
  { key: 3, label: '左', icon: 'lucide:chevron-left' },
  { key: 0, label: '停止', icon: 'lucide:octagon' },
  { key: 4, label: '右', icon: 'lucide:chevron-right' },
  { key: 2, label: '下', icon: 'lucide:chevron-down' }
];

const adjustActions = [
  { key: 'zoom', up: '变倍+', down: '变倍−' },
  { key: 'focus', up: '调焦+', down: '调焦−' },
  { key: 'iris', up: '光圈+', down: '光圈−' }
];
</script>

<template>
  <NModal
    :show="visible"
    preset="card"
    :title="`远程控制 - ${plateNo}`"
    class="w-720px"
    @update:show="emit('update:visible', false)"
  >
    <NAlert v-if="vehicle && !vehicle.online" type="warning" :bordered="false" class="mb-12px">
      设备离线，所有指令暂不可用。请确认车机已连接平台。
    </NAlert>

    <NTabs type="line" size="small">
      <!-- ============ 文本下发 ============ -->
      <NTabPane name="text" tab="文本下发">
        <div class="flex flex-col gap-12px py-8px">
          <NInput
            v-model:value="text.content"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-count
            placeholder="发送到终端显示器的文本，如：前方路段拥堵，请减速慢行"
          />
          <div class="flex items-center gap-16px">
            <NCheckbox v-model:checked="text.display">显示器显示</NCheckbox>
            <NCheckbox v-model:checked="text.tts">语音播读</NCheckbox>
            <NCheckbox v-model:checked="text.emergency">紧急</NCheckbox>
            <NButton type="primary" size="small" :loading="textSending" @click="sendText">
              下发
            </NButton>
          </div>
        </div>
      </NTabPane>

      <!-- ============ 云台控制 ============ -->
      <NTabPane name="ptz" tab="云台控制">
        <div class="flex flex-col items-center gap-12px py-8px">
          <div class="flex items-center gap-8px">
            <span class="text-13px text-#666">通道</span>
            <NInputNumber v-model:value="ptzChannel" :min="1" :max="255" size="small" class="w-80px" />
            <span class="ml-8px text-13px text-#666">速度</span>
            <NSlider v-model:value="ptzSpeed" :min="1" :max="255" class="w-140px" />
          </div>

          <div class="grid grid-cols-3 gap-8px">
            <div v-for="item in ptzDirections" :key="item.key" class="flex-center">
              <NButton
                circle
                size="large"
                :type="item.key === 0 ? 'error' : 'default'"
                :loading="ptzSending"
                @click="sendPtz(item.key)"
              >
                <template #icon><SvgIcon :icon="item.icon" /></template>
              </NButton>
            </div>
          </div>

          <NDivider class="!my-4px">镜头调整</NDivider>
          <div class="flex items-center gap-16px">
            <template v-for="action in adjustActions" :key="action.key">
              <div class="flex items-center gap-4px">
                <span class="w-48px text-right text-13px text-#666">{{ action.up.replace(/[+−]/, '') }}</span>
                <NButton size="small" :loading="adjustSending" @click="sendAdjust(action.key, 0)">+</NButton>
                <NButton size="small" :loading="adjustSending" @click="sendAdjust(action.key, 1)">−</NButton>
              </div>
            </template>
            <div class="flex items-center gap-4px">
              <NButton size="small" :loading="adjustSending" @click="sendAdjust('wiper', 1)">雨刷</NButton>
              <NButton size="small" :loading="adjustSending" @click="sendAdjust('fill-light', 1)">补光</NButton>
            </div>
          </div>
        </div>
      </NTabPane>

      <!-- ============ 车辆控制 ============ -->
      <NTabPane name="control" tab="车辆控制">
        <div class="flex items-center gap-16px py-16px">
          <span class="text-13px text-#666">
            通过 0x8500 下发。按设备注册的协议版本编码（2019 版经 param 表达，2011/2013 版经 type 位）。
          </span>
          <NButton type="success" size="small" :loading="lockSending" @click="sendLock(false)">车门解锁</NButton>
          <NPopconfirm @positive-click="sendLock(true)">
            <template #trigger>
              <NButton type="error" size="small" :loading="lockSending">车门加锁</NButton>
            </template>
            加锁后车辆可能无法启动，确认下发？
          </NPopconfirm>
        </div>
      </NTabPane>

      <!-- ============ 拍照 ============ -->
      <NTabPane name="photo" tab="拍照">
        <div class="flex flex-col gap-12px py-8px">
          <div class="flex items-center gap-8px">
            <span class="text-13px text-#666">通道</span>
            <NInputNumber v-model:value="photo.channel" :min="1" :max="255" size="small" class="w-80px" />
            <span class="ml-8px text-13px text-#666">张数</span>
            <NInputNumber v-model:value="photo.count" :min="1" :max="10" size="small" class="w-80px" />
            <span class="ml-8px text-13px text-#666">分辨率</span>
            <NSelect v-model:value="photo.resolution" :options="resolutionOptions" size="small" class="w-140px" />
            <NButton type="primary" size="small" :loading="photoSending" @click="takePhoto">拍照</NButton>
            <NButton size="small" :loading="photosLoading" @click="loadPhotos">刷新</NButton>
          </div>

          <div v-if="photos.length" class="grid grid-cols-4 gap-8px">
            <div v-for="item in photos" :key="item.id" class="group relative">
              <a v-if="item.accessAddress" :href="item.accessAddress" target="_blank" rel="noopener noreferrer">
                <img
                  :src="item.accessAddress"
                  :alt="item.fileName ?? String(item.fileId)"
                  class="aspect-video w-full rounded object-cover"
                />
              </a>
              <div v-else class="aspect-video w-full rounded bg-gray-100 flex-center text-11px text-#999">
                未配置访问地址
              </div>
              <div class="mt-4px text-11px text-#666 truncate">
                {{ item.capturedAt.replace('T', ' ').slice(0, 16) }} · {{ item.fileFormat ?? item.fileType }}
              </div>
            </div>
          </div>
          <NEmpty v-else-if="!photosLoading" description="还没有拍照记录" class="py-16px" />
        </div>
      </NTabPane>

      <!-- ============ 电话回拨 ============ -->
      <NTabPane name="callback" tab="电话回拨">
        <div class="flex items-center gap-8px py-16px">
          <NRadioGroup v-model:value="callback.mode" size="small">
            <NRadioButton value="call">通话</NRadioButton>
            <NRadioButton value="monitor">监听</NRadioButton>
          </NRadioGroup>
          <NInput
            v-model:value="callback.phoneNumber"
            placeholder="回拨电话号码（5-20 位数字）"
            class="w-220px"
          />
          <NButton type="primary" size="small" :loading="callbackSending" @click="sendCallback">回拨</NButton>
        </div>
      </NTabPane>

      <!-- ============ 临时位置跟踪 ============ -->
      <NTabPane name="track" tab="临时跟踪">
        <div class="flex items-center gap-8px py-16px">
          <span class="text-13px text-#666">上报间隔（秒）</span>
          <NInputNumber v-model:value="track.interval" :min="1" :max="3600" size="small" class="w-100px" />
          <span class="ml-8px text-13px text-#666">有效期（秒）</span>
          <NInputNumber v-model:value="track.validity" :min="1" :max="86400" size="small" class="w-110px" />
          <NButton type="primary" size="small" :loading="trackSending" @click="sendTrackFollow">
            启动跟踪
          </NButton>
          <span class="text-12px text-#999">
            有效期内终端按设定间隔上报位置，监控页无需刷新即可看到密集轨迹。
          </span>
        </div>
      </NTabPane>

      <!-- ============ 终端信息 ============ -->
      <NTabPane name="info" tab="终端信息">
        <div class="flex flex-col gap-12px py-8px">
          <NSpace>
            <NButton size="small" :loading="infoLoading" @click="queryTerminal('query-params')">查询终端参数</NButton>
            <NButton size="small" :loading="infoLoading" @click="queryTerminal('query-attributes')">查询终端属性</NButton>
          </NSpace>
          <NInput
            v-if="terminalInfo"
            type="textarea"
            :value="terminalInfo"
            :rows="14"
            readonly
            class="font-mono!"
          />
          <NEmpty v-else description="点击上方按钮查询终端参数（8104）或属性（8107）" class="py-16px" />
        </div>
      </NTabPane>
    </NTabs>
  </NModal>
</template>
