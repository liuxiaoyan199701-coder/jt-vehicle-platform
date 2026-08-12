import type { DecodedVideoFrame } from '../decoder/video-decoder';
import type { Renderer } from './renderer';

export interface RenderScheduler {
  request(callback: (timestamp: number) => void): unknown;
  cancel(handle: unknown): void;
}

export interface RenderQueueOptions {
  renderer: Renderer;
  scheduler?: RenderScheduler;
  maxFrames?: number;
  dropThreshold?: number;
  targetFrames?: number;
  onRendered?: (frame: DecodedVideoFrame, renderedAt: number) => void;
  onError?: (error: unknown) => void;
}

export class RenderQueue {
  private readonly renderer: Renderer;
  private readonly scheduler: RenderScheduler;
  private readonly maxFrames: number;
  private readonly dropThreshold: number;
  private readonly targetFrames: number;
  private readonly onRendered: ((frame: DecodedVideoFrame, renderedAt: number) => void) | undefined;
  private readonly onError: ((error: unknown) => void) | undefined;
  private readonly frames: DecodedVideoFrame[] = [];
  private scheduled: unknown = null;
  private destroyed = false;
  private droppedValue = 0;

  constructor(options: RenderQueueOptions) {
    this.renderer = options.renderer;
    this.scheduler = options.scheduler ?? browserRenderScheduler;
    this.maxFrames = options.maxFrames ?? 60;
    this.dropThreshold = options.dropThreshold ?? 55;
    this.targetFrames = options.targetFrames ?? 35;
    this.onRendered = options.onRendered;
    this.onError = options.onError;
  }

  get droppedFrames(): number {
    return this.droppedValue;
  }

  enqueue(frame: DecodedVideoFrame): void {
    if (this.destroyed) {
      frame.frame.close();
      return;
    }
    this.frames.push(frame);
    while (this.frames.length > this.maxFrames) this.dropOldest();
    this.schedule();
  }

  reset(): void {
    if (this.scheduled !== null) {
      this.scheduler.cancel(this.scheduled);
      this.scheduled = null;
    }
    while (this.frames.length > 0) this.dropOldest(false);
    this.renderer.reset?.();
  }

  async destroy(): Promise<void> {
    if (this.destroyed) return;
    this.destroyed = true;
    this.reset();
    await this.renderer.destroy?.();
  }

  private schedule(): void {
    if (this.scheduled !== null || this.frames.length === 0) return;
    this.scheduled = this.scheduler.request((timestamp) => {
      this.scheduled = null;
      void this.renderNext(timestamp);
    });
  }

  private async renderNext(timestamp: number): Promise<void> {
    if (this.destroyed) return;
    if (this.frames.length > this.dropThreshold) {
      while (this.frames.length > this.targetFrames) this.dropOldest();
    }
    const decoded = this.frames.shift();
    if (!decoded) return;
    try {
      await this.renderer.render(decoded.frame);
      this.onRendered?.(decoded, timestamp);
    } catch (error) {
      this.onError?.(error);
    } finally {
      decoded.frame.close();
    }
    this.schedule();
  }

  private dropOldest(countDrop = true): void {
    const frame = this.frames.shift();
    if (!frame) return;
    frame.frame.close();
    if (countDrop) this.droppedValue += 1;
  }
}

const browserRenderScheduler: RenderScheduler = {
  request(callback) {
    if (typeof requestAnimationFrame === 'function') return requestAnimationFrame(callback);
    return globalThis.setTimeout(() => callback(now()), 16);
  },
  cancel(handle) {
    if (typeof cancelAnimationFrame === 'function' && typeof handle === 'number') {
      cancelAnimationFrame(handle);
    } else {
      globalThis.clearTimeout(handle as number);
    }
  }
};

function now(): number {
  return typeof performance !== 'undefined' ? performance.now() : Date.now();
}
