import { describe, expect, it } from 'vitest';
import {
  JT78DataType,
  JT78_HEADER_LENGTH,
  parseJT78Frame,
  parseServerMessage
} from '../src/protocol';
import { jt78Frame } from './helpers';

describe('JT78 protocol', () => {
  it('parses an 8-byte header and payload without DOM globals', () => {
    const frame = parseJT78Frame(jt78Frame(JT78DataType.VideoKeyFrame, [1, 2, 3], 7), 123);

    expect(frame).toEqual({
      dataType: JT78DataType.VideoKeyFrame,
      kind: 'video-key',
      channel: 7,
      flags: 0,
      payload: Uint8Array.of(1, 2, 3),
      byteLength: JT78_HEADER_LENGTH + 3,
      receivedAt: 123
    });
    expect(typeof document).toBe('undefined');
  });

  it.each([
    [JT78DataType.VideoDeltaFrame, 'video-delta'],
    [JT78DataType.VideoBFrame, 'video-b'],
    [JT78DataType.AudioFrame, 'audio'],
    [JT78DataType.Sps, 'sps'],
    [JT78DataType.Pps, 'pps'],
    [JT78DataType.AudioConfig, 'audio-config'],
    [JT78DataType.Vps, 'vps']
  ])('maps data type %# to %s', (dataType, kind) => {
    expect(parseJT78Frame(jt78Frame(dataType, [])).kind).toBe(kind);
  });

  it('rejects short and invalid frames', () => {
    expect(() => parseJT78Frame(Uint8Array.of(0x4a).buffer)).toThrow(/shorter/);
    expect(() => parseJT78Frame(new Uint8Array(8).buffer)).toThrow(/magic/);
  });
});

describe('subscription status messages', () => {
  it.each(['waking', 'pending', 'waiting'])('normalizes %s to waking', (state) => {
    expect(parseServerMessage(JSON.stringify({ type: 'state', state }))).toMatchObject({
      type: 'state',
      state: 'waking'
    });
  });

  it('normalizes live and failed messages', () => {
    expect(parseServerMessage('{"type":"state","state":"live"}')).toMatchObject({
      type: 'state', state: 'live'
    });
    expect(parseServerMessage('{"type":"state","state":"failed","code":"DEVICE_NO_RESPONSE","message":"timeout"}'))
      .toMatchObject({ type: 'state', state: 'failed', code: 'DEVICE_NO_RESPONSE', message: 'timeout' });
  });

  it('supports the legacy device_offline error during migration', () => {
    expect(parseServerMessage('{"type":"device_offline","message":"offline"}')).toMatchObject({
      type: 'error', code: 'DEVICE_OFFLINE', message: 'offline'
    });
  });

  it.each(['paused', 'completed', 'closed'])('parses playback state %s', (state) => {
    expect(parseServerMessage(JSON.stringify({ type: 'playback-state', state }))).toMatchObject({
      type: 'playback', state
    });
  });
});
