# `HxClient.Builder.config()` — lossless copy of `HxConfig`

- **Status:** Implemented — fixes [#113](https://github.com/seqeralabs/libseqera/issues/113)
- **Date:** 2026-08-11
- **Module:** `lib-httpx` (`2.4.0` → `2.5.0`)
- **Why:** `config(HxConfig)` hand-transcribes a built config back into an `HxConfig.Builder`.
  The transcription lists 15 of the 18 fields `HxConfig.Builder.build()` assigns, so
  `retryCondition`, `tokenRefreshTimeout` and `refreshCookiePolicy` are silently replaced by
  defaults. Two of them have no other route onto an `HxClient`, making them unsettable.

## 1. Problem

`HxClient.Builder` holds an `HxConfig.Builder`, not an `HxConfig` — every builder setter delegates
to it and `build()` calls `configBuilder.build()`. `config(HxConfig)` therefore has to convert a
*built* config back into a *builder*, which it does by listing fields (`HxClient.java:1070`):

```java
public Builder config(HxConfig config) {
    this.configBuilder = HxConfig.newBuilder()
            .retryConfig(config)                              // delay, maxDelay, maxAttempts, jitter, multiplier
            .bearerToken(config.getJwtToken())
            .refreshToken(config.getRefreshToken())
            .refreshTokenUrl(config.getRefreshTokenUrl())
            .basicAuth(config.getBasicAuthToken())
            .retryStatusCodes(config.getRetryStatusCodes())
            .wwwAuthentication(config.isWwwAuthenticateEnabled())
            .wwwAuthenticationCallback(config.getAuthenticationCallback());
    this.proxySelector = config.getProxySelector();
    this.proxyAuthenticator = config.getProxyAuthenticator();
    return this;
}
```

`retryConfig(Retryable.Config)` copies only the five numeric retry fields (`HxConfig.java:673`), so
everything else must be listed explicitly — and the list has drifted from the 18 assignments in
`HxConfig.Builder.build()` (`HxConfig.java:704-722`).

| Dropped field | Reverts to | On `HxConfig.Builder` | On `HxClient.Builder` |
|---|---|---|---|
| `retryCondition` | `t instanceof IOException` | yes (`HxConfig.java:326`) | **no delegate** |
| `tokenRefreshTimeout` | 30s | yes (`HxConfig.java:427`) | **no delegate** |
| `refreshCookiePolicy` | `null` | yes (`HxConfig.java:622`) | yes, but only *after* `config()` — which replaces `configBuilder` |

**The root cause is the hand-written field list, not those three fields.** Patching the list fixes
today's drift and leaves the mechanism that produced it in place, so the design below removes the
transcription instead.

Secondary defect, same issue: `shouldRetryOnException(Throwable)` (`HxClient.java:620`) is
`protected` and documented as the retry-on-exception decision point, but nothing in production
calls it — `sendWithRetry` and `sendWithRetryAsync` pass `config.getRetryCondition()` straight to
`Retryable` (`HxClient.java:479,552`). Its only callers are `HxClientTest.groovy:42-45`, which pass
and make the extension point look wired up.

## 2. Blast radius — measured

Fixing `config()` is not behaviour-neutral in principle: a caller who sets `retryCondition` today
has it ignored and would suddenly have it honoured. Surveying every consumer available locally
(`platform`, `nextflow`, `sched`, `wave`; `.claude/worktrees` excluded):

- **No consumer calls `HxClient.Builder.config(HxConfig)`.** Its only callers are this repo's own
  tests — `HxClientRetryIntegrationTest`, `HxClientJwtIntegrationTest`, `HxClientBasicAuthTest`,
  `HxClientCombinedIntegrationTest`, `HxClientWwwAuthIntegrationTest`, `HxClientTest` — none of
  which set any of the three dropped fields.
- **No consumer sets `retryCondition` or `tokenRefreshTimeout` on an `HxConfig`.** The
  `retryCondition` matches in `platform` (`AgentChannelRedisImpl`, `RedisEventsTopic`) and
  `nextflow` (`ThrottlingExecutor`, `GridTaskHandler`) are on `Retryable` and unrelated types.
- **Every `refreshCookiePolicy` caller goes through the `HxClient.Builder` delegate** —
  `sched-client`, `sched-app`, `platform-client`, `nf-wave`, `nf-tower` — a path this change does
  not touch.

The only place behaviour changes is `seqeralabs/ffq-java-sdk`, where the change *is* the fix.
This removes the argument for the issue's option B (delegate-only, leave `config()` lossy).

## 3. Design

### 3.1 Copy factory on `HxConfig`

Add an overload beside `newBuilder()`, implemented next to `build()` so the copy-in and copy-out
field lists are adjacent and drift is visible in review:

```java
/**
 * Creates a builder pre-populated with every setting of the given configuration.
 * Later builder calls override the copied values.
 */
public static Builder newBuilder(HxConfig config) {
    java.util.Objects.requireNonNull(config, "config cannot be null");
    final Builder b = new Builder();
    b.delay = config.delay;
    b.maxDelay = config.maxDelay;
    b.maxAttempts = config.maxAttempts;
    b.jitter = config.jitter;
    b.multiplier = config.multiplier;
    b.retryCondition = config.retryCondition;
    b.retryStatusCodes = config.retryStatusCodes;
    b.bearerToken = config.jwtToken;
    b.refreshToken = config.refreshToken;
    b.refreshTokenUrl = config.refreshTokenUrl;
    b.tokenRefreshTimeout = config.tokenRefreshTimeout;
    b.basicAuthToken = config.basicAuthToken;
    b.wwwAuthenticationEnabled = config.wwwAuthenticateEnabled;
    b.wwwAuthenticationCallback = config.authenticationCallback;
    b.refreshCookiePolicy = config.refreshCookiePolicy;
    b.proxySelector = config.proxySelector;
    b.proxyAuthenticator = config.proxyAuthenticator;
    return b;
}
```

Direct field access (legal: `Builder` is nested in `HxConfig`) rather than the fluent setters,
because three builder field names differ from their config counterparts — `bearerToken`/`jwtToken`,
`wwwAuthenticationEnabled`/`wwwAuthenticateEnabled`,
`wwwAuthenticationCallback`/`authenticationCallback` — and going through setters would re-introduce
per-field translation. Assigning fields directly also makes the copy list a line-for-line mirror of
`build()`'s assignment list, which is the property that makes a missing field visible in review.

Mechanically this lands as a private `copyFrom(HxConfig)` on `Builder` placed immediately above
`build()` (the adjacency that makes drift reviewable), with the public static factory delegating to
it. A private helper rather than a `Builder(HxConfig)` constructor, because declaring any
constructor would remove the implicit no-arg one that `newBuilder()` and existing callers rely on.

### 3.2 `config()` collapses to the copy

```java
/**
 * Copies every setting of the given configuration into this builder.
 *
 * <p>Subsequent builder calls override the copied values, and later mutations of the
 * supplied instance are not observed.
 */
public Builder config(HxConfig config) {
    java.util.Objects.requireNonNull(config, "config cannot be null");
    this.configBuilder = HxConfig.newBuilder(config);
    this.proxySelector = config.getProxySelector();
    this.proxyAuthenticator = config.getProxyAuthenticator();
    return this;
}
```

The two proxy assignments stay. `build()` writes `this.proxySelector` / `this.proxyAuthenticator`
back onto `configBuilder` on the no-explicit-client path (`HxClient.java:1436-1437`), so dropping
them would clobber the copied values with `null`. Both routes now agree on the same instances.

The javadoc replaces the current claim that "the provided configuration will be used directly",
which is what led the reporter to trust it. Note the `NullPointerException` this documents is
already the *de facto* behaviour — today `config(null)` NPEs inside `config.getJwtToken()`.

**One intended side effect.** With `config(cfg)` **and** an explicit `httpClient(...)`, the config's
proxy selector/authenticator now reach the built `HxConfig`, where today they are dropped (that
branch returns before the two `configBuilder.proxySelector/proxyAuthenticator` calls). The explicit
`HttpClient` is still used verbatim — it is never reconfigured — but the internal token-refresh and
anonymous-Bearer clients, which read the proxy settings off `HxConfig` (see `HxTokenManager`), will
now inherit them. This is more faithful to the supplied config; `build()`'s javadoc
(`HxClient.java:1420-1424`) must be amended to say so, and it goes in the changelog.

### 3.3 Wire `shouldRetryOnException`

Make the documented hook the actual predicate, with the config as its default:

```java
// sendWithRetry and sendWithRetryAsync
final Retryable<HttpResponse<T>> retry = Retryable.<HttpResponse<T>>of(config)
        .retryCondition(this::shouldRetryOnException)
        .retryIf(this::shouldRetryOnResponse)
        ...

/**
 * Determines whether to retry a request based on the exception that occurred.
 *
 * <p>Delegates to the configured {@link HxConfig#getRetryCondition()}, which defaults to
 * retrying on {@link IOException}. Override to decide independently of the configuration.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
protected boolean shouldRetryOnException(Throwable throwable) {
    final Predicate raw = config.getRetryCondition();
    return raw != null ? raw.test(throwable) : throwable instanceof IOException;
}
```

- Behaviour is unchanged for a default config: the default condition *is*
  `t instanceof IOException` (`HxConfig.java:72`), so the hardcoded rule is not lost, only
  de-duplicated.
- `HxClientTest.groovy:42-45` keeps passing unmodified.
- Subclassing becomes a real route for customising retry behaviour, as its javadoc always implied.
- The raw-`Predicate` cast mirrors `Retryable.toChecked`, the existing
  idiom in this codebase for the `Predicate<? extends Throwable>` variance problem
  (`Retryable.java:413-420`). No cast is
  needed at the `retryCondition(...)` call site: for a wildcard-parameterised target type the
  compiler infers the ground type `Predicate<Throwable>`, which the method reference matches.
- The `log.debug("Retrying on IOException: …")` line disappears; the `onRetry` handler already
  logs every retry with the failure message.

## 4. Test plan

### 4.1 Reflective round-trip guard — `HxConfigRoundTripTest.groovy` (new)

The regression test the issue asks for, generalised so it also catches the *next* field:

1. Two fixtures, because `HxConfig.Builder.build()` rejects a bearer token and a basic-auth token
   together (`HxConfig.java:725-727`): fixture **A** sets every field to a non-default value except
   `basicAuthToken`; fixture **B** sets `basicAuthToken` (plus a couple of neighbours).
2. **Coverage assertion.** For each non-static, non-synthetic declared field of `HxConfig`, assert
   the value in fixture A differs from a default `HxConfig.newBuilder().build()` — with
   `basicAuthToken` asserted against fixture B instead. A field added later is not covered by any
   fixture, still holds its default, and fails here until someone covers it. The guard maintains
   itself rather than relying on a human remembering two lists.
3. **Round-trip assertion.** For both fixtures, and for both `build()` paths — with an explicit
   `httpClient(Mock(HttpClient))` and without — assert every declared field of
   `HxClient.newBuilder().config(cfg).build().config` equals the fixture's. Reference equality is
   the right check for `retryCondition`, `authenticationCallback`, `refreshCookiePolicy`,
   `proxySelector` and `proxyAuthenticator`; `equals` for the durations, numerics and
   `retryStatusCodes`.

Reflection reads private fields via `setAccessible(true)`; filter `field.synthetic` and static
modifiers so `DEFAULT_RETRY_COND` and any coverage instrumentation are skipped.

### 4.2 Behavioural test — the reporter's scenario

A WireMock-backed test mirroring the issue's reproduction: an `HxConfig` with
`maxAttempts(3).retryCondition(t -> false)`, driven through `HxClient.newBuilder().config(config)`
against an endpoint that fails with an `IOException`, asserting the upstream is contacted **once**.
This is the assertion that would have caught the bug; §4.1 alone proves the field survives the copy,
not that it reaches the retry policy.

### 4.3 Extension-point test

A subclass overriding `shouldRetryOnException` to return `false`, asserting a single upstream call —
proving §3.3 wired the hook rather than merely relocating the default.

### 4.4 Regression surface

`./gradlew :lib-httpx:test` — the six existing `config()`-using suites are the regression net for
the copy change, and `HxClientTest` for the retry-path change.

## 5. Scope: what shipped alongside, and what did not

**Changing the default `retryCondition` was written up here as out of scope, then shipped in the
same release.** It was implemented on this branch via the stacked PR
[#116](https://github.com/seqeralabs/libseqera/pull/116) (`460af80`) and released together in
2.5.0, so this section records it rather than deferring it.

The rule lives in `HxConfig.defaultRetryCondition(Throwable)`: retry any `IOException`, except an
`HttpTimeoutException` that is not an `HttpConnectTimeoutException`. A connect timeout is raised
before the request is sent, so nothing ran and re-sending is safe; a post-send timeout is ambiguous
— the server may have received the request and still be working on it — so re-sending risks running
a non-idempotent operation twice and only pushes the worst case out to `maxAttempts × timeout`.
`HxClient.shouldRetryOnException` uses the same method as its `null`-condition fallback, so the
documented default and the fallback cannot drift. The method is public, so callers can compose with
it. This matches OkHttp (a socket timeout is recoverable only before the request is sent) and Apache
HttpClient 5 (the `InterruptedIOException` family is non-retriable), though scoped more narrowly than
Apache's — see the README section "Which failures are retried".

**Still out of scope:**

- **A `retryCondition` / `tokenRefreshTimeout` delegate on `HxClient.Builder`** (the issue's option
  B). Once `config()` is lossless both fields are reachable via `HxConfig.newBuilder()`, so the
  delegates are convenience only, and each is another line that can drift. Add on demand.
- **Gating retries on idempotency, and an overall retry budget** — tracked in
  [#115](https://github.com/seqeralabs/libseqera/issues/115). Retries are decided purely on
  exception type and response status, so a `POST` is retried like a `GET`, and no call has a
  wall-clock ceiling.

## 6. Release

| Step | Detail |
|---|---|
| `lib-httpx/VERSION` | `2.4.0` → `2.5.0` — behaviour fix plus new public API (`HxConfig.newBuilder(HxConfig)`) |
| `lib-httpx/changelog.txt` | new `2.5.0` section: the three recovered fields, the new overload, the token-refresh-client proxy delta from §3.2, `shouldRetryOnException` now wired |
| `lib-httpx/README.md:13` | bump the dependency coordinate |
| PR | `[release]` in the title; merging to `master` publishes |
