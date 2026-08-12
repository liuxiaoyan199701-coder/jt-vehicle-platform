import type { SubscriptionState } from '../types';

export interface SubscriptionStatusMessage {
  type: 'state';
  state: SubscriptionState;
  code?: string;
  message?: string;
  raw: Readonly<Record<string, unknown>>;
}

export interface ServerErrorMessage {
  type: 'error';
  code: string;
  message: string;
  raw: Readonly<Record<string, unknown>>;
}

export type PlaybackServerState = 'paused' | 'completed' | 'closed';

export interface PlaybackStatusMessage {
  type: 'playback';
  state: PlaybackServerState;
  raw: Readonly<Record<string, unknown>>;
}

export interface OtherServerMessage {
  type: 'other';
  raw: Readonly<Record<string, unknown>>;
}

export type ServerMessage = SubscriptionStatusMessage | PlaybackStatusMessage
  | ServerErrorMessage | OtherServerMessage;

const WAKING_STATES = new Set(['waking', 'pending', 'waiting']);
const LIVE_STATES = new Set(['live', 'started', 'playing']);
const FAILED_STATES = new Set(['failed', 'dead']);

export function parseServerMessage(text: string): ServerMessage {
  const parsed: unknown = JSON.parse(text);
  if (!isRecord(parsed)) {
    return { type: 'other', raw: {} };
  }

  const type = stringValue(parsed.type).toLowerCase();
  const rawState = stringValue(parsed.state).toLowerCase();
  const code = stringValue(parsed.code) || inferLegacyCode(type);
  const message = stringValue(parsed.message) || code;

  if (type === 'error' || type === 'device_offline') {
    return {
      type: 'error',
      code: code || 'SERVER_ERROR',
      message: message || 'Server rejected the subscription',
      raw: parsed
    };
  }

  if (type === 'state') {
    if (WAKING_STATES.has(rawState)) {
      return { type: 'state', state: 'waking', raw: parsed };
    }
    if (LIVE_STATES.has(rawState)) {
      return { type: 'state', state: 'live', raw: parsed };
    }
    if (FAILED_STATES.has(rawState)) {
      const result: SubscriptionStatusMessage = { type: 'state', state: 'failed', raw: parsed };
      if (code) result.code = code;
      if (message) result.message = message;
      return result;
    }
  }

  if (type === 'playback-state'
    && (rawState === 'paused' || rawState === 'completed' || rawState === 'closed')) {
    return { type: 'playback', state: rawState, raw: parsed };
  }

  return { type: 'other', raw: parsed };
}

function inferLegacyCode(type: string): string {
  return type === 'device_offline' ? 'DEVICE_OFFLINE' : '';
}

function stringValue(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
