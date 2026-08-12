import type { VideoFrameLike } from '../decoder/video-decoder';
import { JTPlayerError } from '../errors';

export interface Renderer {
  render(frame: VideoFrameLike): void | Promise<void>;
  reset?(): void;
  destroy?(): void | Promise<void>;
}

export interface Canvas2DContextLike {
  drawImage(image: unknown, dx: number, dy: number, dw: number, dh: number): void;
}

export interface CanvasLike {
  width: number;
  height: number;
  getContext(contextId: '2d'): Canvas2DContextLike | null;
}

export class Canvas2DRenderer implements Renderer {
  private readonly context: Canvas2DContextLike;

  constructor(private readonly canvas: CanvasLike) {
    const context = canvas.getContext('2d');
    if (!context) {
      throw new JTPlayerError('Canvas 2D context is unavailable', {
        code: 'INVALID_STATE',
        fatal: true
      });
    }
    this.context = context;
  }

  render(frame: VideoFrameLike): void {
    if (this.canvas.width !== frame.displayWidth) this.canvas.width = frame.displayWidth;
    if (this.canvas.height !== frame.displayHeight) this.canvas.height = frame.displayHeight;
    this.context.drawImage(frame, 0, 0, this.canvas.width, this.canvas.height);
  }
}
