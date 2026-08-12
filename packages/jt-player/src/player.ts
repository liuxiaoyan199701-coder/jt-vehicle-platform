import {
  audioSpecificConfigFromAdts,
  AudioDecoderController,
  decodeALaw,
  int16ToFloat32,
  WebAudioOutput,
  type AudioConfigSupportChecker,
  type AudioDecoderFactory,
  type AudioOutput,
  type EncodedAudioChunkFactory
} from './audio';
import {
  VideoDecoderController,
  type EncodedVideoChunkFactory,
  type VideoDecoderFactory
} from './decoder';
import { JTPlayerError, toJTPlayerError } from './errors';
import { TypedEventEmitter } from './events';
import { parseJT78Frame } from './protocol';
import { Canvas2DRenderer, RenderQueue, type CanvasLike, type Renderer, type RenderScheduler } from './render';
import { TalkbackController, type TalkbackControllerOptions } from './talkback';
import {
  DirectStreamTransport,
  type DirectStreamTransportOptions,
  type TimerScheduler,
  type TransportStateEvent
} from './transport';
import type {
  OpenStreamRequest,
  PlaybackControlCommand,
  PlaybackControlEncoder,
  PlaybackRequest,
  PlayRequest,
  PlayerState,
  PlayerStateEvent,
  PlayerStats
} from './types';

export interface JT1078PlayerEvents {
  state: PlayerStateEvent;
  error: JTPlayerError;
  stats: PlayerStats;
}

export interface JT1078PlayerOptions extends DirectStreamTransportOptions {
  transport?: DirectStreamTransport;
  canvas?: CanvasLike;
  renderer?: Renderer;
  renderScheduler?: RenderScheduler;
  videoDecoderFactory?: VideoDecoderFactory;
  videoChunkFactory?: EncodedVideoChunkFactory;
  audioDecoderFactory?: AudioDecoderFactory;
  audioChunkFactory?: EncodedAudioChunkFactory;
  audioSupportChecker?: AudioConfigSupportChecker;
  audioOutput?: AudioOutput | null;
  audioOutputFactory?: () => AudioOutput;
  talkback?: TalkbackController;
  talkbackOptions?: Omit<TalkbackControllerOptions, 'send'>;
  playbackControlEncoder?: PlaybackControlEncoder;
  statsIntervalMs?: number;
  statsScheduler?: TimerScheduler;
}

type AudioFormat = 'aac' | 'g711a' | null;

export class JT1078Player extends TypedEventEmitter<JT1078PlayerEvents> {
  private readonly transport: DirectStreamTransport;
  private readonly videoDecoder: VideoDecoderController;
  private readonly audioDecoder: AudioDecoderController;
  private readonly renderQueue: RenderQueue;
  private readonly playbackControlEncoder: PlaybackControlEncoder;
  private readonly statsIntervalMs: number;
  private readonly statsScheduler: TimerScheduler;
  private readonly audioOutputFactory: () => AudioOutput;
  private readonly talkbackOptions: Omit<TalkbackControllerOptions, 'send'> | undefined;
  private readonly transportDisposers: Array<() => void> = [];

  private talkback: TalkbackController | null;
  private talkbackTransport: DirectStreamTransport | null = null;
  private talkbackTransportDisposer: (() => void) | null = null;
  private talkbackStart: Promise<void> | null = null;
  private talkbackActive = false;
  private talkbackOperation = 0;
  private audioOutput: AudioOutput | null | undefined;
  private audioFormat: AudioFormat = null;
  private currentState: PlayerState = 'idle';
  private currentRequest: OpenStreamRequest | null = null;
  private currentCredential: string | undefined;
  private statsTimer: unknown = null;
  private destroyed = false;
  private operation = 0;
  private receivedBytes = 0;
  private renderedFrames = 0;
  private audioQueueDrops = 0;
  private latencySum = 0;
  private latencySamples = 0;
  private statsBaselineTime = now();
  private statsBaselineBytes = 0;
  private statsBaselineFrames = 0;
  private statsDroppedBaseline = 0;

  constructor(options: JT1078PlayerOptions) {
    super();
    this.transport = options.transport ?? new DirectStreamTransport(transportOptions(options));
    const renderer = options.renderer ?? (options.canvas ? new Canvas2DRenderer(options.canvas) : null);
    if (!renderer) {
      throw new JTPlayerError('A canvas or custom renderer is required', {
        code: 'INVALID_STATE',
        fatal: true
      });
    }

    this.renderQueue = new RenderQueue({
      renderer,
      ...(options.renderScheduler ? { scheduler: options.renderScheduler } : {}),
      onRendered: (decoded, renderedAt) => {
        this.renderedFrames += 1;
        this.latencySum += Math.max(0, renderedAt - decoded.receivedAt);
        this.latencySamples += 1;
      },
      onError: (error) => this.report(new JTPlayerError('Video rendering failed', {
        code: 'RENDER_FAILED',
        cause: error
      }))
    });

    this.videoDecoder = new VideoDecoderController({
      ...(options.videoDecoderFactory ? { decoderFactory: options.videoDecoderFactory } : {}),
      ...(options.videoChunkFactory ? { chunkFactory: options.videoChunkFactory } : {}),
      onFrame: (frame) => this.renderQueue.enqueue(frame),
      onRecoverableError: (error) => this.report(error)
    });

    this.audioOutput = options.audioOutput;
    this.audioOutputFactory = options.audioOutputFactory ?? (() => new WebAudioOutput());
    this.audioDecoder = new AudioDecoderController({
      ...(options.audioDecoderFactory ? { decoderFactory: options.audioDecoderFactory } : {}),
      ...(options.audioChunkFactory ? { chunkFactory: options.audioChunkFactory } : {}),
      ...(options.audioSupportChecker ? { supportChecker: options.audioSupportChecker } : {}),
      onData: (data) => {
        const output = this.getAudioOutput();
        if (!output) {
          data.close();
          return;
        }
        if (!output.playAudioData(data)) this.audioQueueDrops += 1;
      },
      onRecoverableError: (error) => this.report(error)
    });

    this.talkback = options.talkback ?? null;
    this.talkbackOptions = options.talkbackOptions;
    this.playbackControlEncoder = options.playbackControlEncoder ?? defaultPlaybackControlEncoder;
    this.statsIntervalMs = options.statsIntervalMs ?? 1000;
    this.statsScheduler = options.statsScheduler ?? defaultStatsScheduler;
    this.bindTransport();
  }

  get state(): PlayerState {
    return this.currentState;
  }

  async play(request: PlayRequest): Promise<void> {
    validateDeviceRequest(request.deviceId, request.channel);
    await this.start({
      deviceId: request.deviceId,
      channel: request.channel,
      streamKind: request.streamKind ?? 'main'
    }, request.credential);
  }

  async playback(request: PlaybackRequest): Promise<void> {
    validateDeviceRequest(request.deviceId, request.channel);
    const startTime = normalizeTime(request.startTime);
    const endTime = normalizeTime(request.endTime);
    if (new Date(startTime).getTime() >= new Date(endTime).getTime()) {
      throw new JTPlayerError('Playback endTime must be after startTime', { code: 'INVALID_STATE' });
    }
    await this.start({
      deviceId: request.deviceId,
      channel: request.channel,
      streamKind: 'playback',
      startTime,
      endTime
    }, request.credential);
  }

  pausePlayback(): void {
    this.ensurePlayback();
    this.transport.send(this.playbackControlEncoder({ action: 'pause' }));
    this.transition('paused');
  }

  resumePlayback(): void {
    this.ensurePlayback();
    this.transport.send(this.playbackControlEncoder({ action: 'resume' }));
    this.transition('playing');
  }

  seekPlayback(position: string | number | Date): void {
    this.ensurePlayback();
    const normalized = normalizeTime(position);
    this.videoDecoder.resetForDiscontinuity();
    this.audioDecoder.resetForDiscontinuity();
    this.renderQueue.reset();
    this.audioOutput?.reset();
    this.transport.send(this.playbackControlEncoder({ action: 'seek', position: normalized }));
    this.transition('waking', 'playback-seek');
  }

  async startTalk(): Promise<void> {
    this.ensureActive();
    const request = this.currentRequest;
    if (!request || this.transport.state !== 'connected') {
      throw new JTPlayerError('Talkback requires an active stream connection', { code: 'INVALID_STATE' });
    }
    if (this.talkbackActive) return;
    if (this.talkbackStart) return this.talkbackStart;

    const operation = ++this.talkbackOperation;
    const pending = this.openTalkbackSession(request, this.currentCredential, operation);
    this.talkbackStart = pending;
    try {
      await pending;
    } finally {
      if (this.talkbackStart === pending) this.talkbackStart = null;
    }
  }

  async stopTalk(): Promise<void> {
    this.talkbackOperation += 1;
    this.talkbackActive = false;
    this.releaseTalkbackTransport();
    await this.talkback?.stop();
  }

  async stop(): Promise<void> {
    if (this.destroyed) return;
    this.operation += 1;
    await this.stopSession();
    this.transition('stopped');
  }

  async destroy(): Promise<void> {
    if (this.destroyed) return;
    this.destroyed = true;
    this.operation += 1;
    this.clearStatsTimer();
    this.transport.destroy();
    for (const dispose of this.transportDisposers.splice(0)) dispose();
    await this.stopTalk();
    await this.talkback?.destroy();
    this.talkback = null;
    this.videoDecoder.close();
    this.audioDecoder.close();
    await this.renderQueue.destroy();
    await this.audioOutput?.close();
    this.audioOutput = null;
    this.currentRequest = null;
    this.currentCredential = undefined;
    this.transition('destroyed');
    this.clearListeners();
  }

  private async start(request: OpenStreamRequest, credential?: string): Promise<void> {
    this.ensureActive();
    const operation = ++this.operation;
    await this.stopSession();
    if (operation !== this.operation) return;
    this.resetMetrics();
    this.currentRequest = request;
    this.transition('opening');
    const output = this.getAudioOutput();
    if (output) {
      try { await output.resume(); } catch (error) {
        this.report(new JTPlayerError('Audio output could not be resumed', {
          code: 'AUDIO_DECODER_FAILED',
          cause: error
        }));
      }
    }
    if (operation !== this.operation) return;
    this.scheduleStats();
    this.currentCredential = credential;
    try {
      await this.transport.connect(request, credential ? { credential } : {});
    } catch (error) {
      if (operation !== this.operation) return;
      this.transition('error', error instanceof Error ? error.message : 'open-stream-failed');
      throw error;
    }
  }

  private async stopSession(): Promise<void> {
    this.clearStatsTimer();
    this.transport.stop();
    await this.stopTalk();
    this.videoDecoder.reset();
    this.audioDecoder.reset();
    this.renderQueue.reset();
    this.audioOutput?.reset();
    this.audioFormat = null;
    this.currentRequest = null;
    this.currentCredential = undefined;
  }

  private async openTalkbackSession(
    request: OpenStreamRequest,
    credential: string | undefined,
    operation: number
  ): Promise<void> {
    const transport = this.transport.createSibling();
    this.talkbackTransport = transport;
    let opening = true;
    let microphoneStarting = false;
    let startupError: JTPlayerError | null = null;
    const dispose = transport.on('error', (error) => {
      if (opening) {
        startupError ??= error;
        return;
      }
      if (this.talkbackTransport !== transport) return;
      this.report(error);
      if (error.fatal) {
        void this.stopTalk().catch((stopError) => {
          this.report(toJTPlayerError(stopError, 'INVALID_STATE', 'Unable to stop failed talkback session'));
        });
      }
    });
    this.talkbackTransportDisposer = dispose;

    try {
      await transport.connect({
        deviceId: request.deviceId,
        channel: request.channel,
        streamKind: 'talkback'
      }, credential ? { credential } : {});
      opening = false;
      if (!this.isCurrentTalkbackSession(operation, transport)) {
        this.releaseTalkbackTransport(transport);
        return;
      }
      if (startupError) throw startupError;

      if (!this.talkback) {
        this.talkback = new TalkbackController({
          ...(this.talkbackOptions ?? {}),
          send: (data) => transport.send(data)
        });
      } else {
        this.talkback.setSender((data) => transport.send(data));
      }
      microphoneStarting = true;
      await this.talkback.start();
      if (!this.isCurrentTalkbackSession(operation, transport)) {
        await this.talkback.stop();
        this.releaseTalkbackTransport(transport);
        return;
      }
      this.talkbackActive = true;
    } catch (error) {
      opening = false;
      const current = this.isCurrentTalkbackSession(operation, transport);
      this.releaseTalkbackTransport(transport);
      if (microphoneStarting) await this.talkback?.stop();
      if (!current) return;
      const playerError = microphoneStarting
        ? toJTPlayerError(error, 'MICROPHONE_UNAVAILABLE', 'Unable to start talkback capture')
        : toJTPlayerError(error, 'OPEN_STREAM_FAILED', 'Unable to open talkback stream');
      this.report(playerError);
      throw playerError;
    }
  }

  private isCurrentTalkbackSession(operation: number, transport: DirectStreamTransport): boolean {
    return !this.destroyed
      && operation === this.talkbackOperation
      && this.talkbackTransport === transport;
  }

  private releaseTalkbackTransport(transport: DirectStreamTransport | null = this.talkbackTransport): void {
    if (!transport) return;
    if (this.talkbackTransport === transport) {
      this.talkbackTransportDisposer?.();
      this.talkbackTransportDisposer = null;
      this.talkbackTransport = null;
    }
    transport.destroy();
  }

  private bindTransport(): void {
    this.transportDisposers.push(
      this.transport.on('state', (event) => this.handleTransportState(event)),
      this.transport.on('subscription', (event) => {
        if (event.state === 'waking') this.transition('waking');
        if (event.state === 'live') this.transition('playing');
        if (event.state === 'failed') this.transition('error', event.message ?? event.code);
      }),
      this.transport.on('binary', (data) => this.handleBinary(data)),
      this.transport.on('text', (message) => {
        if (message.type === 'playback') {
          if (message.state === 'paused') this.transition('paused');
          if (message.state === 'completed' || message.state === 'closed') {
            void this.stop().catch((error) => {
              this.report(toJTPlayerError(
                error, 'INVALID_STATE', 'Unable to close completed playback'));
            });
          }
          return;
        }
        const format = message.raw.audioFormat;
        if (format === 'aac' || format === 'g711a') this.audioFormat = format;
      }),
      this.transport.on('error', (error) => {
        this.report(error);
        if (error.fatal) this.transition('error', error.message);
      })
    );
  }

  private handleTransportState(event: TransportStateEvent): void {
    if (event.state === 'opening') this.transition('opening');
    if (event.state === 'connecting') this.transition('connecting');
    if (event.state === 'reconnecting') this.transition('reconnecting');
    if (event.state === 'closed' && this.currentState !== 'error' && this.currentState !== 'destroyed') {
      this.transition('stopped');
    }
  }

  private handleBinary(data: ArrayBuffer): void {
    let frame;
    try {
      frame = parseJT78Frame(data);
    } catch (error) {
      this.report(toJTPlayerError(error, 'INVALID_FRAME', 'Unable to parse JT78 frame'));
      return;
    }
    this.receivedBytes += frame.byteLength;

    if (frame.kind === 'sps' || frame.kind === 'pps' || frame.kind === 'vps'
      || frame.kind === 'video-key' || frame.kind === 'video-delta' || frame.kind === 'video-b') {
      this.videoDecoder.push(frame);
      return;
    }
    if (frame.kind === 'audio-config') {
      this.audioFormat = 'aac';
      void this.audioDecoder.configure(frame.payload).catch((error) => {
        this.report(toJTPlayerError(error, 'AUDIO_DECODER_FAILED', 'AAC configuration failed'));
      });
      return;
    }
    if (frame.kind === 'audio') this.handleAudio(frame.payload);
  }

  private handleAudio(payload: Uint8Array): void {
    if (this.audioFormat === null) {
      const config = audioSpecificConfigFromAdts(payload);
      if (config) {
        this.audioFormat = 'aac';
        void this.audioDecoder.configure(config).catch((error) => {
          this.report(toJTPlayerError(error, 'AUDIO_DECODER_FAILED', 'AAC configuration failed'));
        });
      } else {
        this.audioFormat = 'g711a';
      }
    }
    if (this.audioFormat === 'aac') {
      this.audioDecoder.push(payload);
      return;
    }
    const output = this.getAudioOutput();
    if (!output) return;
    const pcm = int16ToFloat32(decodeALaw(payload));
    if (!output.playPcm([pcm], 8000)) this.audioQueueDrops += 1;
  }

  private getAudioOutput(): AudioOutput | null {
    if (this.audioOutput !== undefined) return this.audioOutput;
    try {
      this.audioOutput = this.audioOutputFactory();
    } catch (error) {
      this.audioOutput = null;
      this.report(toJTPlayerError(error, 'DECODER_UNAVAILABLE', 'Audio output is unavailable'));
    }
    return this.audioOutput;
  }

  private scheduleStats(): void {
    this.clearStatsTimer();
    this.statsTimer = this.statsScheduler.set(() => {
      this.statsTimer = null;
      if (this.destroyed || !this.currentRequest) return;
      this.emitStats();
      this.scheduleStats();
    }, this.statsIntervalMs);
  }

  private emitStats(): void {
    const timestamp = now();
    const elapsedSeconds = Math.max(0.001, (timestamp - this.statsBaselineTime) / 1000);
    const videoStats = this.videoDecoder.stats;
    const stats: PlayerStats = {
      fps: (this.renderedFrames - this.statsBaselineFrames) / elapsedSeconds,
      bitrate: ((this.receivedBytes - this.statsBaselineBytes) * 8) / elapsedSeconds,
      latencyMs: this.latencySamples > 0 ? this.latencySum / this.latencySamples : 0,
      droppedFrames: this.totalDroppedFrames() - this.statsDroppedBaseline,
      receivedBytes: this.receivedBytes,
      decodedFrames: videoStats.decodedFrames,
      renderedFrames: this.renderedFrames
    };
    this.statsBaselineTime = timestamp;
    this.statsBaselineBytes = this.receivedBytes;
    this.statsBaselineFrames = this.renderedFrames;
    this.latencySum = 0;
    this.latencySamples = 0;
    this.emit('stats', stats);
  }

  private clearStatsTimer(): void {
    if (this.statsTimer !== null) {
      this.statsScheduler.clear(this.statsTimer);
      this.statsTimer = null;
    }
  }

  private resetMetrics(): void {
    this.receivedBytes = 0;
    this.renderedFrames = 0;
    this.audioQueueDrops = 0;
    this.latencySum = 0;
    this.latencySamples = 0;
    this.statsBaselineTime = now();
    this.statsBaselineBytes = 0;
    this.statsBaselineFrames = 0;
    this.statsDroppedBaseline = this.totalDroppedFrames();
  }

  private totalDroppedFrames(): number {
    return this.videoDecoder.stats.droppedFrames + this.renderQueue.droppedFrames
      + this.audioDecoder.droppedFrames + this.audioQueueDrops;
  }

  private ensurePlayback(): void {
    this.ensureActive();
    if (this.currentRequest?.streamKind !== 'playback') {
      throw new JTPlayerError('Playback control requires an active playback stream', {
        code: 'INVALID_STATE'
      });
    }
  }

  private ensureActive(): void {
    if (this.destroyed) {
      throw new JTPlayerError('Player has been destroyed', { code: 'DESTROYED', fatal: true });
    }
  }

  private transition(state: PlayerState, reason?: string): void {
    if (state === this.currentState) return;
    const previous = this.currentState;
    this.currentState = state;
    this.emit('state', { state, previous, ...(reason ? { reason } : {}) });
  }

  private report(error: JTPlayerError): void {
    this.emit('error', error);
  }
}

export function defaultPlaybackControlEncoder(command: PlaybackControlCommand): string {
  return JSON.stringify({ type: 'playback-control', ...command });
}

function transportOptions(options: JT1078PlayerOptions): DirectStreamTransportOptions {
  return {
    ...(options.openStreamUrl ? { openStreamUrl: options.openStreamUrl } : {}),
    ...(options.opener ? { opener: options.opener } : {}),
    ...(options.fetch ? { fetch: options.fetch } : {}),
    ...(options.webSocketFactory ? { webSocketFactory: options.webSocketFactory } : {}),
    ...(options.credential ? { credential: options.credential } : {}),
    ...(options.tokenQueryParam ? { tokenQueryParam: options.tokenQueryParam } : {}),
    ...(options.reconnect ? { reconnect: options.reconnect } : {}),
    ...(options.sendQueueLimit !== undefined ? { sendQueueLimit: options.sendQueueLimit } : {}),
    ...(options.connectionTimeoutMs !== undefined ? { connectionTimeoutMs: options.connectionTimeoutMs } : {}),
    ...(options.scheduler ? { scheduler: options.scheduler } : {}),
    ...(options.random ? { random: options.random } : {})
  };
}

function normalizeTime(value: string | number | Date): string {
  const date = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(date.getTime())) {
    throw new JTPlayerError('Invalid playback time', { code: 'INVALID_STATE' });
  }
  return date.toISOString();
}

function validateDeviceRequest(deviceId: string, channel: number): void {
  if (!deviceId.trim()) throw new JTPlayerError('deviceId is required', { code: 'INVALID_STATE' });
  if (!Number.isInteger(channel) || channel < 0 || channel > 255) {
    throw new JTPlayerError('channel must be an integer between 0 and 255', { code: 'INVALID_STATE' });
  }
}

function now(): number {
  return typeof performance !== 'undefined' ? performance.now() : Date.now();
}

const defaultStatsScheduler: TimerScheduler = {
  set: (handler, delayMs) => globalThis.setTimeout(handler, delayMs),
  clear: (handle) => globalThis.clearTimeout(handle as number)
};
