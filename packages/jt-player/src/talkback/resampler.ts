export class StreamingPcmResampler {
  private readonly ratio: number;
  private tail = new Float32Array();
  private cursor = 0;

  constructor(readonly inputSampleRate: number, readonly outputSampleRate = 8000) {
    if (inputSampleRate <= 0 || outputSampleRate <= 0) {
      throw new RangeError('Sample rates must be positive');
    }
    this.ratio = inputSampleRate / outputSampleRate;
  }

  push(input: Float32Array): Float32Array {
    if (input.length === 0) return new Float32Array();
    const data = concat(this.tail, input);
    const output: number[] = [];
    while (this.cursor + 1 < data.length) {
      const leftIndex = Math.floor(this.cursor);
      const fraction = this.cursor - leftIndex;
      const left = data[leftIndex] ?? 0;
      const right = data[leftIndex + 1] ?? left;
      output.push(left + (right - left) * fraction);
      this.cursor += this.ratio;
    }
    const consumed = Math.min(Math.floor(this.cursor), data.length);
    this.tail = data.slice(consumed);
    this.cursor -= consumed;
    return Float32Array.from(output);
  }

  reset(): void {
    this.tail = new Float32Array();
    this.cursor = 0;
  }
}

export function float32ToInt16(input: Float32Array): Int16Array {
  const output = new Int16Array(input.length);
  for (let index = 0; index < input.length; index += 1) {
    const sample = Math.max(-1, Math.min(1, input[index] ?? 0));
    output[index] = sample < 0 ? Math.round(sample * 32768) : Math.round(sample * 32767);
  }
  return output;
}

function concat(left: Float32Array, right: Float32Array): Float32Array {
  if (left.length === 0) return right.slice();
  const output = new Float32Array(left.length + right.length);
  output.set(left, 0);
  output.set(right, left.length);
  return output;
}
