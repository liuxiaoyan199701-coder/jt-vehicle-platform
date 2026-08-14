import { request } from '../request';

export interface Tenant {
  id: number;
  code: string;
  name: string;
  status: string;
  planId?: number | null;
  expiresAt?: string | null;
  contactName?: string | null;
  contactPhone?: string | null;
  remark?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface TenantView {
  tenant: Tenant;
  planName?: string | null;
  /** 0 表示不限量 */
  maxVehicles: number;
  maxAccounts: number;
  vehicleCount: number;
  accountCount: number;
  expired: boolean;
  active: boolean;
}

export interface Plan {
  id: number;
  name: string;
  maxVehicles: number;
  maxAccounts: number;
  priceCents: number;
  periodMonths: number;
  enabled: boolean;
  remark?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface PlanView {
  plan: Plan;
  tenantCount: number;
}

export interface TenantOrder {
  id: number;
  tenantId: number;
  planId?: number | null;
  planName?: string | null;
  months: number;
  amountCents: number;
  previousExpiresAt?: string | null;
  newExpiresAt?: string | null;
  operator: string;
  remark?: string | null;
  createdAt: string;
}

export interface TenantRegistration {
  id: number;
  tenantId: number;
  accountId: number;
  companyName: string;
  contactName: string;
  contactPhone: string;
  username: string;
  status: string;
  reviewedBy?: string | null;
  reviewedAt?: string | null;
  reviewNote?: string | null;
  sourceIp?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface TenantPayload {
  code: string;
  name: string;
  planId?: number | null;
  expiresAt?: string | null;
  contactName?: string | null;
  contactPhone?: string | null;
  remark?: string | null;
}

export function fetchTenants() {
  return request<TenantView[]>({ url: '/platform/tenants' });
}

export function createTenant(data: TenantPayload) {
  return request<TenantView>({ url: '/platform/tenants', method: 'post', data });
}

export function updateTenant(id: number, data: TenantPayload) {
  return request<TenantView>({ url: `/platform/tenants/${id}`, method: 'put', data });
}

export function changeTenantStatus(id: number, enabled: boolean) {
  return request<TenantView>({ url: `/platform/tenants/${id}/status`, method: 'put', data: { enabled } });
}

export function deleteTenant(id: number) {
  return request<void>({ url: `/platform/tenants/${id}`, method: 'delete' });
}

export function fetchTenantOrders(id: number) {
  return request<TenantOrder[]>({ url: `/platform/tenants/${id}/orders` });
}

/** {@code months} 允许为负，用于红冲纠错。 */
export function renewTenant(id: number, data: { planId?: number | null; months: number; amountCents: number; remark?: string }) {
  return request<TenantOrder>({ url: `/platform/tenants/${id}/renew`, method: 'post', data });
}

export function fetchPlans() {
  return request<PlanView[]>({ url: '/platform/plans' });
}

export function createPlan(data: Omit<Plan, 'id' | 'createdAt' | 'updatedAt'>) {
  return request<Plan>({ url: '/platform/plans', method: 'post', data });
}

export function updatePlan(id: number, data: Omit<Plan, 'id' | 'createdAt' | 'updatedAt'>) {
  return request<Plan>({ url: `/platform/plans/${id}`, method: 'put', data });
}

export function deletePlan(id: number) {
  return request<void>({ url: `/platform/plans/${id}`, method: 'delete' });
}

export function fetchRecentOrders(limit = 50) {
  return request<TenantOrder[]>({ url: '/platform/plans/orders', params: { limit } });
}

export function fetchRegistrations(status?: string) {
  return request<TenantRegistration[]>({ url: '/platform/registrations', params: { status } });
}

export function approveRegistration(id: number, data: { planId?: number | null; months?: number | null }) {
  return request<void>({ url: `/platform/registrations/${id}/approve`, method: 'post', data });
}

export function rejectRegistration(id: number, reason: string) {
  return request<void>({ url: `/platform/registrations/${id}/reject`, method: 'post', data: { reason } });
}

/** 公开注册：验证码与提交都不需要登录。 */
export function fetchRegistrationCaptcha() {
  return request<{ captchaToken: string; image: string; expiresAt: string }>({
    url: '/public/registration/captcha'
  });
}

export function submitRegistration(data: {
  companyName: string;
  contactName: string;
  contactPhone: string;
  username: string;
  password: string;
  captchaToken: string;
  captchaCode: string;
}) {
  return request<void>({ url: '/public/registration', method: 'post', data });
}

export interface ConfigKeyDefinition {
  key: string;
  name: string;
  type: string;
  sensitive: boolean;
}

/** 当前会话租户的生效配置。任何已认证账号都可读——只读用户同样要看地图。 */
export function fetchEffectiveConfig() {
  return request<Record<string, string>>({ url: '/config/effective' });
}

export function fetchConfigKeys() {
  return request<ConfigKeyDefinition[]>({ url: '/config/keys' });
}

export function fetchConfigOverrides(tenantId?: number | null) {
  return request<Record<string, string>>({ url: '/config/overrides', params: { tenantId } });
}

export function saveConfigOverrides(values: Record<string, string>, tenantId?: number | null) {
  return request<void>({ url: '/config/overrides', method: 'put', params: { tenantId }, data: values });
}
