# Guidebook 10: API Versioning and Backward Compatibility

## 1. Concept

An API version in the path (`/api/v1/...`) is a promise: while v1 exists, its requests and responses keep working for existing clients. A change that would break them (a removed field, a changed meaning) goes into a new version (`/api/v2/...`) that runs next to v1, so clients move when they are ready. Old forms are not removed at once: they are marked deprecated first, with a pointer to the replacement.

## 2. Implementation

- v1 is the only served version. `/api/v1/**` routes are unchanged by this unit.
- `ApiVersionFilter` (web filter, runs before routing): a path under `/api/` without a version segment, such as `/api/products`, is rewritten to `/api/v1/products` (query string kept). The response gets `Deprecation: true` and `Link: </api/v1/products>; rel="successor-version"`.
- Because the rewrite happens before routing, the alias goes through the same route as v1: same authentication and role check, same rate limit bucket, same circuit breaker. A separate route would have given each client a second quota and left the alias unauthenticated.
- An unknown version (`/api/v9/...`) has no route and returns the 404 problem body (unit 5.9); nothing is forwarded.
- Adding v2 later: add routes for `/api/v2/...` pointing at the new endpoints (or at the same service with a v2 path), keep v1 routes, and decide the sunset date for v1 by adding a `Sunset` header.

## 3. Reproduction

Automated:

```bash
./mvnw --batch-mode test -Dtest=ApiVersioningGatewayTest
```

`ApiVersioningGatewayTest` (6 tests): an unversioned path reaches `/api/v1/products?page=0&size=5` downstream and carries the deprecation headers; a v1 path has no such headers; the alias without a token is 401; the alias with a read-only role cannot POST (403); the alias is rate limited; `/api/v9/products` is a 404 problem and nothing reaches the downstream.

Live: `playground/http/gateway-api-versioning.http`.

## 4. Failure modes and edge cases

- A path like `/api/v1` with no further segment is a version root and is not rewritten.
- `/api/v10/...` counts as a version segment (digits after `v`), so it is never rewritten to v1.
- Clients that ignore the `Deprecation` header will not notice a future removal; log or monitor use of the alias before removing it.
- A path that is an `/api/` path but not a real resource (for example `/api/unknown`) is rewritten to `/api/v1/unknown` and then fails at the downstream with its own 404.
- An unknown version returns 404 for any caller, token or not, because no route matches and the gateway filters never run.

## 5. Trade-offs

- Path versioning is visible and cache friendly; header versioning keeps URLs stable but is easy to miss.
- Keeping an unversioned alias helps old clients, but it is one more surface to secure and eventually remove.
- Running v1 and v2 side by side doubles testing until v1 is retired.

## 6. Interview questions

- What counts as a breaking change in a REST API, and what does not?
- Why rewrite before routing instead of adding a second route for the alias?
- What do the `Deprecation`, `Sunset` and `Link rel="successor-version"` headers tell a client?
- How would you retire v1 safely?
