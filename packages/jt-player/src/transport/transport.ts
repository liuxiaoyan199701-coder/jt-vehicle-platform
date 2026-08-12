import { JTPlayerError, toJTPlayerError } from '../errors';
import { TypedEventEmitter } from '../events';
import { parseServerMessage, type ServerMessage } from '../protocol/status';
import type { OpenStreamRequest, StreamTicket, SubscriptionState } from '../types';
import { HttpStreamOpener, type FetchLike, type StreamOpenContext, type StreamOpener } from './opener';

export type TransportSendData = string | ArrayBuffer | ArrayBufferView;
export type TransportState = 'idle' | 'opening' | 'connecting' | 'connected' | 'reconnecting' | 'closed' | 'destroyed';

export interface WebSocketMessageEventLike {
  readonly data: unknown;
}

export interface WebSocketCloseEventLike {
  readonly code?: number;
  readonly reason?: string;
  readonly wasClean?: boolean;
}

export interface WebSocketLike {
  binaryType: string;
  readonly readyState: number;
  onopen: ((event?: unknown) => void) | null;
  onmessage: ((event: WebSocketMessageEventLike) => void) | null;
  onerror: ((event?: unknown) => void) | null;
  onclose: ((event: WebSocketCloseEventLike) => void) | null;
  send(data: TransportSendData): void;
  close(code?: number, reason?: string): void;
}

export type WebSocketFactory = (url: string) => WebSocketLike;
export type CredentialProvider = () => string | undefined | Promise<string | undefined>;

export interface TimerScheduler {
  set(handler: () => void, delayMs: number): unknown;
  clear(handle: unknown): void;
}

export interface ReconnectOptions {
  maxAttempts: number;
  initialDelayMs: number;
  maxDelayMs: number;
  multiplier: number;
  jitter: number;
}

export interface DirectStreamTransportOptions {
  openStreamUrl?: string;
  opener?: StreamOpener;
  fetch?: FetchLike;
  webSocketFactory?: WebSocketFactory;
  credential?: string | CredentialProvider;
  tokenQueryParam?: string;
  reconnect?: Partial<ReconnectOptions>;
  sendQueueLimit?: number;
  connectionTimeoutMs?: number;
  scheduler?: TimerScheduler;
  random?: () => number;
}

export interface TransportStateEvent {
  state: TransportState;
  attempt: number;
}

export interface SubscriptionEvent {
  state: SubscriptionState;
  code?: string;
  message?: string;
}

export interface TransportEvents {
  state: TransportStateEvent;
  subscription: SubscriptionEvent;
  binary: ArrayBuffer;
  text: ServerMessage;
  error: JTPlayerError;
}

const DEFAULT_RECONNECT: ReconnectOptions = {
  maxAttempts: 5,
  initialDelayMs: 500,
  maxDelayMs: 10_000,
  multiplier: 2,
  jitter: 0.2
};

const OPEN = 1;
const NORMAL_CLOSE = 1000;

export class DirectStreamTransport extends TypedEventEmitter<TransportEvents> {
  private readonly opener: StreamOpener;
  private readonly webSocketFactory: WebSocketFactory;
  private readonly credential: string | CredentialProvider | undefined;
  private readonly tokenQueryParam: string;
  private readonly reconnect: ReconnectOptions;
  private readonly sendQueueLimit: number;
  private readonly connectionTimeoutMs: number;
  private readonly scheduler: TimerScheduler;
  private readonly random: () => number;
  private readonly sendQueue: TransportSendData[] = [];

  private socket: WebSocketLike | null = null;
  private lastRequest: OpenStreamRequest | null = null;
  private lastCredential: string | undefined;
  private reconnectTimer: unknown = null;
  private connectionTimer: unknown = null;
  private pendingConnectionCancel: (() => void) | null = null;
  private reconnectAttempt = 0;
  private generation = 0;
  private manuallyStopped = true;
  private retrySuppressed = false;
  private destroyed = false;
  private currentState: TransportState = 'idle';

  constructor(options: DirectStreamTransportOptions) {
    super();
    if (options.opener) {
      this.opener = options.opener;
    } else if (options.openStreamUrl) {
      this.opener = new HttpStreamOpener({
        url: options.openStreamUrl,
        ...(options.fetch ? { fetch: options.fetch } : {})
      });
    } else {
      throw new JTPlayerError('openStreamUrl or opener is required', {
        code: 'INVALID_STATE',
        fatal: true
      });
    }
    this.webSocketFactory = options.webSocketFactory ?? defaultWebSocketFactory;
    this.credential = options.credential;
    this.tokenQueryParam = options.tokenQueryParam ?? 'token';
    this.reconnect = { ...DEFAULT_RECONNECT, ...options.reconnect };
    this.sendQueueLimit = options.sendQueueLimit ?? 128;
    this.connectionTimeoutMs = options.connectionTimeoutMs ?? 10_000;
    this.scheduler = options.scheduler ?? defaultScheduler;
    this.random = options.random ?? Math.random;
  }

  get state(): TransportState {
    return this.currentState;
  }

  get queuedMessages(): number {
    return this.sendQueue.length;
  }

  /** @internal Creates an independent connection with the same endpoint and authentication configuration. */
  createSibling(): DirectStreamTransport {
    this.ensureActive();
    return new DirectStreamTransport({
      opener: this.opener,
      webSocketFactory: this.webSocketFactory,
      ...(this.credential ? { credential: this.credential } : {}),
      tokenQueryParam: this.tokenQueryParam,
      reconnect: { ...this.reconnect },
      sendQueueLimit: this.sendQueueLimit,
      connectionTimeoutMs: this.connectionTimeoutMs,
      scheduler: this.scheduler,
      random: this.random
    });
  }

  async connect(request: OpenStreamRequest, context: StreamOpenContext = {}): Promise<StreamTicket> {
    this.ensureActive();
    this.resetConnection();
    this.manuallyStopped = false;
    this.retrySuppressed = false;
    this.reconnectAttempt = 0;
    this.lastRequest = { ...request };
    this.lastCredential = context.credential;
    const generation = ++this.generation;

    try {
      const ticket = await this.openTicket(request, generation, context.credential);
      await this.openSocket(ticket, generation);
      this.emitTicketState(ticket);
      return ticket;
    } catch (error) {
      if (generation !== this.generation) {
        throw new JTPlayerError('Connection attempt was superseded', { code: 'INVALID_STATE' });
      }
      const playerError = toJTPlayerError(error, 'OPEN_STREAM_FAILED', 'Unable to open stream');
      this.emit('error', playerError);
      this.setState('closed');
      throw playerError;
    }
  }

  send(data: TransportSendData): void {
    this.ensureActive();
    if (this.socket?.readyState === OPEN) {
      this.socket.send(data);
      return;
    }

    if (this.sendQueue.length >= this.sendQueueLimit) {
      this.sendQueue.shift();
      this.emit('error', new JTPlayerError('Transport send queue overflowed; oldest item was dropped', {
        code: 'SEND_QUEUE_OVERFLOW'
      }));
    }
    this.sendQueue.push(data);
  }

  stop(): void {
    if (this.destroyed) return;
    this.manuallyStopped = true;
    this.retrySuppressed = true;
    this.lastRequest = null;
    this.lastCredential = undefined;
    this.generation += 1;
    this.resetConnection();
    this.sendQueue.length = 0;
    this.setState('closed');
  }

  destroy(): void {
    if (this.destroyed) return;
    this.stop();
    this.destroyed = true;
    this.setState('destroyed');
    this.clearListeners();
  }

  private async openTicket(
    request: OpenStreamRequest,
    generation: number,
    credentialOverride?: string
  ): Promise<StreamTicket> {
    this.setState(this.reconnectAttempt > 0 ? 'reconnecting' : 'opening');
    const credential = credentialOverride ?? await this.resolveCredential();
    if (generation !== this.generation) {
      throw new JTPlayerError('Connection attempt was cancelled', { code: 'INVALID_STATE' });
    }
    const ticket = await this.opener.open(request, credential ? { credential } : {});
    if (generation !== this.generation) {
      throw new JTPlayerError('Connection attempt was cancelled', { code: 'INVALID_STATE' });
    }
    return ticket;
  }

  private openSocket(ticket: StreamTicket, generation: number): Promise<void> {
    return new Promise((resolve, reject) => {
      let opened = false;
      let settled = false;
      const url = withToken(ticket.wsUrl, ticket.token, this.tokenQueryParam);
      let socket: WebSocketLike;
      try {
        socket = this.webSocketFactory(url);
      } catch (error) {
        reject(toJTPlayerError(error, 'WEBSOCKET_FAILED', 'WebSocket construction failed', { retryable: true }));
        return;
      }

      this.socket = socket;
      socket.binaryType = 'arraybuffer';
      let cancelConnection: (() => void) | null = null;
      const clearCancellation = () => {
        if (cancelConnection && this.pendingConnectionCancel === cancelConnection) {
          this.pendingConnectionCancel = null;
        }
      };
      const rejectOnce = (error: JTPlayerError) => {
        if (settled) return;
        settled = true;
        clearCancellation();
        reject(error);
      };
      cancelConnection = () => rejectOnce(new JTPlayerError('WebSocket connection was cancelled', {
        code: 'INVALID_STATE'
      }));
      this.pendingConnectionCancel = cancelConnection;
      this.setState(this.reconnectAttempt > 0 ? 'reconnecting' : 'connecting');
      this.clearConnectionTimer();
      this.connectionTimer = this.scheduler.set(() => {
        if (opened || generation !== this.generation) return;
        if (this.socket === socket) this.socket = null;
        this.detachSocket(socket);
        try { socket.close(4000, 'Connection timeout'); } catch { /* ignored */ }
        rejectOnce(new JTPlayerError('WebSocket connection timed out', {
          code: 'WEBSOCKET_FAILED',
          retryable: true
        }));
      }, this.connectionTimeoutMs);

      socket.onopen = () => {
        if (generation !== this.generation) {
          socket.close(NORMAL_CLOSE, 'Superseded');
          return;
        }
        opened = true;
        this.clearConnectionTimer();
        this.reconnectAttempt = 0;
        this.setState('connected');
        this.flushQueue();
        if (!settled) {
          settled = true;
          clearCancellation();
          resolve();
        }
      };

      socket.onmessage = (event) => {
        void this.handleMessage(event.data, generation);
      };

      socket.onerror = () => {
        if (!opened && !settled) {
          this.clearConnectionTimer();
          if (this.socket === socket) this.socket = null;
          this.detachSocket(socket);
          try { socket.close(4000, 'Connection failed'); } catch { /* ignored */ }
          rejectOnce(new JTPlayerError('WebSocket connection failed', {
            code: 'WEBSOCKET_FAILED',
            retryable: true
          }));
        }
      };

      socket.onclose = (event) => {
        this.clearConnectionTimer();
        if (this.socket === socket) this.socket = null;
        if (!opened && !settled) {
          rejectOnce(closeError(event));
          return;
        }
        if (opened && generation === this.generation && !this.manuallyStopped) {
          if (isAuthClose(event)) {
            this.retrySuppressed = true;
            this.emit('error', new JTPlayerError(event.reason || 'WebSocket authentication failed', {
              code: 'AUTH_FAILED',
              fatal: true
            }));
            this.setState('closed');
          } else if (!this.retrySuppressed) {
            this.scheduleReconnect();
          }
        }
      };
    });
  }

  private async handleMessage(data: unknown, generation: number): Promise<void> {
    if (generation !== this.generation) return;
    if (typeof data === 'string') {
      let message: ServerMessage;
      try {
        message = parseServerMessage(data);
      } catch {
        message = { type: 'other', raw: { text: data } };
      }
      this.emit('text', message);
      this.handleServerMessage(message);
      return;
    }

    const arrayBuffer = await toArrayBuffer(data);
    if (arrayBuffer && generation === this.generation) {
      this.emit('binary', arrayBuffer);
    }
  }

  private handleServerMessage(message: ServerMessage): void {
    if (message.type === 'state') {
      const event: SubscriptionEvent = { state: message.state };
      if (message.code) event.code = message.code;
      if (message.message) event.message = message.message;
      this.emit('subscription', event);
      if (message.state === 'failed') {
        this.failSession(message.code || 'DEVICE_NO_RESPONSE', message.message || 'Subscription failed');
      }
      return;
    }
    if (message.type === 'error') {
      this.failSession(message.code, message.message);
    }
  }

  private failSession(code: string, message: string): void {
    this.retrySuppressed = true;
    const normalized = /DEVICE_NO_RESPONSE/i.test(code) ? 'DEVICE_NO_RESPONSE'
      : /AUTH|TOKEN|UNAUTHORIZED|FORBIDDEN/i.test(code) ? 'AUTH_FAILED'
      : code;
    this.emit('error', new JTPlayerError(message, { code: normalized, fatal: true }));
    const socket = this.socket;
    if (socket) {
      try { socket.close(1008, message.slice(0, 120)); } catch { /* ignored */ }
    }
    this.setState('closed');
  }

  private scheduleReconnect(): void {
    if (this.destroyed || this.manuallyStopped || this.retrySuppressed || !this.lastRequest) return;
    if (this.reconnectAttempt >= this.reconnect.maxAttempts) {
      this.retrySuppressed = true;
      this.setState('closed');
      this.emit('error', new JTPlayerError('WebSocket reconnect attempts exhausted', {
        code: 'RECONNECT_EXHAUSTED',
        fatal: true
      }));
      return;
    }

    this.reconnectAttempt += 1;
    this.setState('reconnecting');
    const attempt = this.reconnectAttempt;
    const base = Math.min(
      this.reconnect.maxDelayMs,
      this.reconnect.initialDelayMs * this.reconnect.multiplier ** (attempt - 1)
    );
    const spread = base * this.reconnect.jitter;
    const delay = Math.max(0, base - spread + this.random() * spread * 2);
    const generation = this.generation;
    this.clearReconnectTimer();
    this.reconnectTimer = this.scheduler.set(() => {
      this.reconnectTimer = null;
      void this.runReconnect(generation);
    }, delay);
  }

  private async runReconnect(generation: number): Promise<void> {
    const request = this.lastRequest;
    if (!request || generation !== this.generation || this.manuallyStopped) return;
    try {
      const ticket = await this.openTicket(request, generation, this.lastCredential);
      await this.openSocket(ticket, generation);
      this.emitTicketState(ticket);
    } catch (error) {
      if (generation !== this.generation || this.manuallyStopped) return;
      const playerError = toJTPlayerError(error, 'WEBSOCKET_FAILED', 'Reconnect failed', {
        retryable: true
      });
      if (playerError.fatal && !playerError.retryable) {
        this.retrySuppressed = true;
        this.setState('closed');
        this.emit('error', playerError);
        return;
      }
      this.scheduleReconnect();
    }
  }

  private emitTicketState(ticket: StreamTicket): void {
    this.emit('subscription', { state: ticket.state });
  }

  private async resolveCredential(): Promise<string | undefined> {
    return typeof this.credential === 'function' ? this.credential() : this.credential;
  }

  private flushQueue(): void {
    const socket = this.socket;
    if (!socket || socket.readyState !== OPEN) return;
    while (this.sendQueue.length > 0 && socket.readyState === OPEN) {
      const item = this.sendQueue.shift();
      if (item !== undefined) socket.send(item);
    }
  }

  private resetConnection(): void {
    this.clearReconnectTimer();
    this.clearConnectionTimer();
    const cancelConnection = this.pendingConnectionCancel;
    this.pendingConnectionCancel = null;
    cancelConnection?.();
    const socket = this.socket;
    this.socket = null;
    if (socket) {
      this.detachSocket(socket);
      try { socket.close(NORMAL_CLOSE, 'Client stopped'); } catch { /* ignored */ }
    }
  }

  private detachSocket(socket: WebSocketLike): void {
    socket.onopen = null;
    socket.onmessage = null;
    socket.onerror = null;
    socket.onclose = null;
  }

  private clearReconnectTimer(): void {
    if (this.reconnectTimer !== null) {
      this.scheduler.clear(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }

  private clearConnectionTimer(): void {
    if (this.connectionTimer !== null) {
      this.scheduler.clear(this.connectionTimer);
      this.connectionTimer = null;
    }
  }

  private setState(state: TransportState): void {
    if (state === this.currentState) return;
    this.currentState = state;
    this.emit('state', { state, attempt: this.reconnectAttempt });
  }

  private ensureActive(): void {
    if (this.destroyed) {
      throw new JTPlayerError('Transport has been destroyed', { code: 'DESTROYED', fatal: true });
    }
  }
}

export function withToken(wsUrl: string, token: string | undefined, queryParam = 'token'): string {
  if (!token) return wsUrl;
  const url = new URL(wsUrl);
  url.searchParams.set(queryParam, token);
  return url.toString();
}

function closeError(event: WebSocketCloseEventLike): JTPlayerError {
  if (isAuthClose(event)) {
    return new JTPlayerError(event.reason || 'WebSocket authentication failed', {
      code: 'AUTH_FAILED',
      fatal: true
    });
  }
  return new JTPlayerError(event.reason || 'WebSocket closed before opening', {
    code: 'WEBSOCKET_FAILED',
    retryable: true
  });
}

function isAuthClose(event: WebSocketCloseEventLike): boolean {
  const code = event.code ?? 0;
  return code === 1008 || code === 4001 || code === 4003 || /AUTH|TOKEN|UNAUTHORIZED|FORBIDDEN/i.test(event.reason ?? '');
}

async function toArrayBuffer(value: unknown): Promise<ArrayBuffer | null> {
  if (value instanceof ArrayBuffer) return value;
  if (ArrayBuffer.isView(value)) {
    return value.buffer.slice(value.byteOffset, value.byteOffset + value.byteLength) as ArrayBuffer;
  }
  if (typeof Blob !== 'undefined' && value instanceof Blob) return value.arrayBuffer();
  return null;
}

function defaultWebSocketFactory(url: string): WebSocketLike {
  if (typeof WebSocket === 'undefined') {
    throw new JTPlayerError('WebSocket API is unavailable', {
      code: 'WEBSOCKET_FAILED',
      fatal: true
    });
  }
  return new WebSocket(url) as unknown as WebSocketLike;
}

const defaultScheduler: TimerScheduler = {
  set: (handler, delayMs) => globalThis.setTimeout(handler, delayMs),
  clear: (handle) => globalThis.clearTimeout(handle as number)
};
