import { localStg } from '@/utils/storage';
import { getServiceBaseURL } from '@/utils/service';
import { fetchRefreshToken } from '@/service/api/auth';

/**
 * AI 对话的流式读取。
 *
 * 不走 `@sa/axios`：那层封装会把响应体整个读完再交出来，流式就没了意义。
 * 也不用原生 `EventSource`——它不支持自定义请求头，带不了 Authorization，
 * 而对话接口必须鉴权。所以只能用 fetch 拿 ReadableStream 自己分帧。
 */

export interface AiToolEvent {
  phase: 'start' | 'end';
  name: string;
  brief: string;
  ok?: boolean;
}

export interface AiActionEvent {
  proposalId: string;
  type: string;
  label: string;
  title: string;
  reason?: string;
  params: Record<string, unknown>;
  requiredPermission: string;
  requiresConfirmation: boolean;
}

/**
 * 一条视图提议。
 *
 * <p>只描述「看什么」——**永远不含接口地址与渲染配置**。前端按自己持有的白名单决定调哪个接口、
 * 怎么画；模型控制看什么，不控制怎么取、怎么画。
 */
export interface AiViewEvent {
  viewId: string;
  type: string;
  label: string;
  title: string;
  /** inline 直接渲染在气泡里；reference_card 先给一张卡、用户点了才加载。 */
  presentation: 'inline' | 'reference_card';
  params: Record<string, unknown>;
  requiredPermission: string;
}

export interface AiUsageEvent {
  promptTokens: number;
  completionTokens: number;
  monthlyUsed: number;
  monthlyLimit: number;
}

export interface AiMetaEvent {
  conversationId: number | null;
  model: string;
}

export interface AiStreamHandlers {
  onMeta?: (event: AiMetaEvent) => void;
  onDelta?: (text: string) => void;
  onTool?: (event: AiToolEvent) => void;
  onAction?: (event: AiActionEvent) => void;
  onView?: (event: AiViewEvent) => void;
  onUsage?: (event: AiUsageEvent) => void;
  onDone?: () => void;
  onError?: (code: string, message: string) => void;
}

export interface AiChatMessage {
  role: 'user' | 'assistant';
  content: string;
}

/**
 * 发起一次对话并持续读取事件流。
 *
 * @returns 用于中止的函数。组件卸载或用户点停止时必须调用，否则请求会一直挂着占着后端线程。
 */
export function streamChat(
  messages: AiChatMessage[],
  handlers: AiStreamHandlers,
  conversationId?: number | null
): () => void {
  const controller = new AbortController();
  // 用与 axios 实例同一个 baseURL 解析：VITE_SERVICE_BASE_URL 生产环境是 "/api"，
  // 本身已含前缀。这里再拼一次 /api 会打到 /api/api/ai/chat 直接 404。
  // 开发模式还要走代理前缀，getServiceBaseURL 一并处理了。
  const isHttpProxy = import.meta.env.DEV && import.meta.env.VITE_HTTP_PROXY === 'Y';
  const { baseURL } = getServiceBaseURL(import.meta.env, isHttpProxy);

  void (async () => {
    try {
      let response = await post(baseURL, messages, controller.signal, conversationId);

      // 令牌过期时先续期再重试一次。走裸 fetch 就绕开了 axios 那套自动刷新，
      // 不补上的话会出现「别的页面都好好的，唯独 AI 说你登录过期了」。
      if (response.status === 401 && (await refreshAccessToken())) {
        response = await post(baseURL, messages, controller.signal, conversationId);
      }

      if (!response.ok || !response.body) {
        // 把状态码分开报：一句笼统的「无法连接」会让人去查网络和后端进程，
        // 而 404 其实是地址拼错了、403 是权限没配到，方向完全不同。
        const [code, message] = describeFailure(response.status);
        handlers.onError?.(code, message);
        return;
      }

      await readEvents(response.body, handlers);
    } catch {
      // 用户主动中止不是错误，不该弹提示。
      if (controller.signal.aborted) return;
      handlers.onError?.('AI_NETWORK_ERROR', '网络中断，请重试');
    }
  })();

  return () => controller.abort();
}

function post(
  baseURL: string,
  messages: AiChatMessage[],
  signal: AbortSignal,
  conversationId?: number | null
) {
  return fetch(`${baseURL}/ai/chat`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      Authorization: `Bearer ${localStg.get('token') ?? ''}`
    },
    body: JSON.stringify({ conversationId: conversationId ?? null, messages }),
    signal
  });
}

/** 用刷新令牌换一张新的访问令牌，成功后写回本地存储。失败即认定确实需要重新登录。 */
async function refreshAccessToken(): Promise<boolean> {
  const refreshToken = localStg.get('refreshToken');
  if (!refreshToken) return false;
  try {
    const { data } = await fetchRefreshToken(refreshToken);
    if (!data?.token) return false;
    localStg.set('token', data.token);
    localStg.set('refreshToken', data.refreshToken);
    return true;
  } catch {
    return false;
  }
}

function describeFailure(status: number): [string, string] {
  if (status === 401) return ['AI_UNAUTHORIZED', '登录已过期，请重新登录'];
  if (status === 403) return ['AI_FORBIDDEN', '当前账号没有使用 AI 助手的权限'];
  if (status === 404) return ['AI_NOT_FOUND', 'AI 接口不存在（404），可能是前后端版本不一致'];
  if (status >= 500) return ['AI_SERVER_ERROR', `AI 服务异常（${status}）`];
  return ['AI_HTTP_ERROR', `无法连接 AI 服务（HTTP ${status}）`];
}

async function readEvents(body: ReadableStream<Uint8Array>, handlers: AiStreamHandlers) {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  for (;;) {
    // eslint-disable-next-line no-await-in-loop
    const { done, value } = await reader.read();
    if (done) break;

    // stream: true 是必须的——一个中文字符可能跨两个数据块，不带它会解出乱码。
    buffer += decoder.decode(value, { stream: true });

    let boundary = buffer.indexOf('\n\n');
    while (boundary >= 0) {
      dispatch(buffer.slice(0, boundary), handlers);
      buffer = buffer.slice(boundary + 2);
      boundary = buffer.indexOf('\n\n');
    }
  }
}

function dispatch(frame: string, handlers: AiStreamHandlers) {
  let eventName = 'message';
  let data = '';

  for (const line of frame.split('\n')) {
    if (line.startsWith('event:')) {
      eventName = line.slice(6).trim();
    } else if (line.startsWith('data:')) {
      // 同一帧的多行 data 要拼起来，这是 SSE 规范里的分片方式。
      data += line.slice(5).trim();
    }
  }
  if (!data) return;

  let payload: any;
  try {
    payload = JSON.parse(data);
  } catch {
    return;
  }

  switch (eventName) {
    case 'meta':
      handlers.onMeta?.(payload as AiMetaEvent);
      break;
    case 'delta':
      handlers.onDelta?.(payload.text ?? '');
      break;
    case 'tool':
      handlers.onTool?.(payload as AiToolEvent);
      break;
    case 'action':
      handlers.onAction?.(payload as AiActionEvent);
      break;
    case 'view':
      handlers.onView?.(payload as AiViewEvent);
      break;
    case 'usage':
      handlers.onUsage?.(payload as AiUsageEvent);
      break;
    case 'done':
      handlers.onDone?.();
      break;
    case 'error':
      handlers.onError?.(payload.code ?? 'AI_ERROR', payload.message ?? 'AI 服务异常');
      break;
    default:
      break;
  }
}
