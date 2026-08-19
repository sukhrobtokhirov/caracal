/**
 * The session token arrives once, in the launch URL the Go process prints.
 * We read it, keep it in memory, and immediately remove it from the visible
 * address bar so it is less likely to be copied, bookmarked, or captured in a
 * screenshot.
 */

const TOKEN_PARAM = "token";

export interface SessionLocation {
  search: string;
  pathname: string;
  hash: string;
}

export interface SessionHistory {
  replaceState(data: unknown, unused: string, url: string): void;
}

/** Extracts the token and strips it from the URL. Returns null when absent. */
export function claimToken(
  location: SessionLocation = window.location,
  history: SessionHistory = window.history,
): string | null {
  const params = new URLSearchParams(location.search);
  const token = params.get(TOKEN_PARAM);
  if (token === null) return null;

  params.delete(TOKEN_PARAM);
  const query = params.toString();
  const cleaned = `${location.pathname}${query ? `?${query}` : ""}${location.hash}`;
  try {
    history.replaceState(null, "", cleaned);
  } catch {
    // A sandboxed context may forbid replaceState; the token still works.
  }
  return token.trim() === "" ? null : token;
}
