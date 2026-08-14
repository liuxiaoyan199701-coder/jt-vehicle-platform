import type { ElegantConstRoute } from '@elegant-router/types';

/**
 * 按角色与权限码过滤可见路由。
 *
 * 单独成文件而不是留在 shared.ts：那里同时做图标渲染，会连带引入 `.vue` 组件，
 * 让这段纯判定逻辑没法在无 Vue 运行时的单元测试里跑起来。
 *
 * 隐藏菜单只是体验优化——后端对同一批权限码逐请求校验，手输 URL 得不到任何东西。
 */
export function filterAuthRoutesByRoles(
  routes: ElegantConstRoute[],
  roles: string[],
  permissions: string[] = []
) {
  return routes.flatMap(route => filterAuthRouteByRoles(route, roles, permissions));
}

function filterAuthRouteByRoles(
  route: ElegantConstRoute,
  roles: string[],
  permissions: string[]
): ElegantConstRoute[] {
  const routeRoles = (route.meta && route.meta.roles) || [];
  const routePermissions = (route.meta && route.meta.permissions) || [];

  // 两者都没声明即对任何已认证账号开放（首页、个人中心这类自助页面）
  const isUnrestricted = !routeRoles.length && !routePermissions.length;

  const hasRole = routeRoles.some(role => roles.includes(role));
  const hasPermissionCode = routePermissions.some(code => permissions.includes(code));
  const hasPermission = hasRole || hasPermissionCode;

  const filterRoute = { ...route };

  if (filterRoute.children?.length) {
    filterRoute.children = filterRoute.children.flatMap(item =>
      filterAuthRouteByRoles(item, roles, permissions)
    );
  }

  // 子项被过滤光的分组整体隐藏，避免留下一个点不开的空菜单
  if (filterRoute.children?.length === 0) {
    return [];
  }

  return hasPermission || isUnrestricted ? [filterRoute] : [];
}
