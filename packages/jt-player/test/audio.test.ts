import { describe, expect, it } from 'vitest';
import {
  decodeALaw,
  encodeALaw,
  negotiateAacConfig,
  parseAacSpecificConfig,
  WebAudioOutput,
  type AudioBufferLike,
  type AudioBufferSourceNodeLike,
  type AudioContextLike,
  type AudioNodeLike,
  type BiquadFilterNodeLike
} from '../src/audio';

describe('AAC configuration', () => {
  it('falls back from a five-channel declaration to stereo then mono', async () => {
    const fiveChannelAsc = Uint8Array.of(0x12, 0x28);
    expect(parseAacSpecificConfig(fiveChannelAsc)).toMatchObject({
      sampleRate: 44100,
      originalChannels: 5
    });
    const attempts: number[] = [];

    const result = await negotiateAacConfig(fiveChannelAsc, async (config) => {
      attempts.push(config.numberOfChannels);
      return config.numberOfChannels === 1;
    });

    expect(attempts).toEqual([2, 1]);
    expect(result.attemptedChannels).toEqual([2, 1]);
    expect(result.config.numberOfChannels).toBe(1);
    expect(((result.config.description[1] ?? 0) >> 3) & 0x0f).toBe(1);
  });
});

describe('G.711A', () => {
  it('encodes independent ITU G.711 A-law PCM golden vectors', () => {
    const pcm = Int16Array.of(
      -32768, -30000, -20000, -10000, -4096, -1000, -256, -16, -8, -1,
      0, 1, 8, 16, 256, 1000, 4096, 10000, 20000, 30000, 32767
    );

    expect(Array.from(encodeALaw(pcm))).toEqual([
      0x2a, 0x28, 0x26, 0x36, 0x1a, 0x7a, 0x5a, 0x55, 0x55, 0x55,
      0xd5, 0xd5, 0xd5, 0xd4, 0xc5, 0xfa, 0x85, 0xb6, 0xa6, 0xa8, 0xaa
    ]);
  });

  it('decodes independent ITU G.711 A-law codeword golden vectors', () => {
    const codewords = Uint8Array.of(
      0x00, 0x10, 0x20, 0x30, 0x40, 0x50, 0x55, 0x7f,
      0x80, 0x90, 0xa0, 0xb0, 0xc0, 0xd0, 0xd5, 0xff
    );

    expect(Array.from(decodeALaw(codewords))).toEqual([
      -5504, -2752, -22016, -11008, -344, -88, -8, -848,
      5504, 2752, 22016, 11008, 344, 88, 8, 848
    ]);
  });
});

describe('WebAudioOutput', () => {
  it('routes every queued buffer through the migrated 400Hz high-pass filter', () => {
    const context = new FakeAudioContext();
    const output = new WebAudioOutput({ contextFactory: () => context });

    expect(output.highPassFilter.type).toBe('highpass');
    expect(output.highPassFilter.frequency.value).toBe(400);
    expect(output.highPassFilter.Q.value).toBe(3);
    expect(output.lowPassFilter.type).toBe('lowpass');
    expect(output.lowPassFilter.frequency.value).toBe(3400);
    expect(output.playPcm([Float32Array.of(0, 0.5, -0.5)], 8000)).toBe(true);
    expect(context.sources[0]?.connections[0]).toBe(output.highPassFilter);
  });
});

class FakeNode implements AudioNodeLike {
  readonly connections: AudioNodeLike[] = [];
  disconnected = false;

  connect(destination: AudioNodeLike): AudioNodeLike {
    this.connections.push(destination);
    return destination;
  }

  disconnect(): void {
    this.disconnected = true;
  }
}

class FakeFilter extends FakeNode implements BiquadFilterNodeLike {
  type = '';
  frequency = { value: 0 };
  Q = { value: 0 };
}

class FakeBuffer implements AudioBufferLike {
  readonly copied: Float32Array[] = [];

  constructor(readonly duration: number) {}

  copyToChannel(source: Float32Array, channelNumber: number): void {
    this.copied[channelNumber] = source.slice();
  }
}

class FakeSource extends FakeNode implements AudioBufferSourceNodeLike {
  buffer: AudioBufferLike | null = null;
  onended: (() => void) | null = null;
  startedAt = -1;
  stopped = false;

  start(when = 0): void {
    this.startedAt = when;
  }

  stop(): void {
    this.stopped = true;
  }
}

class FakeAudioContext implements AudioContextLike {
  readonly sampleRate = 48000;
  readonly destination = new FakeNode();
  readonly filters: FakeFilter[] = [];
  readonly sources: FakeSource[] = [];
  currentTime = 0;
  state = 'running';

  createBuffer(_channels: number, length: number, sampleRate: number): AudioBufferLike {
    return new FakeBuffer(length / sampleRate);
  }

  createBufferSource(): AudioBufferSourceNodeLike {
    const source = new FakeSource();
    this.sources.push(source);
    return source;
  }

  createBiquadFilter(): BiquadFilterNodeLike {
    const filter = new FakeFilter();
    this.filters.push(filter);
    return filter;
  }

  async resume(): Promise<void> {
    this.state = 'running';
  }

  async close(): Promise<void> {
    this.state = 'closed';
  }
}
