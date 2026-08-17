import { localStg } from '@/utils/storage';

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

export interface AiUsageEvent {
  promptTokens: number;
  completionTokens: number;
  monthlyUsed: number;
  monthlyLimit: number;
}

export interface AiStreamHandlers {
  onDelta?: (text: string) => void;
  onTool?: (event: AiToolEvent) => void;
  onAction?: (event: AiActionEvent) => void;
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
  handlers: AiStreamHandlers
): () => void {
  const controller = new AbortController();
  const baseURL = import.meta.env.VITE_SERVICE_BASE_URL ?? '';

  void (async () => {
    try {
      const response = await fetch(`${baseURL}/api/ai/chat`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'text/event-stream',
          Authorization: `Bearer ${localStg.get('token') ?? ''}`
        },
        body: JSON.stringify({ messages }),
        signal: controller.signal
      });

      if (!response.ok || !response.body) {
        handlers.onError?.(
          response.status === 401 ? 'AI_UNAUTHORIZED' : 'AI_HTTP_ERROR',
          response.status === 401 ? '登录已过期，请重新登录' : '无法连接 AI 服务'
        );
        return;
      }

      await readEvents(response.body, handlers);
    } catch (error) {
      // 用户主动中止不是错误，不该弹提示。
      if (controller.signal.aborted) return;
      handlers.onError?.('AI_NETWORK_ERROR', '网络中断，请重试');
    }
  })();

  return () => controller.abort();
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
    case 'delta':
      handlers.onDelta?.(payload.text ?? '');
      break;
    case 'tool':
      handlers.onTool?.(payload as AiToolEvent);
      break;
    case 'action':
      handlers.onAction?.(payload as AiActionEvent);
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
