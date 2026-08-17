import { request } from '../request';

export interface AiConversation {
  id: number;
  title: string;
  createdAt: string;
  updatedAt: string;
}

export interface AiStoredMessage {
  role: 'user' | 'assistant';
  content: string;
  toolTrace?: string | null;
  createdAt: string;
}

export interface AiStatus {
  enabled: boolean;
  used: number;
  limit: number;
  month: string;
}

export function fetchAiStatus() {
  return request<AiStatus>({ url: '/ai/status' });
}

/** 当前账号最近的会话，最新在前。 */
export function fetchAiConversations() {
  return request<AiConversation[]>({ url: '/ai/conversations' });
}

export function fetchAiMessages(id: number) {
  return request<AiStoredMessage[]>({ url: `/ai/conversations/${id}` });
}

/** 清空某个会话的消息，会话保留。 */
export function clearAiMessages(id: number) {
  return request<null>({ url: `/ai/conversations/${id}/messages`, method: 'delete' });
}

/** 删除整个会话。 */
export function deleteAiConversation(id: number) {
  return request<null>({ url: `/ai/conversations/${id}`, method: 'delete' });
}
