export type VideoCodec = 'h264' | 'h265';

export interface VideoParameterSets {
  codec: VideoCodec;
  vps?: Uint8Array;
  sps: Uint8Array;
  pps: Uint8Array;
}

export function stripAnnexBStartCode(nalu: Uint8Array): Uint8Array {
  if (nalu.length >= 4 && nalu[0] === 0 && nalu[1] === 0 && nalu[2] === 0 && nalu[3] === 1) {
    return nalu.slice(4);
  }
  if (nalu.length >= 3 && nalu[0] === 0 && nalu[1] === 0 && nalu[2] === 1) {
    return nalu.slice(3);
  }
  return nalu;
}

export function detectParameterSetCodec(sps: Uint8Array, hasVps: boolean): VideoCodec {
  if (hasVps) return 'h265';
  const data = stripAnnexBStartCode(sps);
  const first = data[0] ?? 0;
  return ((first >> 1) & 0x3f) === 33 ? 'h265' : 'h264';
}

export function createAvcDecoderConfigurationRecord(sps: Uint8Array, pps: Uint8Array): Uint8Array {
  const spsData = stripAnnexBStartCode(sps);
  const ppsData = stripAnnexBStartCode(pps);
  if (spsData.length < 4 || ppsData.length < 1) {
    throw new Error('Invalid H.264 SPS/PPS');
  }

  const record = new Uint8Array(11 + spsData.length + ppsData.length);
  let offset = 0;
  record[offset++] = 1;
  record[offset++] = spsData[1] ?? 0;
  record[offset++] = spsData[2] ?? 0;
  record[offset++] = spsData[3] ?? 0;
  record[offset++] = 0xff;
  record[offset++] = 0xe1;
  record[offset++] = (spsData.length >> 8) & 0xff;
  record[offset++] = spsData.length & 0xff;
  record.set(spsData, offset);
  offset += spsData.length;
  record[offset++] = 1;
  record[offset++] = (ppsData.length >> 8) & 0xff;
  record[offset++] = ppsData.length & 0xff;
  record.set(ppsData, offset);
  return record;
}

export function h264CodecString(sps: Uint8Array): string {
  const data = stripAnnexBStartCode(sps);
  const profile = data[1] ?? 0x42;
  const compatibility = data[2] ?? 0;
  const level = data[3] ?? 0x1e;
  return `avc1.${hex(profile)}${hex(compatibility)}${hex(level)}`;
}

export function annexBToAvcc(data: Uint8Array): Uint8Array {
  const units = splitAnnexB(data);
  if (units.length === 0) return data.slice();
  const size = units.reduce((total, unit) => total + 4 + unit.length, 0);
  const output = new Uint8Array(size);
  let offset = 0;
  for (const unit of units) {
    output[offset++] = (unit.length >>> 24) & 0xff;
    output[offset++] = (unit.length >>> 16) & 0xff;
    output[offset++] = (unit.length >>> 8) & 0xff;
    output[offset++] = unit.length & 0xff;
    output.set(unit, offset);
    offset += unit.length;
  }
  return output;
}

export function splitAnnexB(data: Uint8Array): Uint8Array[] {
  const starts: Array<{ offset: number; length: number }> = [];
  for (let index = 0; index < data.length - 2;) {
    const length = startCodeLengthAt(data, index);
    if (length > 0) {
      starts.push({ offset: index, length });
      index += length;
    } else {
      index += 1;
    }
  }
  if (starts.length === 0) return [];

  const units: Uint8Array[] = [];
  for (let index = 0; index < starts.length; index += 1) {
    const start = starts[index];
    if (!start) continue;
    const payloadStart = start.offset + start.length;
    const payloadEnd = starts[index + 1]?.offset ?? data.length;
    if (payloadEnd > payloadStart) units.push(data.slice(payloadStart, payloadEnd));
  }
  return units;
}

export function prependH265ParameterSets(parameters: VideoParameterSets, frame: Uint8Array): Uint8Array {
  if (parameters.codec !== 'h265' || !parameters.vps) return frame.slice();
  const parts = [parameters.vps, parameters.sps, parameters.pps, frame];
  const output = new Uint8Array(parts.reduce((total, part) => total + part.length, 0));
  let offset = 0;
  for (const part of parts) {
    output.set(part, offset);
    offset += part.length;
  }
  return output;
}

function startCodeLengthAt(data: Uint8Array, offset: number): number {
  if (data[offset] !== 0 || data[offset + 1] !== 0) return 0;
  if (data[offset + 2] === 1) return 3;
  return data[offset + 2] === 0 && data[offset + 3] === 1 ? 4 : 0;
}

function hex(value: number): string {
  return value.toString(16).padStart(2, '0');
}
