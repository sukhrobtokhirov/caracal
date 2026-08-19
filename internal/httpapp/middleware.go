package httpapp

import (
	"crypto/subtle"
	"net/http"
	"strings"
)

// tokenAuth requires the per-process session token on every API request.
//
// The token is accepted either as `Authorization: Bearer <token>` (what the SPA
// sends after bootstrap) or as a `token` query parameter (the launch URL, used
// only for the very first document load). Comparison is constant time.
func tokenAuth(token string) func(http.Handler) http.Handler {
	want := []byte(token)
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			got := bearerToken(r)
			if got == "" {
				got = r.URL.Query().Get("token")
			}
			if subtle.ConstantTimeCompare([]byte(got), want) != 1 {
				writeError(w, http.StatusUnauthorized, CodeUnauthorized,
					"A valid session token is required. Reopen the URL printed by the server.")
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}

func bearerToken(r *http.Request) string {
	h := r.Header.Get("Authorization")
	const prefix = "Bearer "
	if len(h) > len(prefix) && strings.EqualFold(h[:len(prefix)], prefix) {
		return strings.TrimSpace(h[len(prefix):])
	}
	return ""
}

// originGuard rejects requests carrying an unexpected Origin header.
//
// Policy:
//   - A present Origin must exactly match one of the origins this server owns.
//   - A missing Origin is rejected for mutating methods and allowed for safe
//     ones. Browsers always attach Origin to non-GET/HEAD fetches, including
//     same-origin ones, so a missing Origin on a POST means the request did not
//     come from our page.
func originGuard(allowed []string) func(http.Handler) http.Handler {
	set := make(map[string]struct{}, len(allowed))
	for _, o := range allowed {
		set[o] = struct{}{}
	}
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			origin := r.Header.Get("Origin")
			if origin == "" {
				if isSafeMethod(r.Method) {
					next.ServeHTTP(w, r)
					return
				}
				writeError(w, http.StatusForbidden, CodeForbidden,
					"This request must be made from the local application page.")
				return
			}
			if _, ok := set[origin]; !ok {
				writeError(w, http.StatusForbidden, CodeForbidden,
					"This request must be made from the local application page.")
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}

func isSafeMethod(m string) bool {
	return m == http.MethodGet || m == http.MethodHead || m == http.MethodOptions
}

// securityHeaders applies defensive headers to every response. The CSP is
// deliberately tight: the production bundle is fully self-hosted.
func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("X-Frame-Options", "DENY")
		h.Set("Referrer-Policy", "no-referrer")
		h.Set("Content-Security-Policy",
			"default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; "+
				"img-src 'self' data:; font-src 'self' data:; connect-src 'self'; "+
				"base-uri 'none'; form-action 'none'; frame-ancestors 'none'")
		next.ServeHTTP(w, r)
	})
}
