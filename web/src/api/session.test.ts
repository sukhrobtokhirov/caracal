import { describe, expect, it, vi } from "vitest";
import { claimToken } from "./session";

function fakeHistory() {
  const calls: string[] = [];
  return {
    calls,
    replaceState(_data: unknown, _unused: string, url: string) {
      calls.push(url);
    },
  };
}

describe("claimToken", () => {
  it("returns the token and strips it from the address bar", () => {
    const history = fakeHistory();
    const token = claimToken({ search: "?token=abc123", pathname: "/", hash: "" }, history);
    expect(token).toBe("abc123");
    expect(history.calls).toEqual(["/"]);
  });

  it("keeps other query parameters and the hash", () => {
    const history = fakeHistory();
    claimToken({ search: "?token=abc&debug=1", pathname: "/app", hash: "#grid" }, history);
    expect(history.calls).toEqual(["/app?debug=1#grid"]);
  });

  it("returns null when no token is present", () => {
    const history = fakeHistory();
    expect(claimToken({ search: "", pathname: "/", hash: "" }, history)).toBeNull();
    expect(history.calls).toEqual([]);
  });

  it("treats an empty token as missing", () => {
    expect(claimToken({ search: "?token=", pathname: "/", hash: "" }, fakeHistory())).toBeNull();
  });

  it("still returns the token when replaceState is unavailable", () => {
    const throwing = {
      replaceState: vi.fn(() => {
        throw new Error("sandboxed");
      }),
    };
    expect(claimToken({ search: "?token=abc", pathname: "/", hash: "" }, throwing)).toBe("abc");
  });
});
