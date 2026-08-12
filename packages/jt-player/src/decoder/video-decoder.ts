import { JTPlayerError } from '../errors';
import type { JT78Frame } from '../protocol/frame';
import {
  annexBToAvcc,
  createAvcDecoderConfigurationRecord,
  detectParameterSetCodec,
  h264CodecString,
  prependH265ParameterSets,
  type VideoCodec,
  type VideoParameterSets
} from './nal';

export interface VideoFrameLike {
  readonly displayWidth: number;
  readonly displayHeight: number;
  readonly timestamp?: number;
  close(): void;
}

export interface EncodedVideoChunkInitLike {
  type: 'key' | 'delta';
  timestamp: number;
  data: Uint8Array;
}

export interface EncodedVideoChunkLike {
  readonly type?: string;
  readonly timestamp?: number;
}

export interface VideoDecoderConfigLike {
  codec: string;
  hardwareAcceleration?: 'no-preference' | 'prefer-hardware' | 'prefer-software';
  optimizeForLatency?: boolean;
  description?: Uint8Array;
}

export interface VideoDecoderLike {
  readonly state: string;
  readonly decodeQueueSize: number;
  configure(config: VideoDecoderConfigLike): void;
  decode(chunk: EncodedVideoChunkLike): void;
  close(): void;
}

export interface VideoDecoderInitLike {
  output(frame: VideoFrameLike): void;
  error(error: Error): void;
}

export type VideoDecoderFactory = (init: VideoDecoderInitLike) => VideoDecoderLike;
export type EncodedVideoChunkFactory = (init: EncodedVideoChunkInitLike) => EncodedVideoChunkLike;

export interface DecodedVideoFrame {
  frame: VideoFrameLike;
  receivedAt: number;
  codec: VideoCodec;
}

export interface VideoDecoderControllerOptions {
  decoderFactory?: VideoDecoderFactory;
  chunkFactory?: EncodedVideoChunkFactory;
  onFrame(frame: DecodedVideoFrame): void;
  onRecoverableError?: (error: JTPlayerError) => void;
  maxDecodeQueue?: number;
  frameDurationUs?: number;
  h265Codec?: string;
}

export interface VideoDecoderStats {
  decodedFrames: number;
  droppedFrames: number;
  recoveries: number;
}

export class VideoParameterSetStore {
  private vps: Uint8Array | null = null;
  private sps: Uint8Array | null = null;
  private pps: Uint8Array | null = null;
  private revisionValue = 0;

  get revision(): number {
    return this.revisionValue;
  }

  update(frame: JT78Frame): boolean {
    let target: 'vps' | 'sps' | 'pps' | null = null;
    if (frame.kind === 'vps') target = 'vps';
    if (frame.kind === 'sps') target = 'sps';
    if (frame.kind === 'pps') target = 'pps';
    if (!target) return false;
    const previous = this[target];
    if (previous && equals(previous, frame.payload)) return false;
    this[target] = frame.payload.slice();
    this.revisionValue += 1;
    return true;
  }

  snapshot(): VideoParameterSets | null {
    if (!this.sps || !this.pps) return null;
    const codec = detectParameterSetCodec(this.sps, this.vps !== null);
    if (codec === 'h265' && !this.vps) return null;
    return {
      codec,
      sps: this.sps.slice(),
      pps: this.pps.slice(),
      ...(this.vps ? { vps: this.vps.slice() } : {})
    };
  }

  clear(): void {
    this.vps = null;
    this.sps = null;
    this.pps = null;
    this.revisionValue += 1;
  }
}

export class VideoDecoderController {
  readonly parameterSets = new VideoParameterSetStore();
  private readonly decoderFactory: VideoDecoderFactory;
  private readonly chunkFactory: EncodedVideoChunkFactory;
  private readonly onFrame: (frame: DecodedVideoFrame) => void;
  private readonly onRecoverableError: ((error: JTPlayerError) => void) | undefined;
  private readonly maxDecodeQueue: number;
  private readonly frameDurationUs: number;
  private readonly h265Codec: string;
  private readonly receivedAtByTimestamp = new Map<number, number>();

  private decoder: VideoDecoderLike | null = null;
  private configuredRevision = -1;
  private configuredCodec: VideoCodec | null = null;
  private nextTimestamp = 0;
  private awaitingKeyframe = true;
  private closed = false;
  private statsValue: VideoDecoderStats = { decodedFrames: 0, droppedFrames: 0, recoveries: 0 };

  constructor(options: VideoDecoderControllerOptions) {
    this.decoderFactory = options.decoderFactory ?? browserVideoDecoderFactory;
    this.chunkFactory = options.chunkFactory ?? browserEncodedVideoChunkFactory;
    this.onFrame = options.onFrame;
    this.onRecoverableError = options.onRecoverableError;
    this.maxDecodeQueue = options.maxDecodeQueue ?? 50;
    this.frameDurationUs = options.frameDurationUs ?? 40_000;
    this.h265Codec = options.h265Codec ?? 'hvc1.1.6.L93.B0';
  }

  get codec(): VideoCodec | null {
    return this.configuredCodec;
  }

  get stats(): Readonly<VideoDecoderStats> {
    return { ...this.statsValue };
  }

  push(frame: JT78Frame): void {
    if (this.closed) return;
    if (this.parameterSets.update(frame)) {
      this.configureIfReady(true);
      return;
    }
    if (frame.kind !== 'video-key' && frame.kind !== 'video-delta' && frame.kind !== 'video-b') return;

    const key = frame.kind === 'video-key';
    if (!this.configureIfReady(false)) {
      this.dropFrame();
      return;
    }
    if (this.awaitingKeyframe && !key) {
      this.dropFrame();
      return;
    }
    if (!key && (this.decoder?.decodeQueueSize ?? 0) > this.maxDecodeQueue) {
      this.dropFrame();
      return;
    }

    const decoder = this.decoder;
    const codec = this.configuredCodec;
    if (!decoder || !codec) {
      this.dropFrame();
      return;
    }

    const timestamp = this.nextTimestamp;
    this.nextTimestamp += this.frameDurationUs;
    const parameters = codec === 'h265' && key ? this.parameterSets.snapshot() : null;
    const data = codec === 'h264'
      ? annexBToAvcc(frame.payload)
      : parameters ? prependH265ParameterSets(parameters, frame.payload) : frame.payload.slice();
    try {
      const chunk = this.chunkFactory({ type: key ? 'key' : 'delta', timestamp, data });
      this.receivedAtByTimestamp.set(timestamp, frame.receivedAt);
      decoder.decode(chunk);
      if (key) this.awaitingKeyframe = false;
    } catch (error) {
      this.receivedAtByTimestamp.delete(timestamp);
      this.recover(error);
    }
  }

  resetForDiscontinuity(): void {
    if (this.closed) return;
    this.closeDecoder();
    this.configuredRevision = -1;
    this.configuredCodec = null;
    this.awaitingKeyframe = true;
    this.nextTimestamp = 0;
    this.receivedAtByTimestamp.clear();
  }

  reset(): void {
    this.resetForDiscontinuity();
    this.parameterSets.clear();
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    this.closeDecoder();
    this.receivedAtByTimestamp.clear();
    this.parameterSets.clear();
  }

  private configureIfReady(forceReconfigure: boolean): boolean {
    const parameters = this.parameterSets.snapshot();
    if (!parameters) return false;
    if (!forceReconfigure && this.decoder && this.configuredRevision === this.parameterSets.revision) return true;

    this.closeDecoder();
    try {
      this.decoder = this.decoderFactory({
        output: (frame) => this.handleOutput(frame),
        error: (error) => this.recover(error)
      });
      const config: VideoDecoderConfigLike = parameters.codec === 'h264'
        ? {
            codec: h264CodecString(parameters.sps),
            optimizeForLatency: true,
            description: createAvcDecoderConfigurationRecord(parameters.sps, parameters.pps)
          }
        : {
            codec: this.h265Codec,
            optimizeForLatency: true
          };

      this.configureWithFallback(config);
      this.configuredCodec = parameters.codec;
      this.configuredRevision = this.parameterSets.revision;
      this.awaitingKeyframe = true;
      return true;
    } catch (error) {
      this.closeDecoder();
      const detail = error instanceof Error && error.message ? `: ${error.message}` : '';
      this.onRecoverableError?.(new JTPlayerError(`Video decoder configuration failed${detail}`, {
        code: 'UNSUPPORTED_CODEC',
        cause: error
      }));
      return false;
    }
  }

  /**
   * 优先请求硬件解码，失败时回退到不指定加速方式（允许软件解码）。
   *
   * WebCodecs 允许实现在无法满足 `prefer-hardware` 时直接拒绝配置，因此在浏览器禁用了
   * 硬件加速、运行于虚拟机或远程桌面、显卡驱动缺失的环境里，即使 codec 本身完全受支持
   * （例如 H.264 Baseline）也会抛错。没有回退就等于在这些环境下完全放弃播放。
   */
  private configureWithFallback(config: VideoDecoderConfigLike): void {
    if (!this.decoder) throw new Error('Decoder is not created');
    try {
      this.decoder.configure({ ...config, hardwareAcceleration: 'prefer-hardware' });
      return;
    } catch (hardwareError) {
      // 硬件解码不可用，继续尝试软件解码；两者都失败才向上抛
      if (this.decoder.state === 'closed') {
        this.decoder = this.decoderFactory({
          output: (frame) => this.handleOutput(frame),
          error: (error) => this.recover(error)
        });
      }
      this.decoder.configure(config);
    }
  }

  private handleOutput(frame: VideoFrameLike): void {
    const timestamp = frame.timestamp ?? -1;
    const receivedAt = this.receivedAtByTimestamp.get(timestamp) ?? now();
    this.receivedAtByTimestamp.delete(timestamp);
    this.statsValue = { ...this.statsValue, decodedFrames: this.statsValue.decodedFrames + 1 };
    this.onFrame({ frame, receivedAt, codec: this.configuredCodec ?? 'h264' });
  }

  private recover(error: unknown): void {
    if (this.closed) return;
    this.dropFrame();
    this.statsValue = { ...this.statsValue, recoveries: this.statsValue.recoveries + 1 };
    this.closeDecoder();
    this.configuredRevision = -1;
    this.configuredCodec = null;
    this.awaitingKeyframe = true;
    this.receivedAtByTimestamp.clear();
    this.onRecoverableError?.(new JTPlayerError('Video frame decode failed; waiting for next keyframe', {
      code: 'UNSUPPORTED_CODEC',
      cause: error
    }));
  }

  private dropFrame(): void {
    this.statsValue = { ...this.statsValue, droppedFrames: this.statsValue.droppedFrames + 1 };
  }

  private closeDecoder(): void {
    const decoder = this.decoder;
    this.decoder = null;
    if (decoder && decoder.state !== 'closed') {
      try { decoder.close(); } catch { /* ignored */ }
    }
  }
}

function browserVideoDecoderFactory(init: VideoDecoderInitLike): VideoDecoderLike {
  const Decoder = (globalThis as typeof globalThis & { VideoDecoder?: new (init: VideoDecoderInitLike) => VideoDecoderLike }).VideoDecoder;
  if (!Decoder) {
    throw new JTPlayerError('WebCodecs VideoDecoder is unavailable', {
      code: 'DECODER_UNAVAILABLE',
      fatal: true
    });
  }
  return new Decoder(init);
}

function browserEncodedVideoChunkFactory(init: EncodedVideoChunkInitLike): EncodedVideoChunkLike {
  const Chunk = (globalThis as typeof globalThis & { EncodedVideoChunk?: new (init: EncodedVideoChunkInitLike) => EncodedVideoChunkLike }).EncodedVideoChunk;
  if (!Chunk) {
    throw new JTPlayerError('WebCodecs EncodedVideoChunk is unavailable', {
      code: 'DECODER_UNAVAILABLE',
      fatal: true
    });
  }
  return new Chunk(init);
}

function equals(left: Uint8Array, right: Uint8Array): boolean {
  if (left.length !== right.length) return false;
  return left.every((value, index) => value === right[index]);
}

function now(): number {
  return typeof performance !== 'undefined' ? performance.now() : Date.now();
}
