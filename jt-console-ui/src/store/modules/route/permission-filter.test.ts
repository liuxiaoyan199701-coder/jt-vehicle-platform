import assert from 'node:assert/strict';
import test from 'node:test';
import { filterAuthRoutesByRoles } from './auth-route-filter';

/**
 * 菜单过滤只影响「看不看得见」，不影响「能不能做」——后端对同一批权限码逐请求校验。
 * 这些用例守住的是可见性本身的取舍：未声明即开放，声明了就必须命中。
 */

function route(name: string, meta: Record<string, unknown> = {}, children?: any[]): any {
  return { name, path: `/${name}`, component: 'view', meta: { title: name, ...meta }, children };
}

test('未声明角色与权限码的路由对任何已认证账号开放', () => {
  const result = filterAuthRoutesByRoles([route('home')], [], []);
  assert.equal(result.length, 1);
});

test('声明了权限码的路由只对持有者可见', () => {
  const routes = [route('system_audit', { permissions: ['system:audit:view'] })];

  assert.equal(filterAuthRoutesByRoles(routes, [], ['system:audit:view']).length, 1);
  assert.equal(filterAuthRoutesByRoles(routes, [], ['vehicle:list']).length, 0);
  assert.equal(filterAuthRoutesByRoles(routes, [], []).length, 0);
});

test('多个权限码取任一命中', () => {
  const routes = [route('system_config', { permissions: ['system:config:view', 'platform:config:manage'] })];

  assert.equal(filterAuthRoutesByRoles(routes, [], ['platform:config:manage']).length, 1);
  assert.equal(filterAuthRoutesByRoles(routes, [], ['system:config:view']).length, 1);
  assert.equal(filterAuthRoutesByRoles(routes, [], ['alarm:list']).length, 0);
});

test('角色声明与权限码声明可以并存', () => {
  const routes = [route('legacy', { roles: ['R_PLATFORM_ADMIN'] })];

  assert.equal(filterAuthRoutesByRoles(routes, ['R_PLATFORM_ADMIN'], []).length, 1);
  assert.equal(filterAuthRoutesByRoles(routes, ['R_TENANT_ADMIN'], []).length, 0);
});

test('分组路由的子项全部不可见时整组隐藏', () => {
  const routes = [
    route('system', {}, [
      route('system_tenant', { permissions: ['platform:tenant:manage'] }),
      route('system_plan', { permissions: ['platform:plan:manage'] })
    ])
  ];

  assert.equal(filterAuthRoutesByRoles(routes, [], ['vehicle:list']).length, 0);

  const visible = filterAuthRoutesByRoles(routes, [], ['platform:tenant:manage']);
  assert.equal(visible.length, 1);
  assert.equal(visible[0].children?.length, 1);
  assert.equal(visible[0].children?.[0].name, 'system_tenant');
});

test('个人中心这类自助页面不声明权限码，只读账号也看得到', () => {
  const routes = [route('system', {}, [route('system_profile')])];

  const visible = filterAuthRoutesByRoles(routes, [], []);
  assert.equal(visible.length, 1);
  assert.equal(visible[0].children?.[0].name, 'system_profile');
});
