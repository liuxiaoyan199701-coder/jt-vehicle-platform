import { describe, expect, it } from 'vitest';
import {
  StreamingPcmResampler,
  TalkbackController,
  type AudioProcessEventLike,
  type CaptureAudioContextLike,
  type MediaDevicesLike,
  type MediaStreamLike,
  type ScriptProcessorNodeLike
} from '../src/talkback';
import type { AudioNodeLike } from '../src/audio';

describe('StreamingPcmResampler', () => {
  it('resamples 48kHz microphone PCM to 8kHz across chunks', () => {
    const resampler = new StreamingPcmResampler(48000, 8000);
    const first = resampler.push(Float32Array.from({ length: 240 }, (_, index) => index / 240));
    const second = resampler.push(Float32Array.from({ length: 240 }, (_, index) => index / 240));

    expect(first.length + second.length).toBe(80);
    expect(first[0]).toBeCloseTo(0);
  });
});

describe('TalkbackController', () => {
  it('captures, resamples, frames and sends G.711A, then releases all resources', async () => {
    const track = { stopped: false, stop() { this.stopped = true; } };
    const stream: MediaStreamLike = { getTracks: () => [track] };
    const mediaDevices: MediaDevicesLike = { getUserMedia: async () => stream };
    const context = new FakeCaptureContext();
    const sent: Array<string | ArrayBuffer | ArrayBufferView> = [];
    const talkback = new TalkbackController({
      send: (data) => sent.push(data),
      mediaDevices,
      audioContextFactory: () => context,
      frameDurationMs: 20
    });

    await talkback.start();
    context.processor?.process(Float32Array.from({ length: 960 }, (_, index) => Math.sin(index / 10)));

    expect(talkback.isActive).toBe(true);
    expect(sent).toHaveLength(1);
    expect((sent[0] as ArrayBuffer).byteLength).toBe(160);

    await talkback.stop();
    expect(talkback.isActive).toBe(false);
    expect(track.stopped).toBe(true);
    expect(context.closed).toBe(true);
    expect(context.processor?.disconnected).toBe(true);
  });

  it('reports microphone permission denial with a stable error code', async () => {
    const denied = new Error('denied');
    denied.name = 'NotAllowedError';
    const talkback = new TalkbackController({
      send: () => undefined,
      mediaDevices: { getUserMedia: async () => Promise.reject(denied) },
      audioContextFactory: () => new FakeCaptureContext()
    });

    await expect(talkback.start()).rejects.toMatchObject({
      code: 'MICROPHONE_PERMISSION_DENIED',
      message: 'Microphone permission was denied'
    });
  });

  it('stops a late microphone stream when capture is cancelled during permission', async () => {
    let resolveStream!: (stream: MediaStreamLike) => void;
    const pendingStream = new Promise<MediaStreamLike>((resolve) => {
      resolveStream = resolve;
    });
    const track = { stopped: false, stop() { this.stopped = true; } };
    let contextCreated = false;
    const talkback = new TalkbackController({
      send: () => undefined,
      mediaDevices: { getUserMedia: async () => pendingStream },
      audioContextFactory: () => {
        contextCreated = true;
        return new FakeCaptureContext();
      }
    });

    const starting = talkback.start();
    await Promise.resolve();
    await talkback.stop();
    resolveStream({ getTracks: () => [track] });
    await starting;

    expect(track.stopped).toBe(true);
    expect(contextCreated).toBe(false);
    expect(talkback.isActive).toBe(false);
  });
});

class FakeNode implements AudioNodeLike {
  disconnected = false;
  readonly connections: AudioNodeLike[] = [];

  connect(destination: AudioNodeLike): AudioNodeLike {
    this.connections.push(destination);
    return destination;
  }

  disconnect(): void {
    this.disconnected = true;
  }
}

class FakeProcessor extends FakeNode implements ScriptProcessorNodeLike {
  onaudioprocess: ((event: AudioProcessEventLike) => void) | null = null;

  process(samples: Float32Array): void {
    this.onaudioprocess?.({ inputBuffer: { getChannelData: () => samples } });
  }
}

class FakeCaptureContext implements CaptureAudioContextLike {
  readonly sampleRate = 48000;
  readonly destination = new FakeNode();
  readonly source = new FakeNode();
  processor: FakeProcessor | null = null;
  closed = false;

  createMediaStreamSource(_stream: MediaStreamLike): AudioNodeLike {
    return this.source;
  }

  createScriptProcessor(_bufferSize: number, _inputChannels: number, _outputChannels: number): ScriptProcessorNodeLike {
    this.processor = new FakeProcessor();
    return this.processor;
  }

  async close(): Promise<void> {
    this.closed = true;
  }
}
