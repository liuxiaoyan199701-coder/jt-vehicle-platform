import { JTPlayerError } from '../errors';
import {
  negotiateAacConfig,
  parseAacSpecificConfig,
  stripAdtsHeader,
  type AudioConfigSupportChecker,
  type AudioDecoderConfigLike
} from './aac';

export interface AudioDataLike {
  readonly numberOfChannels: number;
  readonly numberOfFrames: number;
  readonly sampleRate: number;
  readonly timestamp?: number;
  copyTo(destination: Float32Array, options: { planeIndex: number }): void;
  close(): void;
}

export interface EncodedAudioChunkInitLike {
  type: 'key';
  timestamp: number;
  data: Uint8Array;
}

export interface EncodedAudioChunkLike {
  readonly timestamp?: number;
}

export interface AudioDecoderLike {
  readonly state: string;
  configure(config: AudioDecoderConfigLike): void;
  decode(chunk: EncodedAudioChunkLike): void;
  close(): void;
}

export interface AudioDecoderInitLike {
  output(data: AudioDataLike): void;
  error(error: Error): void;
}

export type AudioDecoderFactory = (init: AudioDecoderInitLike) => AudioDecoderLike;
export type EncodedAudioChunkFactory = (init: EncodedAudioChunkInitLike) => EncodedAudioChunkLike;

export interface AudioDecoderControllerOptions {
  decoderFactory?: AudioDecoderFactory;
  chunkFactory?: EncodedAudioChunkFactory;
  supportChecker?: AudioConfigSupportChecker;
  onData(data: AudioDataLike): void;
  onRecoverableError?: (error: JTPlayerError) => void;
}

export class AudioDecoderController {
  private readonly decoderFactory: AudioDecoderFactory;
  private readonly chunkFactory: EncodedAudioChunkFactory;
  private readonly supportChecker: AudioConfigSupportChecker;
  private readonly onData: (data: AudioDataLike) => void;
  private readonly onRecoverableError: ((error: JTPlayerError) => void) | undefined;
  private decoder: AudioDecoderLike | null = null;
  private currentConfig: AudioDecoderConfigLike | null = null;
  private sourceConfig: Uint8Array | null = null;
  private nextTimestamp = 0;
  private frameDurationUs = 0;
  private configuring: Promise<void> | null = null;
  private pendingFrames: Uint8Array[] = [];
  private closed = false;
  private droppedFramesValue = 0;

  constructor(options: AudioDecoderControllerOptions) {
    this.decoderFactory = options.decoderFactory ?? browserAudioDecoderFactory;
    this.chunkFactory = options.chunkFactory ?? browserEncodedAudioChunkFactory;
    this.supportChecker = options.supportChecker ?? browserAudioSupportChecker;
    this.onData = options.onData;
    this.onRecoverableError = options.onRecoverableError;
  }

  get droppedFrames(): number {
    return this.droppedFramesValue;
  }

  async configure(audioSpecificConfig: Uint8Array): Promise<void> {
    if (this.closed) return;
    this.sourceConfig = audioSpecificConfig.slice();
    const work = this.configureInternal(this.sourceConfig);
    this.configuring = work;
    try {
      await work;
      this.flushPending();
    } finally {
      if (this.configuring === work) this.configuring = null;
    }
  }

  push(payload: Uint8Array): void {
    if (this.closed) return;
    if (!this.decoder || this.decoder.state !== 'configured') {
      if (this.configuring || this.sourceConfig) {
        if (this.pendingFrames.length >= 16) {
          this.pendingFrames.shift();
          this.droppedFramesValue += 1;
        }
        this.pendingFrames.push(payload.slice());
        if (!this.configuring && this.sourceConfig) {
          void this.configure(this.sourceConfig).catch((error) => this.report(error));
        }
      } else {
        this.droppedFramesValue += 1;
      }
      return;
    }
    this.decode(payload);
  }

  resetForDiscontinuity(): void {
    if (this.closed) return;
    this.closeDecoder();
    this.nextTimestamp = 0;
    this.pendingFrames.length = 0;
    if (this.sourceConfig) {
      void this.configure(this.sourceConfig).catch((error) => this.report(error));
    }
  }

  reset(): void {
    if (this.closed) return;
    this.closeDecoder();
    this.sourceConfig = null;
    this.currentConfig = null;
    this.nextTimestamp = 0;
    this.pendingFrames.length = 0;
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    this.closeDecoder();
    this.pendingFrames.length = 0;
    this.sourceConfig = null;
  }

  private async configureInternal(source: Uint8Array): Promise<void> {
    const negotiated = await negotiateAacConfig(source, this.supportChecker);
    if (this.closed || this.sourceConfig !== source) return;
    this.closeDecoder();
    this.currentConfig = negotiated.config;
    const parsed = parseAacSpecificConfig(source);
    this.frameDurationUs = Math.round((1024 * 1_000_000) / parsed.sampleRate);
    this.decoder = this.decoderFactory({
      output: (data) => this.onData(data),
      error: (error) => this.recover(error)
    });
    this.decoder.configure(negotiated.config);
  }

  private decode(payload: Uint8Array): void {
    const decoder = this.decoder;
    if (!decoder) return;
    const data = stripAdtsHeader(payload);
    if (data.length === 0) {
      this.droppedFramesValue += 1;
      return;
    }
    const timestamp = this.nextTimestamp;
    this.nextTimestamp += this.frameDurationUs;
    try {
      decoder.decode(this.chunkFactory({ type: 'key', timestamp, data }));
    } catch (error) {
      this.droppedFramesValue += 1;
      this.report(error);
    }
  }

  private recover(error: unknown): void {
    if (this.closed) return;
    this.droppedFramesValue += 1;
    this.closeDecoder();
    this.report(error);
  }

  private flushPending(): void {
    const frames = this.pendingFrames.splice(0);
    for (const frame of frames) this.decode(frame);
  }

  private report(error: unknown): void {
    this.onRecoverableError?.(new JTPlayerError('AAC frame decode failed; subsequent frames will continue', {
      code: 'AUDIO_DECODER_FAILED',
      cause: error
    }));
  }

  private closeDecoder(): void {
    const decoder = this.decoder;
    this.decoder = null;
    if (decoder && decoder.state !== 'closed') {
      try { decoder.close(); } catch { /* ignored */ }
    }
  }
}

function browserAudioDecoderFactory(init: AudioDecoderInitLike): AudioDecoderLike {
  const Decoder = (globalThis as typeof globalThis & { AudioDecoder?: new (init: AudioDecoderInitLike) => AudioDecoderLike }).AudioDecoder;
  if (!Decoder) {
    throw new JTPlayerError('WebCodecs AudioDecoder is unavailable', {
      code: 'DECODER_UNAVAILABLE',
      fatal: true
    });
  }
  return new Decoder(init);
}

function browserEncodedAudioChunkFactory(init: EncodedAudioChunkInitLike): EncodedAudioChunkLike {
  const Chunk = (globalThis as typeof globalThis & { EncodedAudioChunk?: new (init: EncodedAudioChunkInitLike) => EncodedAudioChunkLike }).EncodedAudioChunk;
  if (!Chunk) {
    throw new JTPlayerError('WebCodecs EncodedAudioChunk is unavailable', {
      code: 'DECODER_UNAVAILABLE',
      fatal: true
    });
  }
  return new Chunk(init);
}

async function browserAudioSupportChecker(config: AudioDecoderConfigLike): Promise<boolean> {
  const Decoder = (globalThis as typeof globalThis & {
    AudioDecoder?: { isConfigSupported(config: AudioDecoderConfigLike): Promise<{ supported?: boolean }> }
  }).AudioDecoder;
  if (!Decoder) return false;
  const result = await Decoder.isConfigSupported(config);
  return result.supported === true;
}
