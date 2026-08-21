<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import { fetchWaybillRaw, fetchWaybills, type WaybillItem } from '@/service/api';
import { formatConsoleTime } from '@/utils/fleet-operations';

const props = defineProps<{ deviceId: string }>();

const loading = ref(false);
const rows = ref<WaybillItem[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = 10;

onMounted(load);
watch(() => props.deviceId, () => {
  page.value = 1;
  void load();
});

async function load() {
  loading.value = true;
  const { data } = await fetchWaybills(props.deviceId, page.value, pageSize);
  loading.value = false;
  rows.value = data?.items ?? [];
  total.value = data?.total ?? 0;
}

function decodeBase64(base64: string) {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
  return bytes;
}

async function download(item: WaybillItem) {
  const { data } = await fetchWaybillRaw(props.deviceId, item.id);
  if (!data) return;
  const url = URL.createObjectURL(new Blob([decodeBase64(data.base64)], { type: 'application/octet-stream' }));
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = data.fileName;
  anchor.click();
  URL.revokeObjectURL(url);
}
</script>

<template>
  <NSpin :show="loading">
    <NEmpty v-if="!loading && rows.length === 0" description="暂无电子运单上报" class="py-32px" />
    <NList v-else bordered>
      <NListItem v-for="item in rows" :key="item.id">
        <div class="w-full min-w-0">
          <div class="flex items-center justify-between gap-8px">
            <div>
              <span class="font-medium">{{ formatConsoleTime(item.reportedAt) }}</span>
              <span class="ml-8px text-12px text-gray-500">{{ item.rawLength }} 字节</span>
            </div>
            <NButton size="tiny" secondary @click="download(item)">
              <template #icon><SvgIcon icon="lucide:download" /></template>
              原文
            </NButton>
          </div>
          <NAlert v-if="!item.utf8" type="warning" :bordered="false" class="mt-8px">
            {{ item.preview }}
          </NAlert>
          <pre v-else class="waybill-preview mt-8px">{{ item.preview || '（空文本）' }}</pre>
        </div>
      </NListItem>
    </NList>
    <NPagination
      v-if="total > pageSize"
      v-model:page="page"
      :page-size="pageSize"
      :item-count="total"
      class="mt-12px justify-end"
      @update:page="load"
    />
  </NSpin>
</template>

<style scoped>
.waybill-preview {
  max-height: 180px;
  overflow: auto;
  padding: 8px;
  border-radius: 4px;
  background: rgb(0 0 0 / 4%);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: 12px;
}
</style>
