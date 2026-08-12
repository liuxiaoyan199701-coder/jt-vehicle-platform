import { JTPlayerError, toJTPlayerError } from '../errors';
import type { OpenStreamRequest, StreamTicket } from '../types';

export interface StreamOpenContext {
  credential?: string;
  signal?: AbortSignal;
}

export interface StreamOpener {
  open(request: OpenStreamRequest, context?: StreamOpenContext): Promise<StreamTicket>;
}

export interface FetchResponseLike {
  readonly ok: boolean;
  readonly status: number;
  readonly statusText: string;
  json(): Promise<unknown>;
}

export type FetchLike = (
  input: string | URL,
  init?: RequestInit
) => Promise<FetchResponseLike>;

export interface HttpStreamOpenerOptions {
  url: string;
  fetch?: FetchLike;
  headers?: Readonly<Record<string, string>>;
}

export class HttpStreamOpener implements StreamOpener {
  readonly url: string;
  private readonly fetchImpl: FetchLike;
  private readonly headers: Readonly<Record<string, string>>;

  constructor(options: HttpStreamOpenerOptions) {
    this.url = options.url;
    const fetchImpl = options.fetch ?? globalThis.fetch;
    if (!fetchImpl) {
      throw new JTPlayerError('Fetch API is unavailable', {
        code: 'OPEN_STREAM_FAILED',
        fatal: true
      });
    }
    this.fetchImpl = fetchImpl.bind(globalThis) as FetchLike;
    this.headers = options.headers ?? {};
  }

  async open(request: OpenStreamRequest, context: StreamOpenContext = {}): Promise<StreamTicket> {
    const headers: Record<string, string> = {
      'content-type': 'application/json',
      ...this.headers
    };
    if (context.credential) {
      headers.authorization = context.credential.startsWith('Bearer ')
        ? context.credential
        : `Bearer ${context.credential}`;
    }

    let response: FetchResponseLike;
    try {
      response = await this.fetchImpl(this.url, {
        method: 'POST',
        headers,
        body: JSON.stringify(request),
        redirect: 'error',
        ...(context.signal ? { signal: context.signal } : {})
      });
    } catch (error) {
      throw toJTPlayerError(error, 'OPEN_STREAM_FAILED', 'Open-stream request failed', {
        retryable: true
      });
    }

    const body = await readJson(response);
    const data = unwrapData(body);
    if (!response.ok) {
      throw openError(response.status, data, response.statusText);
    }
    return parseTicket(data);
  }
}

export function parseTicket(value: unknown): StreamTicket {
  if (!isRecord(value)) {
    throw new JTPlayerError('Open-stream response is not an object', {
      code: 'INVALID_OPEN_RESPONSE',
      fatal: true
    });
  }

  const wsUrl = stringValue(value.wsUrl) || stringValue(value.websocketUrl);
  if (!/^wss?:\/\//i.test(wsUrl)) {
    throw new JTPlayerError('Open-stream response must contain an absolute wsUrl', {
      code: 'INVALID_OPEN_RESPONSE',
      fatal: true
    });
  }

  const rawState = stringValue(value.state).toLowerCase();
  if (rawState === 'failed' || rawState === 'dead') {
    throw serverFailure(value);
  }
  const state = rawState === 'live' || rawState === 'started' ? 'live' : 'waking';
  const token = stringValue(value.token);
  const streamId = stringValue(value.streamId);

  return {
    wsUrl,
    state,
    ...(token ? { token } : {}),
    ...(streamId ? { streamId } : {})
  };
}

function openError(status: number, data: unknown, statusText: string): JTPlayerError {
  const record = isRecord(data) ? data : {};
  const serverCode = stringValue(record.code);
  const message = stringValue(record.message) || statusText || `Open-stream request failed (${status})`;
  const auth = status === 401 || status === 403 || /AUTH|TOKEN|UNAUTHORIZED|FORBIDDEN/i.test(serverCode);
  const resource = /RESOURCE|CAPACITY|UNAVAILABLE/i.test(serverCode);
  return new JTPlayerError(message, {
    code: auth ? 'AUTH_FAILED' : resource ? 'RESOURCE_UNAVAILABLE' : serverCode || 'OPEN_STREAM_FAILED',
    fatal: auth || resource,
    retryable: status >= 500
  });
}

function serverFailure(record: Record<string, unknown>): JTPlayerError {
  const code = stringValue(record.code) || 'OPEN_STREAM_FAILED';
  return new JTPlayerError(stringValue(record.message) || code, {
    code: /RESOURCE|CAPACITY|UNAVAILABLE/i.test(code) ? 'RESOURCE_UNAVAILABLE' : code,
    fatal: true
  });
}

async function readJson(response: FetchResponseLike): Promise<unknown> {
  try {
    return await response.json();
  } catch (error) {
    if (!response.ok) return {};
    throw new JTPlayerError('Open-stream response is not valid JSON', {
      code: 'INVALID_OPEN_RESPONSE',
      fatal: true,
      cause: error
    });
  }
}

function unwrapData(value: unknown): unknown {
  if (isRecord(value) && isRecord(value.data)) return value.data;
  return value;
}

function stringValue(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
