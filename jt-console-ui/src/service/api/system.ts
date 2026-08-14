import { request } from '../request';

/** 账号读模型。刻意不含密码字段——后端从类型上就不会返回。 */
export interface AccountView {
  id: number;
  username: string;
  displayName?: string | null;
  tenantId?: number | null;
  tenantName?: string | null;
  departmentId?: number | null;
  departmentName?: string | null;
  positionId?: number | null;
  positionName?: string | null;
  status: string;
  lastLoginAt?: string | null;
  createdAt: string;
  updatedAt: string;
  roles: RoleSummary[];
}

export interface RoleSummary {
  id: number;
  code: string;
  name: string;
  builtin: boolean;
}

export interface Role {
  id: number;
  tenantId?: number | null;
  code: string;
  name: string;
  builtin: boolean;
  dataScope: string;
  remark?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface RoleDetails {
  role: Role;
  permissions: string[];
  departmentIds: number[];
  accountCount: number;
}

export interface PermissionDefinition {
  code: string;
  module: string;
  name: string;
  platformOnly: boolean;
  write: boolean;
  sortOrder: number;
}

export interface DepartmentNode {
  id: number;
  parentId?: number | null;
  name: string;
  sortOrder: number;
  enabled: boolean;
  accountCount: number;
  vehicleCount: number;
  children: DepartmentNode[];
}

export interface Position {
  id: number;
  tenantId: number;
  name: string;
  sortOrder: number;
  remark?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AuditEntry {
  id: number;
  occurredAt: string;
  tenantId?: number | null;
  accountId?: number | null;
  username?: string | null;
  action: string;
  resourceType?: string | null;
  resourceId?: string | null;
  method?: string | null;
  path?: string | null;
  detail?: string | null;
  sourceIp?: string | null;
  result: string;
  statusCode?: number | null;
  durationMs?: number | null;
}

export interface AuditPage {
  records: AuditEntry[];
  total: number;
  current: number;
  size: number;
}

export interface AccountPayload {
  username?: string;
  password?: string;
  displayName?: string | null;
  tenantId?: number | null;
  departmentId?: number | null;
  positionId?: number | null;
  roleIds: number[];
}

export interface RolePayload {
  tenantId?: number | null;
  code?: string;
  name: string;
  dataScope: string;
  remark?: string | null;
  permissions: string[];
  departmentIds?: number[];
}

export function fetchAccounts(params?: { tenantId?: number | null; keyword?: string }) {
  return request<AccountView[]>({ url: '/system/accounts', params });
}

export function createAccount(data: AccountPayload) {
  return request<AccountView>({ url: '/system/accounts', method: 'post', data });
}

export function updateAccount(id: number, data: AccountPayload) {
  return request<AccountView>({ url: `/system/accounts/${id}`, method: 'put', data });
}

export function changeAccountStatus(id: number, enabled: boolean) {
  return request<void>({ url: `/system/accounts/${id}/status`, method: 'put', data: { enabled } });
}

export function resetAccountPassword(id: number, newPassword: string) {
  return request<void>({ url: `/system/accounts/${id}/password`, method: 'put', data: { newPassword } });
}

export function deleteAccount(id: number) {
  return request<void>({ url: `/system/accounts/${id}`, method: 'delete' });
}

export function fetchPermissionCatalog() {
  return request<PermissionDefinition[]>({ url: '/system/roles/permissions' });
}

export function fetchRoles(params?: { tenantId?: number | null }) {
  return request<RoleDetails[]>({ url: '/system/roles', params });
}

export function createRole(data: RolePayload) {
  return request<RoleDetails>({ url: '/system/roles', method: 'post', data });
}

export function updateRole(id: number, data: RolePayload) {
  return request<RoleDetails>({ url: `/system/roles/${id}`, method: 'put', data });
}

export function deleteRole(id: number) {
  return request<void>({ url: `/system/roles/${id}`, method: 'delete' });
}

export function fetchDepartmentTree(params?: { tenantId?: number | null }) {
  return request<DepartmentNode[]>({ url: '/system/departments', params });
}

export function createDepartment(data: {
  tenantId?: number | null;
  parentId?: number | null;
  name: string;
  sortOrder?: number;
  enabled?: boolean;
}) {
  return request<{ id: number }>({ url: '/system/departments', method: 'post', data });
}

export function updateDepartment(
  id: number,
  data: { parentId?: number | null; name: string; sortOrder?: number; enabled?: boolean }
) {
  return request<{ id: number }>({ url: `/system/departments/${id}`, method: 'put', data });
}

export function deleteDepartment(id: number) {
  return request<void>({ url: `/system/departments/${id}`, method: 'delete' });
}

export function fetchPositions(params?: { tenantId?: number | null }) {
  return request<Position[]>({ url: '/system/positions', params });
}

export function createPosition(data: { tenantId?: number | null; name: string; sortOrder?: number; remark?: string }) {
  return request<Position>({ url: '/system/positions', method: 'post', data });
}

export function updatePosition(id: number, data: { name: string; sortOrder?: number; remark?: string }) {
  return request<Position>({ url: `/system/positions/${id}`, method: 'put', data });
}

export function deletePosition(id: number) {
  return request<void>({ url: `/system/positions/${id}`, method: 'delete' });
}

export function fetchAuditLog(params: {
  tenantId?: number | null;
  username?: string;
  action?: string;
  resourceType?: string;
  result?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}) {
  return request<AuditPage>({ url: '/system/audit', params });
}

/** 修改自己的密码。只读账号同样可用——它走的是自助通道，不需要任何权限码。 */
export function changeOwnPassword(oldPassword: string, newPassword: string) {
  return request<void>({ url: '/auth/changePassword', method: 'post', data: { oldPassword, newPassword } });
}
