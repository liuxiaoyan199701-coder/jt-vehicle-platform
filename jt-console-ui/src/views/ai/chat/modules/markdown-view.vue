<script setup lang="ts">
import { computed } from 'vue';
import { marked } from 'marked';
import DOMPurify from 'dompurify';

/**
 * 渲染 AI 输出的 markdown。
 *
 * 必须过 DOMPurify 才能 v-html：模型的输出里混着从库里查出来的用户可控字符串
 * （车辆备注、告警处置说明都是自由文本），直接注入就是一个货真价实的 XSS 面，
 * 而不是理论风险。
 */
defineOptions({ name: 'MarkdownView' });

const props = defineProps<{ content: string }>();

const html = computed(() => {
  if (!props.content) return '';
  const raw = marked.parse(props.content, { async: false, breaks: true }) as string;
  return DOMPurify.sanitize(raw, { USE_PROFILES: { html: true } });
});
</script>

<template>
  <div class="markdown-view" v-html="html"></div>
</template>

<style scoped>
.markdown-view {
  /*
   * 代码块与表格边框的底色。原本写死成黑色低透明度，在深色模式下等于压在深色背景上的深色——
   * 代码块和表格线整个消失，回答里凡是带表格的部分看起来就像没渲染。
   * 拆成变量后按主题翻转，两种模式下都看得见。
   */
  --md-surface: rgb(0 0 0 / 6%);
  --md-border: rgb(0 0 0 / 12%);

  line-height: 1.7;
  word-break: break-word;
}

:global(html.dark) .markdown-view {
  --md-surface: rgb(255 255 255 / 10%);
  --md-border: rgb(255 255 255 / 18%);
}

.markdown-view :deep(p) {
  margin: 0 0 8px;
}

.markdown-view :deep(p:last-child) {
  margin-bottom: 0;
}

.markdown-view :deep(ul),
.markdown-view :deep(ol) {
  margin: 0 0 8px;
  padding-left: 20px;
}

.markdown-view :deep(code) {
  padding: 1px 5px;
  border-radius: 3px;
  background: var(--md-surface);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.92em;
}

.markdown-view :deep(pre) {
  overflow-x: auto;
  margin: 8px 0;
  padding: 10px 12px;
  border-radius: 6px;
  background: var(--md-surface);
}

.markdown-view :deep(pre code) {
  padding: 0;
  background: none;
}

/* 表格可能很宽，让它自己滚动而不是把整个消息撑出横向滚动条 */
.markdown-view :deep(table) {
  display: block;
  overflow-x: auto;
  border-collapse: collapse;
  margin: 8px 0;
}

.markdown-view :deep(th),
.markdown-view :deep(td) {
  padding: 4px 10px;
  border: 1px solid var(--md-border);
  white-space: nowrap;
}

.markdown-view :deep(h1),
.markdown-view :deep(h2),
.markdown-view :deep(h3) {
  margin: 12px 0 6px;
  font-size: 1.05em;
  font-weight: 600;
}
</style>
