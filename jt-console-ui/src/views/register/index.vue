<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useMessage } from 'naive-ui';
import { fetchRegistrationCaptcha, submitRegistration } from '@/service/api';

defineOptions({ name: 'Register' });

const router = useRouter();
const message = useMessage();
const submitting = ref(false);
const submitted = ref(false);
const captchaImage = ref('');
const captchaToken = ref('');
const captchaUnavailable = ref(false);

const form = reactive({
  companyName: '',
  contactName: '',
  contactPhone: '',
  username: '',
  password: '',
  confirmPassword: '',
  captchaCode: ''
});

onMounted(refreshCaptcha);

async function refreshCaptcha() {
  const result = await fetchRegistrationCaptcha();
  if (result.error || !result.data) {
    // 入口可由平台整体关闭，这时验证码接口也不再提供服务。
    captchaUnavailable.value = true;
    return;
  }
  captchaUnavailable.value = false;
  captchaImage.value = result.data.image;
  captchaToken.value = result.data.captchaToken;
}

async function submit() {
  if (!form.companyName.trim() || !form.contactName.trim() || !form.contactPhone.trim()) {
    message.warning('请填写完整的企业与联系人信息');
    return;
  }
  if (form.password.length < 8) {
    message.warning('密码至少 8 个字符');
    return;
  }
  if (form.password !== form.confirmPassword) {
    message.warning('两次输入的密码不一致');
    return;
  }
  submitting.value = true;
  try {
    const result = await submitRegistration({
      companyName: form.companyName.trim(),
      contactName: form.contactName.trim(),
      contactPhone: form.contactPhone.trim(),
      username: form.username.trim(),
      password: form.password,
      captchaToken: captchaToken.value,
      captchaCode: form.captchaCode
    });
    if (result.error) {
      // 验证码一次性使用，无论成败都要换一张。
      await refreshCaptcha();
      form.captchaCode = '';
      return;
    }
    submitted.value = true;
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <div class="min-h-screen flex-center bg-layout p-16px">
    <NCard class="w-full max-w-520px" :bordered="false" size="large">
      <template v-if="submitted">
        <NResult status="success" title="申请已提交" description="平台管理员审批通过后，您就可以用刚才填写的账号登录。">
          <template #footer>
            <NButton type="primary" @click="router.push('/login')">返回登录</NButton>
          </template>
        </NResult>
      </template>

      <template v-else-if="captchaUnavailable">
        <NResult status="info" title="暂未开放自助注册" description="请联系平台管理员为您开通账号。">
          <template #footer>
            <NButton @click="router.push('/login')">返回登录</NButton>
          </template>
        </NResult>
      </template>

      <template v-else>
        <div class="mb-20px">
          <h2 class="text-20px font-600">企业注册</h2>
          <p class="mt-6px text-14px text-#909399">提交后进入待审批，平台管理员通过并分配套餐后账号才可登录。</p>
        </div>
        <NForm label-placement="left" :label-width="100">
          <NFormItem label="企业名称" required>
            <NInput v-model:value="form.companyName" placeholder="营业执照上的公司名称" />
          </NFormItem>
          <NFormItem label="联系人" required>
            <NInput v-model:value="form.contactName" />
          </NFormItem>
          <NFormItem label="联系电话" required>
            <NInput v-model:value="form.contactPhone" />
          </NFormItem>
          <NFormItem label="管理员账号" required>
            <NInput v-model:value="form.username" placeholder="登录用户名" />
          </NFormItem>
          <NFormItem label="密码" required>
            <NInput v-model:value="form.password" type="password" placeholder="至少 8 个字符" />
          </NFormItem>
          <NFormItem label="确认密码" required>
            <NInput v-model:value="form.confirmPassword" type="password" />
          </NFormItem>
          <NFormItem label="验证码" required>
            <NSpace align="center" :size="10" class="w-full">
              <NInput v-model:value="form.captchaCode" class="w-140px" placeholder="不区分大小写" />
              <img
                v-if="captchaImage"
                :src="captchaImage"
                alt="验证码"
                class="h-40px cursor-pointer rounded"
                title="点击换一张"
                @click="refreshCaptcha"
              />
            </NSpace>
          </NFormItem>
          <NFormItem :show-label="false">
            <NSpace class="w-full" justify="space-between" align="center">
              <NButton text @click="router.push('/login')">已有账号，去登录</NButton>
              <NButton type="primary" :loading="submitting" @click="submit">提交申请</NButton>
            </NSpace>
          </NFormItem>
        </NForm>
      </template>
    </NCard>
  </div>
</template>
