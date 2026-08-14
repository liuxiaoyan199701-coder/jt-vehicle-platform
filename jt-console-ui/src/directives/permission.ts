import type { App, Directive, DirectiveBinding } from 'vue';
import { useAuthStore } from '@/store/modules/auth';

type PermissionValue = string | string[];

/**
 * `v-permission="'vehicle:create'"` —— 无权限时把元素从 DOM 里移除。
 *
 * 这是体验优化，不是安全边界：真正的拦截在后端，同一批权限码在每次请求时都会被校验。
 * 因此隐藏失败不会造成越权，只会让人点到一个必然被拒的按钮。
 */
function resolve(binding: DirectiveBinding<PermissionValue>) {
  const value = binding.value;
  const codes = Array.isArray(value) ? value : [value];
  return codes.filter(Boolean);
}

function apply(element: HTMLElement, binding: DirectiveBinding<PermissionValue>) {
  const codes = resolve(binding);
  if (!codes.length) {
    return;
  }
  const authStore = useAuthStore();
  if (!authStore.hasAnyPermission(...codes)) {
    element.remove();
  }
}

const permission: Directive<HTMLElement, PermissionValue> = {
  mounted: apply
};

export function setupPermissionDirective(app: App) {
  app.directive('permission', permission);
}
