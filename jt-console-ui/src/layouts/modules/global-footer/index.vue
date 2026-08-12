<script setup lang="ts">
defineOptions({
  name: 'GlobalFooter'
});

const REPO_URL = 'https://github.com/liuxiaoyan199701-coder/jt-vehicle-platform';
const UPSTREAM_URL = 'https://github.com/soybeanjs/soybean-admin';
</script>

<template>
  <!--
    页脚同时标注本项目与前端框架的来源：界面框架衍生自 SoybeanAdmin（MIT），
    完整的第三方归属见仓库根目录的 NOTICE。
  -->
  <DarkModeContainer class="h-full flex-center gap-8px text-13px">
    <a :href="REPO_URL" target="_blank" rel="noopener noreferrer">车联网监控平台 · Apache-2.0</a>
    <span class="text-#ccc">|</span>
    <span class="text-#999">
      UI based on
      <a :href="UPSTREAM_URL" target="_blank" rel="noopener noreferrer">SoybeanAdmin</a>
      (MIT © 2021 Soybean)
    </span>
  </DarkModeContainer>
</template>

<style scoped></style>
