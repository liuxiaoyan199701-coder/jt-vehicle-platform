<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { useMessage } from 'naive-ui';
import { changeOwnPassword } from '@/service/api';
import { useAuthStore } from '@/store/modules/auth';

defineOptions({ name: 'SystemProfile' });

const message = useMessage();
const authStore = useAuthStore();
const submitting = ref(false);
const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' });

const roleNames = computed(() =>
  (authStore.userInfo.roleDetails ?? []).map(item => item.name).join('、') || '—'
);

async function submit() {
  if (!form.oldPassword) {
    message.warning('请输入当前密码');
    return;
  }
  if (form.newPassword.length < 8) {
    message.warning('新密码至少 8 个字符');
    return;
  }
  if (form.newPassword !== form.confirmPassword) {
    message.warning('两次输入的新密码不一致');
    return;
  }
  submitting.value = true;
  try {
    const result = await changeOwnPassword(form.oldPassword, form.newPassword);
    if (result.error) {
      return;
    }
    // 当前会话会被保留，其他设备上的登录被踢掉——改密的常见动机就是怀疑凭据泄露。
    message.success('密码已修改，本账号在其他设备上的登录已失效');
    Object.assign(form, { oldPassword: '', newPassword: '', confirmPassword: '' });
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <NSpace vertical :size="12">
    <NCard title="账号信息" :bordered="false" size="small">
      <NDescriptions label-placement="left" bordered :column="2">
        <NDescriptionsItem label="用户名">{{ authStore.userInfo.userName }}</NDescriptionsItem>
        <NDescriptionsItem label="显示名称">{{ authStore.userInfo.displayName || '—' }}</NDescriptionsItem>
        <NDescriptionsItem label="所属租户">
          {{ authStore.userInfo.platform ? '平台（跨租户）' : authStore.userInfo.tenantName || '—' }}
        </NDescriptionsItem>
        <NDescriptionsItem label="角色">{{ roleNames }}</NDescriptionsItem>
      </NDescriptions>
    </NCard>

    <NCard title="修改密码" :bordered="false" size="small">
      <NForm class="max-w-460px" label-placement="left" :label-width="100">
        <NFormItem label="当前密码" required>
          <NInput v-model:value="form.oldPassword" type="password" show-password-on="click" />
        </NFormItem>
        <NFormItem label="新密码" required>
          <NInput
            v-model:value="form.newPassword"
            type="password"
            show-password-on="click"
            placeholder="至少 8 个字符"
          />
        </NFormItem>
        <NFormItem label="确认新密码" required>
          <NInput v-model:value="form.confirmPassword" type="password" show-password-on="click" />
        </NFormItem>
        <NFormItem :show-label="false">
          <NButton type="primary" :loading="submitting" @click="submit">保存</NButton>
        </NFormItem>
      </NForm>
    </NCard>
  </NSpace>
</template>
