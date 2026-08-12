import { JTPlayerError } from '../errors';

const AAC_SAMPLE_RATES = [
  96000, 88200, 64000, 48000, 44100, 32000, 24000,
  22050, 16000, 12000, 11025, 8000, 7350
] as const;

export interface AacSpecificConfig {
  audioObjectType: number;
  sampleRate: number;
  samplingFrequencyIndex: number;
  originalChannels: number;
}

export interface AudioDecoderConfigLike {
  codec: string;
  sampleRate: number;
  numberOfChannels: number;
  description: Uint8Array;
}

export type AudioConfigSupportChecker = (config: AudioDecoderConfigLike) => Promise<boolean>;

export interface NegotiatedAacConfig {
  config: AudioDecoderConfigLike;
  originalChannels: number;
  attemptedChannels: readonly number[];
}

export function parseAacSpecificConfig(data: Uint8Array): AacSpecificConfig {
  if (data.length < 2) {
    throw new JTPlayerError('AAC AudioSpecificConfig requires at least two bytes', {
      code: 'AUDIO_DECODER_FAILED'
    });
  }
  const byte0 = data[0] ?? 0;
  const byte1 = data[1] ?? 0;
  const audioObjectType = (byte0 >> 3) & 0x1f;
  const samplingFrequencyIndex = ((byte0 & 0x07) << 1) | ((byte1 >> 7) & 0x01);
  const originalChannels = (byte1 >> 3) & 0x0f;
  const sampleRate = AAC_SAMPLE_RATES[samplingFrequencyIndex];
  if (!sampleRate) {
    throw new JTPlayerError(`Unsupported AAC sampling frequency index: ${samplingFrequencyIndex}`, {
      code: 'AUDIO_DECODER_FAILED'
    });
  }
  return { audioObjectType, sampleRate, samplingFrequencyIndex, originalChannels };
}

export function rewriteAacChannelConfig(data: Uint8Array, channels: number): Uint8Array {
  const modified = data.slice();
  if (modified.length < 2) return modified;
  modified[1] = ((modified[1] ?? 0) & 0x87) | ((channels & 0x0f) << 3);
  return modified;
}

export function aacChannelCandidates(originalChannels: number): readonly number[] {
  if (originalChannels <= 0) return [1];
  if (originalChannels === 1) return [1];
  if (originalChannels === 2) return [2, 1];
  return [2, 1];
}

export async function negotiateAacConfig(
  audioSpecificConfig: Uint8Array,
  isSupported: AudioConfigSupportChecker
): Promise<NegotiatedAacConfig> {
  const parsed = parseAacSpecificConfig(audioSpecificConfig);
  const candidates = aacChannelCandidates(parsed.originalChannels);
  const attempted: number[] = [];
  for (const channels of candidates) {
    attempted.push(channels);
    const config: AudioDecoderConfigLike = {
      codec: 'mp4a.40.2',
      sampleRate: parsed.sampleRate,
      numberOfChannels: channels,
      description: rewriteAacChannelConfig(audioSpecificConfig, channels)
    };
    if (await isSupported(config)) {
      return { config, originalChannels: parsed.originalChannels, attemptedChannels: attempted };
    }
  }
  throw new JTPlayerError(`AAC configuration is unsupported after channel fallback (${attempted.join(' -> ')})`, {
    code: 'AUDIO_DECODER_FAILED'
  });
}

export function stripAdtsHeader(data: Uint8Array): Uint8Array {
  if (data.length < 7 || data[0] !== 0xff || ((data[1] ?? 0) & 0xf0) !== 0xf0) {
    return data;
  }
  const protectionAbsent = ((data[1] ?? 0) & 0x01) === 1;
  const headerLength = protectionAbsent ? 7 : 9;
  return data.length > headerLength ? data.slice(headerLength) : new Uint8Array();
}

export function audioSpecificConfigFromAdts(data: Uint8Array): Uint8Array | null {
  if (data.length < 4 || data[0] !== 0xff || ((data[1] ?? 0) & 0xf0) !== 0xf0) return null;
  const byte2 = data[2] ?? 0;
  const byte3 = data[3] ?? 0;
  const audioObjectType = ((byte2 >> 6) & 0x03) + 1;
  const samplingFrequencyIndex = (byte2 >> 2) & 0x0f;
  const channelConfiguration = ((byte2 & 0x01) << 2) | ((byte3 >> 6) & 0x03);
  return Uint8Array.of(
    (audioObjectType << 3) | (samplingFrequencyIndex >> 1),
    ((samplingFrequencyIndex & 0x01) << 7) | (channelConfiguration << 3)
  );
}
