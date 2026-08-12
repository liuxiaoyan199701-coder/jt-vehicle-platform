import type {
  EncodedVideoChunkInitLike,
  EncodedVideoChunkLike,
  VideoDecoderConfigLike,
  VideoDecoderInitLike,
  VideoDecoderLike,
  VideoFrameLike
} from '../src/decoder';
import type { RenderScheduler } from '../src/render';
import type {
  StreamOpenContext,
  StreamOpener,
  TimerScheduler,
  WebSocketCloseEventLike,
  WebSocketLike,
  WebSocketMessageEventLike
} from '../src/transport';
import type { OpenStreamRequest, StreamTicket } from '../src/types';

export class ManualScheduler implements TimerScheduler, RenderScheduler {
  readonly delays: number[] = [];
  private readonly tasks = new Map<number, () => void>();
  private nextId = 1;

  set(handler: () => void, delayMs: number): unknown {
    const id = this.nextId++;
    this.delays.push(delayMs);
    this.tasks.set(id, handler);
    return id;
  }

  clear(handle: unknown): void {
    this.tasks.delete(handle as number);
  }

  request(callback: (timestamp: number) => void): unknown {
    return this.set(() => callback(performance.now()), 16);
  }

  cancel(handle: unknown): void {
    this.clear(handle);
  }

  get size(): number {
    return this.tasks.size;
  }

  runNext(): boolean {
    const first = this.tasks.entries().next().value as [number, () => void] | undefined;
    if (!first) return false;
    this.tasks.delete(first[0]);
    first[1]();
    return true;
  }

  runAll(limit = 100): void {
    let count = 0;
    while (this.runNext()) {
      count += 1;
      if (count > limit) throw new Error('ManualScheduler runAll limit exceeded');
    }
  }
}

export class FakeWebSocket implements WebSocketLike {
  binaryType = '';
  readyState = 0;
  onopen: ((event?: unknown) => void) | null = null;
  onmessage: ((event: WebSocketMessageEventLike) => void) | null = null;
  onerror: ((event?: unknown) => void) | null = null;
  onclose: ((event: WebSocketCloseEventLike) => void) | null = null;
  readonly sent: Array<string | ArrayBuffer | ArrayBufferView> = [];
  readonly closeCalls: Array<{ code?: number; reason?: string }> = [];

  constructor(readonly url: string) {}

  open(): void {
    this.readyState = 1;
    this.onopen?.();
  }

  receive(data: unknown): void {
    this.onmessage?.({ data });
  }

  fail(): void {
    this.onerror?.();
  }

  serverClose(event: WebSocketCloseEventLike = { code: 1006, wasClean: false }): void {
    this.readyState = 3;
    this.onclose?.(event);
  }

  send(data: string | ArrayBuffer | ArrayBufferView): void {
    this.sent.push(data);
  }

  close(code?: number, reason?: string): void {
    this.readyState = 3;
    this.closeCalls.push({ ...(code !== undefined ? { code } : {}), ...(reason ? { reason } : {}) });
    this.onclose?.({
      ...(code !== undefined ? { code } : {}),
      ...(reason !== undefined ? { reason } : {}),
      wasClean: code === 1000
    });
  }
}

export class FakeOpener implements StreamOpener {
  readonly calls: Array<{ request: OpenStreamRequest; context?: StreamOpenContext }> = [];
  readonly results: Array<StreamTicket | Error> = [];

  constructor(...results: Array<StreamTicket | Error>) {
    this.results.push(...results);
  }

  async open(request: OpenStreamRequest, context?: StreamOpenContext): Promise<StreamTicket> {
    this.calls.push({ request, ...(context ? { context } : {}) });
    const result = this.results.shift();
    if (result instanceof Error) throw result;
    return result ?? { wsUrl: 'ws://node.example/ws', token: 'token', state: 'waking' };
  }
}

export class FakeVideoFrame implements VideoFrameLike {
  closed = false;

  constructor(
    readonly timestamp: number,
    readonly displayWidth = 1280,
    readonly displayHeight = 720
  ) {}

  close(): void {
    this.closed = true;
  }
}

export class FakeVideoDecoder implements VideoDecoderLike {
  state = 'unconfigured';
  decodeQueueSize = 0;
  readonly configs: VideoDecoderConfigLike[] = [];
  readonly chunks: EncodedVideoChunkLike[] = [];
  failNextDecode = false;

  constructor(
    readonly init: VideoDecoderInitLike,
    private readonly autoOutput = false
  ) {}

  configure(config: VideoDecoderConfigLike): void {
    this.configs.push(config);
    this.state = 'configured';
  }

  decode(chunk: EncodedVideoChunkLike): void {
    if (this.failNextDecode) {
      this.failNextDecode = false;
      throw new Error('bad frame');
    }
    this.chunks.push(chunk);
    if (this.autoOutput) this.init.output(new FakeVideoFrame(chunk.timestamp ?? 0));
  }

  close(): void {
    this.state = 'closed';
  }
}

export function chunkFactory(init: EncodedVideoChunkInitLike): EncodedVideoChunkLike & EncodedVideoChunkInitLike {
  return { ...init };
}

export function jt78Frame(dataType: number, payload: readonly number[], channel = 1): ArrayBuffer {
  return Uint8Array.of(
    0x4a, 0x54, 0x37, 0x38,
    dataType, channel, 0, 0,
    ...payload
  ).buffer;
}

export const H264_SPS = [0, 0, 0, 1, 0x67, 0x42, 0, 0x1e] as const;
export const H264_PPS = [0, 0, 0, 1, 0x68, 0xce, 0x06] as const;
export const H264_KEY = [0, 0, 0, 1, 0x65, 0x88, 0x84] as const;
export const H264_DELTA = [0, 0, 0, 1, 0x41, 0x01] as const;

export async function flushPromises(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
}

export async function waitForSocket(sockets: readonly FakeWebSocket[], index = 0): Promise<FakeWebSocket> {
  for (let attempt = 0; attempt < 20; attempt += 1) {
    const socket = sockets[index];
    if (socket) return socket;
    await flushPromises();
  }
  throw new Error(`WebSocket ${index} was not created`);
}
