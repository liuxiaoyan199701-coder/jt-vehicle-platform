export type UnauthorizedAction = 'refresh' | 'retry-current' | 'logout';

/** Decide how to handle a 401 without rotating again for a stale request generation. */
export function decideUnauthorizedAction(
  attemptedAuthorization: string | null,
  currentAuthorization: string | null,
  alreadyRetried: boolean
): UnauthorizedAction {
  if (attemptedAuthorization && currentAuthorization && attemptedAuthorization !== currentAuthorization) {
    return 'retry-current';
  }

  return alreadyRetried ? 'logout' : 'refresh';
}

export function hasCredentialGenerationChanged(
  observedAuthorization: string | null | undefined,
  currentAuthorization: string | null,
  observedRefreshToken: string,
  currentRefreshToken: string
) {
  return Boolean(
    currentAuthorization &&
      ((observedAuthorization && observedAuthorization !== currentAuthorization) ||
        (observedRefreshToken && currentRefreshToken && observedRefreshToken !== currentRefreshToken))
  );
}
