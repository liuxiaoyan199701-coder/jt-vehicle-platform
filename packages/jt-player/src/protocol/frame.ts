import { JTPlayerError } from '../errors';

export const JT78_HEADER_LENGTH = 8;
export const JT78_MAGIC = Object.freeze([0x4a, 0x54, 0x37, 0x38] as const);

export enum JT78DataType {
  VideoKeyFrame = 0x00,
  VideoDeltaFrame = 0x01,
  VideoBFrame = 0x02,
  AudioFrame = 0x03,
  TransparentData = 0x04,
  Sps = 0xf0,
  Pps = 0xf1,
  AudioConfig = 0xf2,
  Vps = 0xf3
}

export type JT78FrameKind =
  | 'video-key'
  | 'video-delta'
  | 'video-b'
  | 'audio'
  | 'transparent'
  | 'sps'
  | 'pps'
  | 'audio-config'
  | 'vps'
  | 'unknown';

export interface JT78Frame {
  dataType: number;
  kind: JT78FrameKind;
  channel: number;
  flags: number;
  payload: Uint8Array;
  byteLength: number;
  receivedAt: number;
}

export type BinaryInput = ArrayBuffer | ArrayBufferView;

export function toUint8Array(input: BinaryInput): Uint8Array {
  if (ArrayBuffer.isView(input)) {
    return new Uint8Array(input.buffer, input.byteOffset, input.byteLength);
  }
  return new Uint8Array(input);
}

export function parseJT78Frame(input: BinaryInput, receivedAt = monotonicNow()): JT78Frame {
  const bytes = toUint8Array(input);
  if (bytes.byteLength < JT78_HEADER_LENGTH) {
    throw new JTPlayerError(`JT78 frame is shorter than ${JT78_HEADER_LENGTH} bytes`, {
      code: 'INVALID_FRAME'
    });
  }

  for (let index = 0; index < JT78_MAGIC.length; index += 1) {
    if (bytes[index] !== JT78_MAGIC[index]) {
      throw new JTPlayerError('Invalid JT78 frame magic', { code: 'INVALID_FRAME' });
    }
  }

  const dataType = bytes[4] ?? -1;
  const channel = bytes[5] ?? 0;
  const flags = ((bytes[6] ?? 0) << 8) | (bytes[7] ?? 0);
  return {
    dataType,
    kind: dataTypeToKind(dataType),
    channel,
    flags,
    payload: bytes.slice(JT78_HEADER_LENGTH),
    byteLength: bytes.byteLength,
    receivedAt
  };
}

export function dataTypeToKind(dataType: number): JT78FrameKind {
  switch (dataType) {
    case JT78DataType.VideoKeyFrame: return 'video-key';
    case JT78DataType.VideoDeltaFrame: return 'video-delta';
    case JT78DataType.VideoBFrame: return 'video-b';
    case JT78DataType.AudioFrame: return 'audio';
    case JT78DataType.TransparentData: return 'transparent';
    case JT78DataType.Sps: return 'sps';
    case JT78DataType.Pps: return 'pps';
    case JT78DataType.AudioConfig: return 'audio-config';
    case JT78DataType.Vps: return 'vps';
    default: return 'unknown';
  }
}

function monotonicNow(): number {
  return typeof performance !== 'undefined' ? performance.now() : Date.now();
}
