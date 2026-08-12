const ALAW_DECOMPRESS_TABLE = createDecompressTable();
const ALAW_SEGMENT_END = [
  0x1f, 0x3f, 0x7f, 0xff, 0x1ff, 0x3ff, 0x7ff, 0xfff
] as const;

export function decodeALaw(data: Uint8Array): Int16Array {
  const pcm = new Int16Array(data.length);
  for (let index = 0; index < data.length; index += 1) {
    pcm[index] = ALAW_DECOMPRESS_TABLE[data[index] ?? 0] ?? 0;
  }
  return pcm;
}

export function encodeALaw(pcm: Int16Array): Uint8Array {
  const encoded = new Uint8Array(pcm.length);
  for (let index = 0; index < pcm.length; index += 1) {
    encoded[index] = encodeSample(pcm[index] ?? 0);
  }
  return encoded;
}

export function int16ToFloat32(pcm: Int16Array): Float32Array {
  const output = new Float32Array(pcm.length);
  for (let index = 0; index < pcm.length; index += 1) {
    output[index] = (pcm[index] ?? 0) / 32768;
  }
  return output;
}

function createDecompressTable(): Int16Array {
  const table = new Int16Array(256);
  for (let value = 0; value < 256; value += 1) {
    const input = value ^ 0x55;
    const segment = (input & 0x70) >> 4;
    let magnitude = (input & 0x0f) << 4;
    if (segment === 0) {
      magnitude += 8;
    } else {
      magnitude += 0x108;
      if (segment > 1) magnitude <<= segment - 1;
    }
    table[value] = (input & 0x80) !== 0 ? magnitude : -magnitude;
  }
  return table;
}

function encodeSample(sample: number): number {
  // G.711 A-law quantizes a signed 13-bit value derived from 16-bit PCM.
  let value = sample >> 3;
  let mask: number;
  if (value >= 0) {
    mask = 0xd5;
  } else {
    mask = 0x55;
    value = -value - 1;
  }

  let segment = 0;
  while (segment < ALAW_SEGMENT_END.length && value > (ALAW_SEGMENT_END[segment] ?? 0)) {
    segment += 1;
  }
  if (segment >= ALAW_SEGMENT_END.length) return 0x7f ^ mask;

  const quantized = segment < 2
    ? (value >> 1) & 0x0f
    : (value >> segment) & 0x0f;
  return ((segment << 4) | quantized) ^ mask;
}
