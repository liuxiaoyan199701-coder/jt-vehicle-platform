export type JTPlayerErrorCode =
  | 'OPEN_STREAM_FAILED'
  | 'INVALID_OPEN_RESPONSE'
  | 'AUTH_FAILED'
  | 'DEVICE_NO_RESPONSE'
  | 'RESOURCE_UNAVAILABLE'
  | 'WEBSOCKET_FAILED'
  | 'RECONNECT_EXHAUSTED'
  | 'SEND_QUEUE_OVERFLOW'
  | 'INVALID_FRAME'
  | 'UNSUPPORTED_CODEC'
  | 'DECODER_UNAVAILABLE'
  | 'AUDIO_DECODER_FAILED'
  | 'MICROPHONE_UNAVAILABLE'
  | 'MICROPHONE_PERMISSION_DENIED'
  | 'INVALID_STATE'
  | 'DESTROYED'
  | string;

export interface JTPlayerErrorOptions {
  code: JTPlayerErrorCode;
  fatal?: boolean;
  retryable?: boolean;
  cause?: unknown;
}

export class JTPlayerError extends Error {
  readonly code: JTPlayerErrorCode;
  readonly fatal: boolean;
  readonly retryable: boolean;
  override readonly cause: unknown;

  constructor(message: string, options: JTPlayerErrorOptions) {
    super(message);
    this.name = 'JTPlayerError';
    this.code = options.code;
    this.fatal = options.fatal ?? false;
    this.retryable = options.retryable ?? false;
    this.cause = options.cause;
  }
}

export function toJTPlayerError(
  error: unknown,
  fallbackCode: JTPlayerErrorCode,
  fallbackMessage: string,
  options: Omit<JTPlayerErrorOptions, 'code' | 'cause'> = {}
): JTPlayerError {
  if (error instanceof JTPlayerError) return error;
  const message = error instanceof Error && error.message ? error.message : fallbackMessage;
  return new JTPlayerError(message, { ...options, code: fallbackCode, cause: error });
}
