export type StreamKind = 'main' | 'sub' | 'playback' | 'talkback';

export interface PlayRequest {
  deviceId: string;
  channel: number;
  streamKind?: Exclude<StreamKind, 'playback'>;
  credential?: string;
}

export interface PlaybackRequest {
  deviceId: string;
  channel: number;
  startTime: string | number | Date;
  endTime: string | number | Date;
  credential?: string;
}

export interface OpenStreamRequest {
  deviceId: string;
  channel: number;
  streamKind: StreamKind;
  startTime?: string;
  endTime?: string;
}

export type SubscriptionState = 'waking' | 'live' | 'failed';

export interface StreamTicket {
  wsUrl: string;
  token?: string;
  state: Exclude<SubscriptionState, 'failed'>;
  streamId?: string;
}

export type PlayerState =
  | 'idle'
  | 'opening'
  | 'connecting'
  | 'waking'
  | 'playing'
  | 'reconnecting'
  | 'paused'
  | 'stopped'
  | 'error'
  | 'destroyed';

export interface PlayerStateEvent {
  state: PlayerState;
  previous: PlayerState;
  reason?: string;
}

export interface PlayerStats {
  fps: number;
  bitrate: number;
  latencyMs: number;
  droppedFrames: number;
  receivedBytes: number;
  decodedFrames: number;
  renderedFrames: number;
}

export interface PlaybackPauseCommand {
  action: 'pause' | 'resume';
}

export interface PlaybackSeekCommand {
  action: 'seek';
  position: string;
}

export type PlaybackControlCommand = PlaybackPauseCommand | PlaybackSeekCommand;
export type PlaybackControlEncoder = (command: PlaybackControlCommand) => string | ArrayBuffer | ArrayBufferView;
