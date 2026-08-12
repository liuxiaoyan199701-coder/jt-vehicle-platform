export type LiveSessionDecision = 'connect' | 'retry' | 'stop';
export type LiveAuthChangeDecision = 'keep' | 'reconnect' | 'stop';

export interface LiveSessionProbeDependencies {
  ensureFreshAccessToken: () => boolean | Promise<boolean>;
  probeAuthenticatedSession: () => boolean | Promise<boolean>;
  hasAccessToken: () => boolean;
}

/** Decide whether a WebSocket attempt may proceed without conflating auth and network failures. */
export async function decideLiveSessionConnection(
  dependencies: LiveSessionProbeDependencies
): Promise<LiveSessionDecision> {
  try {
    if (!(await dependencies.ensureFreshAccessToken())) {
      return 'stop';
    }

    if (await dependencies.probeAuthenticatedSession()) {
      return 'connect';
    }
  } catch {
    // The request layer clears credentials only for unrecoverable authentication failures.
  }

  return dependencies.hasAccessToken() ? 'retry' : 'stop';
}

export function decideLiveAuthChange(
  activeAccessToken: string | null,
  currentAccessToken: string | null
): LiveAuthChangeDecision {
  if (!currentAccessToken) {
    return 'stop';
  }
  if (activeAccessToken && activeAccessToken !== currentAccessToken) {
    return 'reconnect';
  }
  return 'keep';
}

export function isConnectionStable(openedAt: number, now: number, stableWindowMs: number) {
  return now - openedAt >= stableWindowMs;
}
