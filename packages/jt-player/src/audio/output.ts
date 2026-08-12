import { JTPlayerError } from '../errors';
import type { AudioDataLike } from './audio-decoder';

export interface AudioParamLike {
  value: number;
}

export interface AudioNodeLike {
  connect(destination: AudioNodeLike): AudioNodeLike | void;
  disconnect?(): void;
}

export interface BiquadFilterNodeLike extends AudioNodeLike {
  type: string;
  frequency: AudioParamLike;
  Q: AudioParamLike;
}

export interface AudioBufferLike {
  readonly duration: number;
  copyToChannel(source: Float32Array, channelNumber: number): void;
}

export interface AudioBufferSourceNodeLike extends AudioNodeLike {
  buffer: AudioBufferLike | null;
  onended: (() => void) | null;
  start(when?: number): void;
  stop?(): void;
}

export interface AudioContextLike {
  readonly sampleRate: number;
  readonly destination: AudioNodeLike;
  readonly currentTime: number;
  readonly state: string;
  createBuffer(numberOfChannels: number, length: number, sampleRate: number): AudioBufferLike;
  createBufferSource(): AudioBufferSourceNodeLike;
  createBiquadFilter(): BiquadFilterNodeLike;
  resume(): Promise<void>;
  close(): Promise<void>;
}

export type AudioContextFactory = () => AudioContextLike;

export interface AudioOutput {
  resume(): Promise<void>;
  playPcm(channels: readonly Float32Array[], sampleRate: number): boolean;
  playAudioData(data: AudioDataLike): boolean;
  reset(): void;
  close(): Promise<void>;
}

export interface WebAudioOutputOptions {
  contextFactory?: AudioContextFactory;
  highPassFrequency?: number;
  maxQueueSeconds?: number;
}

export class WebAudioOutput implements AudioOutput {
  readonly context: AudioContextLike;
  readonly highPassFilter: BiquadFilterNodeLike;
  readonly lowPassFilter: BiquadFilterNodeLike;
  private readonly maxQueueSeconds: number;
  private readonly sources = new Set<AudioBufferSourceNodeLike>();
  private nextAudioTime = 0;
  private closed = false;

  constructor(options: WebAudioOutputOptions = {}) {
    this.context = (options.contextFactory ?? browserAudioContextFactory)();
    this.maxQueueSeconds = options.maxQueueSeconds ?? 1.5;
    this.highPassFilter = this.context.createBiquadFilter();
    this.highPassFilter.type = 'highpass';
    this.highPassFilter.frequency.value = options.highPassFrequency ?? 400;
    this.highPassFilter.Q.value = 3;
    this.lowPassFilter = this.context.createBiquadFilter();
    this.lowPassFilter.type = 'lowpass';
    this.lowPassFilter.frequency.value = 3400;
    this.lowPassFilter.Q.value = 0.7;
    this.highPassFilter.connect(this.lowPassFilter);
    this.lowPassFilter.connect(this.context.destination);
    this.nextAudioTime = this.context.currentTime;
  }

  async resume(): Promise<void> {
    if (!this.closed && this.context.state === 'suspended') await this.context.resume();
  }

  playPcm(channels: readonly Float32Array[], sampleRate: number): boolean {
    if (this.closed || channels.length === 0) return false;
    const length = channels[0]?.length ?? 0;
    if (length === 0 || channels.some((channel) => channel.length !== length)) return false;
    const buffer = this.context.createBuffer(channels.length, length, sampleRate);
    channels.forEach((channel, index) => buffer.copyToChannel(channel, index));
    return this.schedule(buffer);
  }

  playAudioData(data: AudioDataLike): boolean {
    try {
      const channels: Float32Array[] = [];
      for (let index = 0; index < data.numberOfChannels; index += 1) {
        const channel = new Float32Array(data.numberOfFrames);
        data.copyTo(channel, { planeIndex: index });
        channels.push(channel);
      }
      return this.playPcm(channels, data.sampleRate);
    } finally {
      data.close();
    }
  }

  reset(): void {
    for (const source of this.sources) {
      try { source.stop?.(); } catch { /* ignored */ }
      source.disconnect?.();
    }
    this.sources.clear();
    this.nextAudioTime = this.context.currentTime;
  }

  async close(): Promise<void> {
    if (this.closed) return;
    this.closed = true;
    this.reset();
    this.highPassFilter.disconnect?.();
    this.lowPassFilter.disconnect?.();
    await this.context.close();
  }

  private schedule(buffer: AudioBufferLike): boolean {
    const currentTime = this.context.currentTime;
    if (this.nextAudioTime - currentTime > this.maxQueueSeconds) {
      this.reset();
      return false;
    }
    const source = this.context.createBufferSource();
    source.buffer = buffer;
    source.connect(this.highPassFilter);
    source.onended = () => {
      this.sources.delete(source);
      source.disconnect?.();
    };
    this.sources.add(source);
    const startAt = Math.max(this.nextAudioTime, currentTime);
    source.start(startAt);
    this.nextAudioTime = startAt + buffer.duration;
    return true;
  }
}

function browserAudioContextFactory(): AudioContextLike {
  const scope = globalThis as typeof globalThis & {
    AudioContext?: new () => AudioContextLike;
    webkitAudioContext?: new () => AudioContextLike;
  };
  const Context = scope.AudioContext ?? scope.webkitAudioContext;
  if (!Context) {
    throw new JTPlayerError('Web Audio API is unavailable', {
      code: 'DECODER_UNAVAILABLE',
      fatal: true
    });
  }
  return new Context() as unknown as AudioContextLike;
}
