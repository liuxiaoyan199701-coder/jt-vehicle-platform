<script setup lang="ts">
import type { AiConversation } from '@/service/api/ai';

/**
 * 会话列表。桌面放在左侧卡片里，小屏放进抽屉——两处渲染同一份，避免改一处忘另一处。
 */
defineOptions({ name: 'ConversationList' });

defineProps<{
  conversations: AiConversation[];
  activeId: number | null;
}>();

defineEmits<{
  open: [id: number];
  remove: [item: AiConversation];
}>();
</script>

<template>
  <!-- 用 ul/li + button 而不是 div@click：后者键盘完全够不着，Tab 会直接跳过整个列表 -->
  <ul class="m-0 h-full list-none overflow-y-auto p-0">
    <li v-if="!conversations.length">
      <p class="py-16px text-center text-13px text-gray-500 dark:text-gray-400">还没有对话</p>
    </li>
    <li v-for="item in conversations" :key="item.id" class="group relative mb-4px">
      <button
        type="button"
        class="w-full flex cursor-pointer items-center gap-4px border-0 rd-6px bg-transparent py-6px pl-8px pr-28px text-left transition-colors duration-200 hover:bg-gray-100 dark:hover:bg-dark-600"
        :class="activeId === item.id ? 'bg-primary/10 text-primary font-medium' : ''"
        :aria-current="activeId === item.id ? 'true' : undefined"
        @click="$emit('open', item.id)"
      >
        <SvgIcon icon="mdi:message-text-outline" class="flex-shrink-0 text-14px" />
        <span class="flex-1 truncate text-13px">{{ item.title || '新对话' }}</span>
      </button>
      <!--
        删除按钮独立于会话按钮，不能嵌在 button 里——HTML 不允许按钮嵌套，浏览器会把结构拆坏。
        focus-visible 让它在键盘 Tab 到时也显形，否则纯键盘用户根本触达不到删除。
      -->
      <NButton
        size="tiny"
        quaternary
        class="absolute right-2px top-1/2 opacity-0 -translate-y-1/2 focus-visible:opacity-100 group-hover:opacity-100"
        :aria-label="`删除对话 ${item.title || '新对话'}`"
        @click="$emit('remove', item)"
      >
        <template #icon>
          <SvgIcon icon="mdi:delete-outline" />
        </template>
      </NButton>
    </li>
  </ul>
</template>
