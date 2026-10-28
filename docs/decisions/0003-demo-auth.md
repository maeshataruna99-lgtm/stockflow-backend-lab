# ADR 0003 - Demo root user and secret handling

Status: Policy accepted in Phase 0; implementation NOT STARTED. The checks
below become evidence requirements in their phases; nothing here is verified
behavior yet.

## Decision (policy)

- A demo root account may exist only in `demo` and `local` profiles. The
  production profile never seeds it, and a test must assert that absence
  (Phase 2/5 evidence).
- `ROOT_USERNAME` and `ROOT_PASSWORD` come from environment variables (compose
  `env_file` or shell environment). They never appear in `.env.example`, code,
  docs, logs, or Git history. `.env` is gitignored; `.env.example` holds
  placeholders only.
- Passwords are stored only as strong hashes via `PasswordEncoder` (bcrypt) -
  never plaintext, never reversible encoding.
- No publicly reachable endpoint mints a root token. The concrete
  authentication/token-issuing approach for the lab (including the Phase 5 JWT
  use case) gets its own decision record before implementation. A lightweight
  local demo issuer is acceptable only if it is isolated to non-production
  profiles and every service still enforces authorization on its endpoints.

## Consequences

- Security acceptance tests (root absent under the production profile; example
  file placeholder-only; no public token minting) are required evidence in
  Phases 2 and 5.
- Until implemented, no authentication claim of any kind may be marked
  verified.
