import { describe, expect, it } from 'vitest';
import {
  annexBToAvcc,
  createAvcDecoderConfigurationRecord,
  VideoDecoderController,
  type VideoDecoderInitLike
} from '../src/decoder';
import { parseJT78Frame } from '../src/protocol';
import {
  chunkFactory,
  FakeVideoDecoder,
  H264_DELTA,
  H264_KEY,
  H264_PPS,
  H264_SPS,
  jt78Frame
} from './helpers';

describe('NAL helpers', () => {
  it('converts three-byte and four-byte Annex-B units to AVCC', () => {
    const input = Uint8Array.of(0, 0, 0, 1, 0x65, 1, 0, 0, 1, 0x41, 2, 3);
    expect([...annexBToAvcc(input)]).toEqual([
      0, 0, 0, 2, 0x65, 1,
      0, 0, 0, 3, 0x41, 2, 3
    ]);
  });

  it('builds an AVCDecoderConfigurationRecord from cached SPS/PPS', () => {
    const description = createAvcDecoderConfigurationRecord(
      Uint8Array.from(H264_SPS), Uint8Array.from(H264_PPS)
    );
    expect(description[0]).toBe(1);
    expect(description[1]).toBe(0x42);
    expect(description).toContain(0x67);
    expect(description).toContain(0x68);
  });
});

describe('VideoDecoderController', () => {
  it('configures on SPS/PPS and decodes the immediately following keyframe', () => {
    const decoders: FakeVideoDecoder[] = [];
    const controller = new VideoDecoderController({
      decoderFactory: (init) => {
        const decoder = new FakeVideoDecoder(init);
        decoders.push(decoder);
        return decoder;
      },
      chunkFactory,
      onFrame: () => undefined
    });

    controller.push(parseJT78Frame(jt78Frame(0xf0, H264_SPS)));
    controller.push(parseJT78Frame(jt78Frame(0xf1, H264_PPS)));
    controller.push(parseJT78Frame(jt78Frame(0x00, H264_KEY)));

    expect(decoders).toHaveLength(1);
    expect(decoders[0]?.configs[0]?.codec).toBe('avc1.42001e');
    expect(decoders[0]?.chunks).toHaveLength(1);
    expect(decoders[0]?.chunks[0]).toMatchObject({ type: 'key', timestamp: 0 });
  });

  it('configures H.265 after VPS/SPS/PPS and keeps Annex-B payloads', () => {
    const decoders: FakeVideoDecoder[] = [];
    const controller = new VideoDecoderController({
      decoderFactory: (init) => {
        const decoder = new FakeVideoDecoder(init);
        decoders.push(decoder);
        return decoder;
      },
      chunkFactory,
      onFrame: () => undefined
    });
    const vps = [0, 0, 0, 1, 0x40, 1];
    const sps = [0, 0, 0, 1, 0x42, 1];
    const pps = [0, 0, 0, 1, 0x44, 1];
    const key = [0, 0, 0, 1, 0x26, 1];

    controller.push(parseJT78Frame(jt78Frame(0xf3, vps)));
    controller.push(parseJT78Frame(jt78Frame(0xf0, sps)));
    controller.push(parseJT78Frame(jt78Frame(0xf1, pps)));
    controller.push(parseJT78Frame(jt78Frame(0x00, key)));

    expect(controller.codec).toBe('h265');
    expect(decoders[0]?.configs[0]).toMatchObject({ codec: 'hvc1.1.6.L93.B0' });
    const chunk = decoders[0]?.chunks[0] as { data?: Uint8Array } | undefined;
    expect([...chunk?.data ?? []]).toEqual([...vps, ...sps, ...pps, ...key]);
  });

  it('drops a bad frame and recreates the decoder at the next keyframe', () => {
    const decoders: FakeVideoDecoder[] = [];
    const errors: string[] = [];
    const controller = new VideoDecoderController({
      decoderFactory: (init: VideoDecoderInitLike) => {
        const decoder = new FakeVideoDecoder(init);
        if (decoders.length === 0) decoder.failNextDecode = true;
        decoders.push(decoder);
        return decoder;
      },
      chunkFactory,
      onFrame: () => undefined,
      onRecoverableError: (error) => errors.push(error.message)
    });

    controller.push(parseJT78Frame(jt78Frame(0xf0, H264_SPS)));
    controller.push(parseJT78Frame(jt78Frame(0xf1, H264_PPS)));
    controller.push(parseJT78Frame(jt78Frame(0x00, H264_KEY)));
    controller.push(parseJT78Frame(jt78Frame(0x01, H264_DELTA)));
    controller.push(parseJT78Frame(jt78Frame(0x00, H264_KEY)));

    expect(decoders).toHaveLength(2);
    expect(decoders[1]?.chunks).toHaveLength(1);
    expect(decoders[1]?.chunks[0]).toMatchObject({ type: 'key' });
    expect(controller.stats.recoveries).toBe(1);
    expect(controller.stats.droppedFrames).toBeGreaterThanOrEqual(2);
    expect(errors[0]).toMatch(/next keyframe/);
  });
});
