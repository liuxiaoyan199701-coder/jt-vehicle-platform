import { describe, expect, it, vi } from 'vitest';
import type { AudioDataLike, AudioOutput } from '../src/audio';
import { bindReactPlayerLifecycle, bindVuePlayerLifecycle } from '../src/adapters';
import type { VideoFrameLike } from '../src/decoder';
import { JT1078Player } from '../src/player';
import type { Renderer } from '../src/render';
import {
  chunkFactory,
  FakeOpener,
  FakeVideoDecoder,
  FakeWebSocket,
  flushPromises,
  H264_KEY,
  H264_PPS,
  H264_SPS,
  jt78Frame,
  ManualScheduler,
  waitForSocket
} from './helpers';

describe('JT1078Player facade', () => {
  it('performs one-call open/connect and renders immediately after parameter sets + keyframe', async () => {
    const harness = createHarness();
    const states: string[] = [];
    harness.player.on('state', (event) => states.push(event.state));

    const playing = harness.player.play({
      deviceId: '013800138000', channel: 1, streamKind: 'main', credential: 'jwt'
    });
    (await waitForSocket(harness.sockets)).open();
    await playing;
    harness.sockets[0]?.receive('{"type":"state","state":"live"}');
    harness.sockets[0]?.receive(jt78Frame(0xf0, H264_SPS));
    harness.sockets[0]?.receive(jt78Frame(0xf1, H264_PPS));
    harness.sockets[0]?.receive(jt78Frame(0x00, H264_KEY));
    await flushPromises();
    harness.renderScheduler.runNext();
    await flushPromises();

    expect(harness.opener.calls[0]).toMatchObject({
      request: { deviceId: '013800138000', channel: 1, streamKind: 'main' },
      context: { credential: 'jwt' }
    });
    expect(states).toContain('waking');
    expect(states).toContain('playing');
    expect(harness.renderer.frames).toHaveLength(1);
    expect(harness.renderer.frames[0]?.closed).toBe(true);
    await harness.player.destroy();
  });

  it('uses the same media pipeline for playback and delegates pause/seek/resume commands', async () => {
    const commands: unknown[] = [];
    const harness = createHarness({
      playbackControlEncoder: (command) => {
        commands.push(command);
        return JSON.stringify(command);
      }
    });
    const playing = harness.player.playback({
      deviceId: '013800138000',
      channel: 2,
      startTime: '2026-08-10T10:00:00Z',
      endTime: '2026-08-10T10:30:00Z'
    });
    (await waitForSocket(harness.sockets)).open();
    await playing;
    harness.sockets[0]?.receive('{"type":"state","state":"live"}');

    harness.player.pausePlayback();
    harness.player.seekPlayback('2026-08-10T10:12:00Z');
    harness.player.resumePlayback();

    expect(harness.opener.calls[0]?.request).toMatchObject({
      streamKind: 'playback',
      startTime: '2026-08-10T10:00:00.000Z',
      endTime: '2026-08-10T10:30:00.000Z'
    });
    expect(commands).toEqual([
      { action: 'pause' },
      { action: 'seek', position: '2026-08-10T10:12:00.000Z' },
      { action: 'resume' }
    ]);
    expect(harness.sockets[0]?.sent).toHaveLength(3);
    harness.sockets[0]?.receive('{"type":"playback-state","state":"completed"}');
    await flushPromises();
    expect(harness.player.state).toBe('stopped');
    expect(harness.sockets[0]?.closeCalls.at(-1)?.code).toBe(1000);
    await harness.player.destroy();
  });

  it('uses the server playback-control wire contract by default', async () => {
    const harness = createHarness();
    const playing = harness.player.playback({
      deviceId: '013800138000',
      channel: 2,
      startTime: '2026-08-10T10:00:00Z',
      endTime: '2026-08-10T10:30:00Z'
    });
    (await waitForSocket(harness.sockets)).open();
    await playing;

    harness.player.pausePlayback();
    harness.player.seekPlayback('2026-08-10T10:12:00Z');
    harness.player.resumePlayback();

    expect(harness.sockets[0]?.sent.map((message) => JSON.parse(String(message)))).toEqual([
      { type: 'playback-control', action: 'pause' },
      { type: 'playback-control', action: 'seek', position: '2026-08-10T10:12:00.000Z' },
      { type: 'playback-control', action: 'resume' }
    ]);
    await harness.player.destroy();
  });

  it('emits periodic stats and destroy releases connections, decoders, audio, renderer and timers', async () => {
    const harness = createHarness();
    const stats: unknown[] = [];
    harness.player.on('stats', (event) => stats.push(event));
    const playing = harness.player.play({ deviceId: 'device', channel: 1 });
    (await waitForSocket(harness.sockets)).open();
    await playing;
    harness.sockets[0]?.receive(jt78Frame(0xf0, H264_SPS));
    harness.sockets[0]?.receive(jt78Frame(0xf1, H264_PPS));
    harness.sockets[0]?.receive(jt78Frame(0x00, H264_KEY));
    await flushPromises();

    expect(harness.statsScheduler.size).toBe(1);
    expect(harness.decoders).toHaveLength(1);
    harness.statsScheduler.runNext();
    expect(stats).toHaveLength(1);
    await harness.player.destroy();

    expect(harness.sockets[0]?.closeCalls.at(-1)?.code).toBe(1000);
    expect(harness.decoders.every((decoder) => decoder.state === 'closed')).toBe(true);
    expect(harness.audio.closed).toBe(true);
    expect(harness.renderer.destroyed).toBe(true);
    expect(harness.statsScheduler.size).toBe(0);
    expect(harness.transportScheduler.size).toBe(0);
    expect(harness.renderScheduler.size).toBe(0);
    expect(harness.player.state).toBe('destroyed');
  });

  it('keeps two player instances isolated on the same page', async () => {
    const first = createHarness();
    const second = createHarness();
    const firstPlay = first.player.play({ deviceId: 'one', channel: 1 });
    const secondPlay = second.player.play({ deviceId: 'two', channel: 2 });
    (await waitForSocket(first.sockets)).open();
    (await waitForSocket(second.sockets)).open();
    await Promise.all([firstPlay, secondPlay]);
    first.sockets[0]?.receive('{"type":"state","state":"live"}');
    second.sockets[0]?.receive('{"type":"state","state":"live"}');

    await first.player.destroy();

    expect(first.player.state).toBe('destroyed');
    expect(second.player.state).toBe('playing');
    expect(second.sockets[0]?.closeCalls).toHaveLength(0);
    await second.player.destroy();
  });

  it('provides dependency-free Vue and React lifecycle bindings', async () => {
    const vue = createHarness();
    let vueCleanup: (() => void) | undefined;
    const vueDestroy = vi.spyOn(vue.player, 'destroy');
    bindVuePlayerLifecycle(vue.player, (cleanup) => { vueCleanup = cleanup; });
    vueCleanup?.();
    expect(vueDestroy).toHaveBeenCalledOnce();
    await vueDestroy.mock.results[0]?.value;

    const react = createHarness();
    const reactDestroy = vi.spyOn(react.player, 'destroy');
    bindReactPlayerLifecycle(react.player)();
    expect(reactDestroy).toHaveBeenCalledOnce();
    await reactDestroy.mock.results[0]?.value;
  });
});

interface HarnessOverrides {
  playbackControlEncoder?: (command: { action: 'pause' | 'resume' } | { action: 'seek'; position: string }) => string;
}

function createHarness(overrides: HarnessOverrides = {}) {
  const opener = new FakeOpener({ wsUrl: 'ws://media.example/ws', token: 'token', state: 'waking' });
  const sockets: FakeWebSocket[] = [];
  const transportScheduler = new ManualScheduler();
  const statsScheduler = new ManualScheduler();
  const renderScheduler = new ManualScheduler();
  const renderer = new FakeRenderer();
  const audio = new FakeAudioOutput();
  const decoders: FakeVideoDecoder[] = [];
  const player = new JT1078Player({
    opener,
    webSocketFactory: (url) => {
      const socket = new FakeWebSocket(url);
      sockets.push(socket);
      return socket;
    },
    scheduler: transportScheduler,
    statsScheduler,
    renderScheduler,
    renderer,
    audioOutput: audio,
    videoDecoderFactory: (init) => {
      const decoder = new FakeVideoDecoder(init, true);
      decoders.push(decoder);
      return decoder;
    },
    videoChunkFactory: chunkFactory,
    ...(overrides.playbackControlEncoder ? { playbackControlEncoder: overrides.playbackControlEncoder } : {})
  });
  return {
    player, opener, sockets, transportScheduler, statsScheduler,
    renderScheduler, renderer, audio, decoders
  };
}

class FakeRenderer implements Renderer {
  readonly frames: Array<VideoFrameLike & { closed?: boolean }> = [];
  destroyed = false;

  render(frame: VideoFrameLike): void {
    this.frames.push(frame);
  }

  destroy(): void {
    this.destroyed = true;
  }
}

class FakeAudioOutput implements AudioOutput {
  closed = false;

  async resume(): Promise<void> {}
  playPcm(): boolean { return true; }
  playAudioData(data: AudioDataLike): boolean { data.close(); return true; }
  reset(): void {}
  async close(): Promise<void> { this.closed = true; }
}
