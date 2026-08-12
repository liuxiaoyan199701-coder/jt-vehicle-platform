import { encodeALaw } from '../audio/g711a';
import type { AudioNodeLike } from '../audio/output';
import { JTPlayerError } from '../errors';
import type { TransportSendData } from '../transport/transport';
import { float32ToInt16, StreamingPcmResampler } from './resampler';

export interface MediaStreamTrackLike {
  stop(): void;
}

export interface MediaStreamLike {
  getTracks(): MediaStreamTrackLike[];
}

export interface MediaDevicesLike {
  getUserMedia(constraints: MediaStreamConstraints): Promise<MediaStreamLike>;
}

export interface AudioInputBufferLike {
  getChannelData(channel: number): Float32Array;
}

export interface AudioProcessEventLike {
  readonly inputBuffer: AudioInputBufferLike;
}

export interface ScriptProcessorNodeLike extends AudioNodeLike {
  onaudioprocess: ((event: AudioProcessEventLike) => void) | null;
}

export interface CaptureAudioContextLike {
  readonly sampleRate: number;
  readonly destination: AudioNodeLike;
  createMediaStreamSource(stream: MediaStreamLike): AudioNodeLike;
  createScriptProcessor(bufferSize: number, inputChannels: number, outputChannels: number): ScriptProcessorNodeLike;
  close(): Promise<void>;
}

export interface TalkbackControllerOptions {
  send(data: TransportSendData): void;
  mediaDevices?: MediaDevicesLike;
  audioContextFactory?: () => CaptureAudioContextLike;
  targetSampleRate?: number;
  frameDurationMs?: number;
  processorBufferSize?: number;
}

export class TalkbackController {
  private sendData: (data: TransportSendData) => void;
  private readonly mediaDevices: MediaDevicesLike | undefined;
  private readonly audioContextFactory: () => CaptureAudioContextLike;
  private readonly targetSampleRate: number;
  private readonly frameSamples: number;
  private readonly processorBufferSize: number;
  private stream: MediaStreamLike | null = null;
  private context: CaptureAudioContextLike | null = null;
  private source: AudioNodeLike | null = null;
  private processor: ScriptProcessorNodeLike | null = null;
  private resampler: StreamingPcmResampler | null = null;
  private sampleBuffer: number[] = [];
  private active = false;
  private destroyed = false;
  private captureGeneration = 0;

  constructor(options: TalkbackControllerOptions) {
    this.sendData = options.send;
    this.mediaDevices = options.mediaDevices;
    this.audioContextFactory = options.audioContextFactory ?? browserCaptureAudioContextFactory;
    this.targetSampleRate = options.targetSampleRate ?? 8000;
    const frameDurationMs = options.frameDurationMs ?? 20;
    this.frameSamples = Math.max(1, Math.round(this.targetSampleRate * frameDurationMs / 1000));
    this.processorBufferSize = options.processorBufferSize ?? 4096;
  }

  get isActive(): boolean {
    return this.active;
  }

  /** @internal Rebinds capture output when the owning player creates a new talkback session. */
  setSender(send: (data: TransportSendData) => void): void {
    this.sendData = send;
  }

  async start(): Promise<void> {
    if (this.destroyed) {
      throw new JTPlayerError('Talkback has been destroyed', { code: 'DESTROYED', fatal: true });
    }
    if (this.active) return;
    const generation = ++this.captureGeneration;

    let stream: MediaStreamLike;
    try {
      const mediaDevices = this.mediaDevices ?? browserMediaDevices();
      stream = await mediaDevices.getUserMedia({
        audio: {
          channelCount: 1,
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true
        }
      });
    } catch (error) {
      if (generation !== this.captureGeneration || this.destroyed) return;
      throw microphoneError(error);
    }
    if (generation !== this.captureGeneration || this.destroyed) {
      for (const track of stream.getTracks()) track.stop();
      return;
    }
    this.stream = stream;

    try {
      this.context = this.audioContextFactory();
      this.resampler = new StreamingPcmResampler(this.context.sampleRate, this.targetSampleRate);
      this.source = this.context.createMediaStreamSource(this.stream);
      this.processor = this.context.createScriptProcessor(this.processorBufferSize, 1, 1);
      this.processor.onaudioprocess = (event) => {
        if (this.active) this.process(event.inputBuffer.getChannelData(0));
      };
      this.source.connect(this.processor);
      this.processor.connect(this.context.destination);
      this.active = true;
    } catch (error) {
      await this.stop();
      throw new JTPlayerError('Unable to initialize microphone processing', {
        code: 'MICROPHONE_UNAVAILABLE',
        cause: error
      });
    }
  }

  async stop(): Promise<void> {
    this.captureGeneration += 1;
    this.active = false;
    if (this.processor) {
      this.processor.onaudioprocess = null;
      this.processor.disconnect?.();
      this.processor = null;
    }
    this.source?.disconnect?.();
    this.source = null;
    this.resampler?.reset();
    this.resampler = null;
    this.sampleBuffer.length = 0;
    for (const track of this.stream?.getTracks() ?? []) track.stop();
    this.stream = null;
    const context = this.context;
    this.context = null;
    if (context) {
      try { await context.close(); } catch { /* ignored */ }
    }
  }

  async destroy(): Promise<void> {
    if (this.destroyed) return;
    await this.stop();
    this.destroyed = true;
  }

  private process(input: Float32Array): void {
    const output = this.resampler?.push(input);
    if (!output) return;
    const pcm = float32ToInt16(output);
    for (const sample of pcm) this.sampleBuffer.push(sample);
    while (this.sampleBuffer.length >= this.frameSamples) {
      const frame = new Int16Array(this.sampleBuffer.splice(0, this.frameSamples));
      const encoded = encodeALaw(frame);
      this.sendData(encoded.slice().buffer as ArrayBuffer);
    }
  }
}

function microphoneError(error: unknown): JTPlayerError {
  const name = error instanceof Error ? error.name : '';
  const denied = name === 'NotAllowedError' || name === 'PermissionDeniedError' || name === 'SecurityError';
  return new JTPlayerError(
    denied ? 'Microphone permission was denied' : 'Microphone is unavailable',
    { code: denied ? 'MICROPHONE_PERMISSION_DENIED' : 'MICROPHONE_UNAVAILABLE', cause: error }
  );
}

function browserMediaDevices(): MediaDevicesLike {
  if (typeof navigator === 'undefined' || !navigator.mediaDevices) {
    throw new JTPlayerError('MediaDevices API is unavailable', {
      code: 'MICROPHONE_UNAVAILABLE',
      fatal: true
    });
  }
  return navigator.mediaDevices as unknown as MediaDevicesLike;
}

function browserCaptureAudioContextFactory(): CaptureAudioContextLike {
  const scope = globalThis as typeof globalThis & {
    AudioContext?: new () => CaptureAudioContextLike;
    webkitAudioContext?: new () => CaptureAudioContextLike;
  };
  const Context = scope.AudioContext ?? scope.webkitAudioContext;
  if (!Context) {
    throw new JTPlayerError('Web Audio API is unavailable', {
      code: 'MICROPHONE_UNAVAILABLE',
      fatal: true
    });
  }
  return new Context() as unknown as CaptureAudioContextLike;
}
