# Guidebook 32: The Gateway as a Reverse Proxy

## 1. Concept

A reverse proxy sits in front of servers, receives the client's requests and forwards them to a server behind it. The service behind it sees the proxy as its client, so the proxy has to decide what the service learns about the real client, and which things the client is not allowed to decide.

What a proxy does with a request:
- It rewrites the connection: the service sees the proxy's connection and its own `Host`, not the client's.
- It drops hop-by-hop headers (`Connection`, `Keep-Alive`, `Proxy-Authorization`, `TE`, `Trailer`, `Transfer-Encoding`, `Upgrade`): they describe one hop, not the whole path.
- It tells the service who the client is, through headers such as `X-Forwarded-For`, `X-Forwarded-Host`, `X-Forwarded-Proto` or the standard `Forwarded`. These are ordinary headers, so a client can send them itself; a service that believes them blindly can be lied to (a forged client address, a forged host in a generated link, a forged `https`).
- It adds or removes headers of its own (here: `X-Request-Id`, `X-User-Id`, `X-User-Roles`, security headers, no `Server` header).

## 2. Implementation (api-gateway)

The gateway is Spring Cloud Gateway, a reverse proxy by construction. This unit checked what it does and closed one gap.
- Header sanitation by default: with no trusted proxy configured, Spring Cloud Gateway removes the `Forwarded` and `X-Forwarded-*` headers a client sends (the beans `removeForwardedHeadersFilter` and `removeXForwardedHeadersFilter`) and adds none.
- Gap found: the service therefore could not tell who the client was. `ClientAddressFilter` (new, a global filter) sets `X-Client-Ip` from the address of the TCP connection and removes any value the client sent. The gateway is the edge of the system, no other proxy stands in front of it, so the TCP peer is the client. If a load balancer is placed in front, this filter would report the load balancer's address and needs to change.
- Identity headers: `JwtAuthenticationFilter` (unit 5.2) already removes a client's `X-User-Id` and `X-User-Roles` and sets them from the verified token.
- Rate limiting keys on the verified user or the TCP peer address, never on a client-supplied header (unit 5.3).

## 3. Measured observation

`ReverseProxyGatewayTest` sends real HTTP requests to the running gateway with the JDK HTTP client and reads what WireMock, standing in for the service, received.

| Client sends | The service receives |
|---|---|
| a normal request | `X-Client-Ip: 127.0.0.1`, `Host: localhost:<service port>`, path and query unchanged (`/api/v1/products/501?page=2&size=5`) |
| `X-Client-Ip: 6.6.6.6`, `X-Forwarded-For: 6.6.6.6`, `X-Forwarded-Host: evil.example.test`, `X-Forwarded-Proto: https`, `Forwarded: for=6.6.6.6;...` | `X-Client-Ip: 127.0.0.1`; no `X-Forwarded-For`, `X-Forwarded-Host`, `X-Forwarded-Proto` or `Forwarded` at all |
| `X-User-Id: root`, `X-User-Roles: ROLE_ADMIN` with a user token | `X-User-Id: user-7`, `X-User-Roles: ROLE_USER` (from the token) |
| `Proxy-Authorization`, `Keep-Alive`, `TE`, `Upgrade` (one request each) | all four answered 200 and none reached the service |

Things found while building it, which changed the design:
- The first version of the test used `WebTestClient` and the service never saw a client address. The request class at the gateway was `MockServerHttpRequest`: in these tests `WebTestClient` is bound to the application in mock mode, which has no TCP connection, so no remote address. Network behavior therefore has to be tested with a real client; the earlier gateway tests that use `WebTestClient` do not exercise it.
- Enabling `spring.cloud.gateway.server.webflux.x-forwarded.enabled` changed nothing: the bean `XForwardedHeadersFilter` exists only if `trusted-proxies` is set (a condition on the auto-configuration, read from the class files).
- Setting `trusted-proxies: "^$"` (trust nobody) made the gateway add a `Forwarded` header, but it also stopped dropping the client's headers: a forged `X-Forwarded-Host: evil.example.test` and `X-Forwarded-Proto: https` reached the service, and `X-Forwarded-For` was still empty. That is worse than the default, so it was not used.
- A first hop-by-hop test sent `Proxy-Authorization`, `Keep-Alive`, `TE` and `Upgrade: websocket` in one request and got a 503; sent one at a time (with `Upgrade: h2c`) all four got 200. The cause of the 503 was not investigated.

The rest of the live checks are in docs/STATUS.md.

## 4. Reproduction

In `apps/api-gateway`:

```bash
./mvnw --batch-mode test -Dtest=ReverseProxyGatewayTest,ClientAddressFilterTest
```

## 5. Failure modes and edge cases

- Behind a cloud load balancer or a CDN the TCP peer is that proxy; the real client address then arrives in the proxy's `X-Forwarded-For`, which the gateway may trust only from the proxy's own addresses. That needs `trusted-proxies` set to those addresses and a test that a forged header from anywhere else is still dropped.
- Services must read the client from `X-Client-Ip`, not from `X-Forwarded-For`, and must not be reachable except through the gateway; otherwise the header can be forged by calling the service directly. In the compose stack only the gateway publishes a port.
- TLS is not terminated by the gateway in this lab (plain HTTP inside the Docker network); a proxy that terminates TLS must tell the service the original scheme.
- Large bodies, slow clients and WebSocket upgrades have their own limits and timeouts at a proxy (unit 5.6 covers the response timeout); not tested here.
- A response header such as `Server` leaks the stack: the gateway removes it (unit 5.7).

## 6. Interview questions

- What does the service behind a reverse proxy see as the client, and how can it learn the real one?
- Why is `X-Forwarded-For` not trustworthy by itself, and how does a proxy make it trustworthy?
- What are hop-by-hop headers and why does a proxy drop them?
- Why can a gateway test using a mock client miss a networking bug?
