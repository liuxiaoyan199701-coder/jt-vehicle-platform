import { describe, expect, it } from 'vitest';
import { JTPlayerError } from '../src/errors';
import { JT1078Player } from '../src/player';
import type { Renderer } from '../src/render';
import { TalkbackController } from '../src/talkback';
import type { TransportSendData } from '../src/transport';
import type { StreamTicket } from '../src/types';
import {
  FakeOpener,
  FakeWebSocket,
  ManualScheduler,
  waitForSocket
} from './helpers';

const MAIN_TICKET: StreamTicket = {
  wsUrl: 'ws://media.example/main',
  token: 'main-token',
  state: 'live'
};
const TALKBACK_TICKET: StreamTicket = {
  wsUrl: 'ws://media.example/talkback',
  token: 'talk-token',
  state: 'live'
};

describe('JT1078Player talkback session', () => {
  it('opens an independent TALKBACK stream with the current credential before capturing audio', async () => {
    const harness = createHarness(MAIN_TICKET, TALKBACK_TICKET);
    await openMain(harness, 'jwt-current');

    const starting = harness.player.startTalk();
    const talkbackSocket = await waitForSocket(harness.sockets, 1);

    expect(harness.talkback.startCalls).toBe(0);
    expect(harness.opener.calls[1]).toMatchObject({
      request: { deviceId: 'device-1', channel: 2, streamKind: 'talkback' },
      context: { credential: 'jwt-current' }
    });
    expect(harness.sockets[0]?.closeCalls).toHaveLength(0);

    talkbackSocket.open();
    await starting;
    harness.talkback.sendFrame(Uint8Array.of(1, 2, 3).buffer);

    expect(harness.talkback.startCalls).toBe(1);
    expect(talkbackSocket.sent).toHaveLength(1);
    expect(harness.sockets[0]?.sent).toHaveLength(0);
    expect(harness.player.state).toBe('playing');
    await harness.player.destroy();
  });

  it('coalesces concurrent starts and keeps one session while reconnecting', async () => {
    const harness = createHarness(MAIN_TICKET, TALKBACK_TICKET);
    await openMain(harness);

    const first = harness.player.startTalk();
    const second = harness.player.startTalk();
    const talkbackSocket = await waitForSocket(harness.sockets, 1);

    expect(harness.opener.calls).toHaveLength(2);
    expect(harness.sockets).toHaveLength(2);
    talkbackSocket.open();
    await Promise.all([first, second]);

    expect(harness.talkback.startCalls).toBe(1);
    talkbackSocket.serverClose({ code: 1006, reason: 'network', wasClean: false });
    await harness.player.startTalk();
    expect(harness.opener.calls).toHaveLength(2);
    expect(harness.sockets).toHaveLength(2);
    await harness.player.destroy();
  });

  it('cancels talkback while its WebSocket is still connecting', async () => {
    const harness = createHarness(MAIN_TICKET, TALKBACK_TICKET);
    await openMain(harness);

    const starting = harness.player.startTalk();
    const talkbackSocket = await waitForSocket(harness.sockets, 1);
    await harness.player.stopTalk();
    await starting;

    expect(talkbackSocket.closeCalls.at(-1)?.code).toBe(1000);
    expect(harness.talkback.startCalls).toBe(0);
    expect(harness.sockets[0]?.closeCalls).toHaveLength(0);
    await harness.player.destroy();
  });

  it('stopTalk and destroy close only their respective talkback sessions', async () => {
    const harness = createHarness(MAIN_TICKET, TALKBACK_TICKET, TALKBACK_TICKET);
    await openMain(harness);
    const firstStart = harness.player.startTalk();
    const firstTalkback = await waitForSocket(harness.sockets, 1);
    firstTalkback.open();
    await firstStart;

    await harness.player.stopTalk();

    expect(firstTalkback.closeCalls.at(-1)?.code).toBe(1000);
    expect(harness.sockets[0]?.closeCalls).toHaveLength(0);
    expect(harness.talkback.stopCalls).toBe(1);

    const secondStart = harness.player.startTalk();
    const secondTalkback = await waitForSocket(harness.sockets, 2);
    secondTalkback.open();
    await secondStart;
    await harness.player.destroy();

    expect(secondTalkback.closeCalls.at(-1)?.code).toBe(1000);
    expect(harness.sockets[0]?.closeCalls.at(-1)?.code).toBe(1000);
    expect(harness.talkback.destroyCalls).toBe(1);
  });

  it('switching playback closes talkback before opening the replacement main stream', async () => {
    const replacement: StreamTicket = { ...MAIN_TICKET, wsUrl: 'ws://media.example/replacement' };
    const harness = createHarness(MAIN_TICKET, TALKBACK_TICKET, replacement);
    await openMain(harness);
    const talkStart = harness.player.startTalk();
    const talkbackSocket = await waitForSocket(harness.sockets, 1);
    talkbackSocket.open();
    await talkStart;

    const switching = harness.player.play({ deviceId: 'device-2', channel: 3, streamKind: 'sub' });
    const replacementSocket = await waitForSocket(harness.sockets, 2);

    expect(talkbackSocket.closeCalls.at(-1)?.code).toBe(1000);
    expect(harness.talkback.stopCalls).toBe(1);
    expect(harness.opener.calls[2]?.request).toEqual({
      deviceId: 'device-2', channel: 3, streamKind: 'sub'
    });
    replacementSocket.open();
    await switching;
    await harness.player.destroy();
  });

  it('throws explicit busy and WebSocket authentication errors without starting capture', async () => {
    const busy = createHarness(
      MAIN_TICKET,
      new JTPlayerError('Talkback is occupied', { code: 'TALKBACK_BUSY', fatal: true })
    );
    await openMain(busy);

    await expect(busy.player.startTalk()).rejects.toMatchObject({
      code: 'TALKBACK_BUSY', message: 'Talkback is occupied'
    });
    expect(busy.talkback.startCalls).toBe(0);
    expect(busy.sockets).toHaveLength(1);
    expect(busy.sockets[0]?.closeCalls).toHaveLength(0);
    await busy.player.destroy();

    const unauthorized = createHarness(MAIN_TICKET, TALKBACK_TICKET);
    await openMain(unauthorized);
    const starting = unauthorized.player.startTalk();
    const talkbackSocket = await waitForSocket(unauthorized.sockets, 1);
    talkbackSocket.serverClose({ code: 4003, reason: 'AUTH_TOKEN_INVALID', wasClean: false });

    await expect(starting).rejects.toMatchObject({ code: 'AUTH_FAILED' });
    expect(unauthorized.talkback.startCalls).toBe(0);
    expect(unauthorized.sockets[0]?.closeCalls).toHaveLength(0);
    await unauthorized.player.destroy();
  });
});

interface Harness {
  player: JT1078Player;
  opener: FakeOpener;
  sockets: FakeWebSocket[];
  talkback: FakeTalkbackController;
}

function createHarness(...results: Array<StreamTicket | Error>): Harness {
  const opener = new FakeOpener(...results);
  const sockets: FakeWebSocket[] = [];
  const talkback = new FakeTalkbackController();
  const player = new JT1078Player({
    opener,
    webSocketFactory: (url) => {
      const socket = new FakeWebSocket(url);
      sockets.push(socket);
      return socket;
    },
    scheduler: new ManualScheduler(),
    statsScheduler: new ManualScheduler(),
    renderScheduler: new ManualScheduler(),
    renderer: new NoopRenderer(),
    audioOutput: null,
    talkback
  });
  return { player, opener, sockets, talkback };
}

async function openMain(harness: Harness, credential?: string): Promise<void> {
  const playing = harness.player.play({
    deviceId: 'device-1',
    channel: 2,
    streamKind: 'main',
    ...(credential ? { credential } : {})
  });
  (await waitForSocket(harness.sockets)).open();
  await playing;
}

class FakeTalkbackController extends TalkbackController {
  startCalls = 0;
  stopCalls = 0;
  destroyCalls = 0;
  private fakeActive = false;
  private sender: (data: TransportSendData) => void = () => undefined;

  constructor() {
    super({ send: () => undefined });
  }

  override get isActive(): boolean {
    return this.fakeActive;
  }

  override setSender(send: (data: TransportSendData) => void): void {
    super.setSender(send);
    this.sender = send;
  }

  override async start(): Promise<void> {
    this.startCalls += 1;
    this.fakeActive = true;
  }

  override async stop(): Promise<void> {
    if (!this.fakeActive) return;
    this.stopCalls += 1;
    this.fakeActive = false;
  }

  override async destroy(): Promise<void> {
    this.destroyCalls += 1;
    this.fakeActive = false;
  }

  sendFrame(data: TransportSendData): void {
    this.sender(data);
  }
}

class NoopRenderer implements Renderer {
  render(): void {}
}
