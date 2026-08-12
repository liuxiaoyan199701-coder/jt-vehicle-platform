import { describe, expect, it, vi } from 'vitest';
import { JTPlayerError } from '../src/errors';
import {
  DirectStreamTransport,
  HttpStreamOpener,
  type FetchLike
} from '../src/transport';
import { FakeOpener, FakeWebSocket, flushPromises, ManualScheduler } from './helpers';

const request = { deviceId: '013800138000', channel: 1, streamKind: 'main' as const };

describe('HttpStreamOpener', () => {
  it('posts the stream key with credentials and rejects HTTP redirects', async () => {
    const fetcher = vi.fn<FetchLike>(async () => ({
      ok: true,
      status: 200,
      statusText: 'OK',
      json: async () => ({ wsUrl: 'ws://media-2.example:7825/ws', token: 'once', state: 'waking' })
    }));
    const opener = new HttpStreamOpener({ url: 'https://api.example/stream/open', fetch: fetcher });

    await expect(opener.open(request, { credential: 'jwt' })).resolves.toEqual({
      wsUrl: 'ws://media-2.example:7825/ws', token: 'once', state: 'waking'
    });
    expect(fetcher).toHaveBeenCalledOnce();
    const [, init] = fetcher.mock.calls[0] ?? [];
    expect(init?.redirect).toBe('error');
    expect(init?.headers).toMatchObject({ authorization: 'Bearer jwt' });
    expect(JSON.parse(String(init?.body))).toEqual(request);
  });

  it('turns authorization and capacity failures into stable error codes', async () => {
    const unauthorized = new HttpStreamOpener({
      url: 'https://api.example/stream/open',
      fetch: async () => ({
        ok: false, status: 401, statusText: 'Unauthorized', json: async () => ({ message: 'expired' })
      })
    });
    await expect(unauthorized.open(request)).rejects.toMatchObject({ code: 'AUTH_FAILED', message: 'expired' });

    const capacity = new HttpStreamOpener({
      url: 'https://api.example/stream/open',
      fetch: async () => ({
        ok: false, status: 503, statusText: 'Unavailable',
        json: async () => ({ code: 'MEDIA_CAPACITY_EXHAUSTED', message: 'full' })
      })
    });
    await expect(capacity.open(request)).rejects.toMatchObject({ code: 'RESOURCE_UNAVAILABLE' });
  });
});

describe('DirectStreamTransport', () => {
  it('encapsulates open + direct connect and flushes its send queue', async () => {
    const opener = new FakeOpener({
      wsUrl: 'ws://media-2.example:7825/ws?stream=x', token: 'a b', state: 'waking'
    });
    const sockets: FakeWebSocket[] = [];
    const transport = new DirectStreamTransport({
      opener,
      webSocketFactory: (url) => {
        const socket = new FakeWebSocket(url);
        sockets.push(socket);
        return socket;
      }
    });

    transport.send('queued');
    const connected = transport.connect(request, { credential: 'jwt' });
    await flushPromises();
    expect(sockets).toHaveLength(1);
    expect(sockets[0]?.url).toBe('ws://media-2.example:7825/ws?stream=x&token=a+b');
    sockets[0]?.open();
    await connected;

    expect(opener.calls[0]?.context).toEqual({ credential: 'jwt' });
    expect(sockets[0]?.sent).toEqual(['queued']);
    transport.destroy();
  });

  it('does not construct a WebSocket when opening the stream fails', async () => {
    const factory = vi.fn(() => new FakeWebSocket('ws://unused'));
    const transport = new DirectStreamTransport({
      opener: new FakeOpener(new JTPlayerError('forbidden', { code: 'AUTH_FAILED', fatal: true })),
      webSocketFactory: factory
    });

    await expect(transport.connect(request)).rejects.toMatchObject({ code: 'AUTH_FAILED' });
    expect(factory).not.toHaveBeenCalled();
    transport.destroy();
  });

  it('does not construct a WebSocket when stopped before opening returns', async () => {
    let resolveOpen!: (ticket: { wsUrl: string; state: 'waking' }) => void;
    const pendingTicket = new Promise<{ wsUrl: string; state: 'waking' }>((resolve) => {
      resolveOpen = resolve;
    });
    const factory = vi.fn(() => new FakeWebSocket('ws://unused'));
    const transport = new DirectStreamTransport({
      opener: { open: async () => pendingTicket },
      webSocketFactory: factory
    });

    const connecting = transport.connect(request);
    await flushPromises();
    transport.stop();
    resolveOpen({ wsUrl: 'ws://media/ws', state: 'waking' });

    await expect(connecting).rejects.toMatchObject({ code: 'INVALID_STATE' });
    expect(factory).not.toHaveBeenCalled();
    expect(transport.state).toBe('closed');
    transport.destroy();
  });

  it('exposes waking/live status and does not retry a device failure', async () => {
    const scheduler = new ManualScheduler();
    const socket = new FakeWebSocket('ws://media/ws');
    const transport = new DirectStreamTransport({
      opener: new FakeOpener({ wsUrl: socket.url, state: 'waking' }),
      webSocketFactory: () => socket,
      scheduler
    });
    const states: string[] = [];
    const errors: string[] = [];
    transport.on('subscription', (event) => states.push(event.state));
    transport.on('error', (error) => errors.push(error.code));

    const connected = transport.connect(request);
    await flushPromises();
    socket.open();
    await connected;
    socket.receive('{"type":"state","state":"live"}');
    socket.receive('{"type":"error","code":"DEVICE_NO_RESPONSE","message":"timeout"}');

    expect(states).toEqual(['waking', 'live']);
    expect(errors).toContain('DEVICE_NO_RESPONSE');
    expect(scheduler.size).toBe(0);
    expect(socket.closeCalls.at(-1)?.code).toBe(1008);
    transport.destroy();
  });

  it('re-opens the stream after an abnormal close using exponential backoff', async () => {
    const scheduler = new ManualScheduler();
    const opener = new FakeOpener(
      { wsUrl: 'ws://media-1/ws', token: 'one', state: 'live' },
      { wsUrl: 'ws://media-2/ws', token: 'two', state: 'waking' }
    );
    const sockets: FakeWebSocket[] = [];
    const transport = new DirectStreamTransport({
      opener,
      webSocketFactory: (url) => {
        const socket = new FakeWebSocket(url);
        sockets.push(socket);
        return socket;
      },
      scheduler,
      random: () => 0.5,
      reconnect: { initialDelayMs: 250, jitter: 0, maxAttempts: 2 }
    });

    const first = transport.connect(request);
    await flushPromises();
    sockets[0]?.open();
    await first;
    sockets[0]?.serverClose({ code: 1006, reason: 'network', wasClean: false });

    expect(scheduler.delays.at(-1)).toBe(250);
    scheduler.runNext();
    await flushPromises();
    expect(opener.calls).toHaveLength(2);
    expect(sockets[1]?.url).toContain('media-2');
    sockets[1]?.open();
    await flushPromises();
    expect(transport.state).toBe('connected');
    transport.destroy();
  });

  it('stops and reports when consecutive reconnect attempts are exhausted', async () => {
    const scheduler = new ManualScheduler();
    const sockets: FakeWebSocket[] = [];
    const transport = new DirectStreamTransport({
      opener: new FakeOpener(
        { wsUrl: 'ws://media-1/ws', state: 'live' },
        { wsUrl: 'ws://media-2/ws', state: 'waking' }
      ),
      webSocketFactory: (url) => {
        const socket = new FakeWebSocket(url);
        sockets.push(socket);
        return socket;
      },
      scheduler,
      random: () => 0.5,
      reconnect: { maxAttempts: 1, initialDelayMs: 1, jitter: 0 }
    });
    const errors: string[] = [];
    transport.on('error', (error) => errors.push(error.code));

    const first = transport.connect(request);
    await flushPromises();
    sockets[0]?.open();
    await first;
    sockets[0]?.serverClose();
    scheduler.runNext();
    await flushPromises();
    sockets[1]?.fail();
    await flushPromises();

    expect(errors).toContain('RECONNECT_EXHAUSTED');
    expect(transport.state).toBe('closed');
    expect(scheduler.size).toBe(0);
    transport.destroy();
  });
});
