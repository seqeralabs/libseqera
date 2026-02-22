# lib-util-string

General-purpose string utilities shared across Seqera projects. Pure Java, no external dependencies.

## Usage

Add the dependency to your `build.gradle`:

```groovy
implementation 'io.seqera:lib-util-string:<version>'
```

All methods are static on `io.seqera.util.string.StringUtils`.

## API

### Emptiness & case checks

| Method | Description |
|--------|-------------|
| `isEmpty(String)` | Returns `true` if null or empty |
| `isBlank(String)` | Returns `true` if null, empty, or whitespace-only |
| `isNotEmpty(String)` | Negation of `isEmpty` |
| `isNotBlank(String)` | Negation of `isBlank` |
| `isLowerCase(String)` | `true` if no uppercase alphabetic characters |
| `isUpperCase(String)` | `true` if no lowercase alphabetic characters |

### Pattern matching

| Method | Description |
|--------|-------------|
| `like(String, String)` | Case-insensitive glob match (`*` wildcard) |
| `globToRegex(String)` | Converts a glob pattern (`*`, `?`) to a compiled `Pattern` |

### Redaction

| Method | Description |
|--------|-------------|
| `redact(Object)` | Masks a value for safe logging (first 3 chars shown for 10+ char strings) |
| `redactUrlPassword(Object)` | Redacts credentials embedded in a URL |
| `stripSecrets(Map)` | Recursively redacts sensitive map entries by key name |

### URL & path utilities

| Method | Description |
|--------|-------------|
| `getUrlProtocol(String)` | Extracts the protocol (e.g. `http`, `s3`) from a URL string |
| `isUrlPath(String)` | Checks whether a string has a URL protocol |
| `baseUrl(String)` | Extracts protocol + host (+ optional port) from a URL |
| `isSameUri(String, String)` | Compares two URIs ignoring trailing slashes and case |
| `pathConcat(String, String)` | Joins a base path and relative path, handling slashes correctly |
| `hasSamePathPrefix(String, String)` | Checks if a path starts with a given prefix at segment boundaries |

### Email validation

| Method | Description |
|--------|-------------|
| `isEmail(String)` | Validates an email address using Angular/WHATWG rules |
| `isTrustedEmail(List, String)` | Checks an email against an allow-list with glob support |
