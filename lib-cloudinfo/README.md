# lib-cloudinfo

Java HTTP client for the [Cloudinfo API](https://cloudinfo.seqera.io), providing cloud provider information including regions and compute products with pricing.

## Installation

### Gradle

Add the dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-cloudinfo:2.0.0'
}
```

**Note:** Check the project's `VERSION` file for the current version number.

## Features

- Fetch cloud regions for AWS, GCP, and Azure
- Fetch compute products (instance types) with on-demand and spot pricing
- Built on `lib-httpx` with automatic retry and error handling
- Type-safe JSON deserialization using `lib-serde-jackson`

## Usage

### Basic Usage

```java
// Create client with default configuration
CloudInfoClient client = CloudInfoClient.create();

// Fetch regions for a cloud provider
List<CloudRegion> regions = client.getRegions("amazon");
List<String> regionIds = client.getRegionIds("amazon");

// Fetch compute products with pricing
List<CloudProduct> products = client.getProducts("amazon", "us-east-1");
```

### Filtering Products

Pass a `ProductsQuery` to filter the products endpoint. Filters are applied
server-side and compose with AND semantics; providers without matching data
return an empty result for that filter.

- `features(List<String>)` — keep only products whose capability features
  contain **all** the given lowercase tokens. Serialised as `?features=a,b,c`.
  The vocabulary is `ssd`, `gpu`, `arm`, `x86`, `burst`, `hibernation`, `sched`,
  plus GPU vendor tokens (`nvidia`, `amd`, `habana`) and model tokens
  (`a100`, `tesla-a100`, `radeon-pro-v520`, …).
- `families(List<String>)` — keep only products in the given machine families
  or matching the given instance-type names. Serialised as
  `?families=m5d,c5.large`.
- `sched(boolean)` / `nvme(boolean)` — the original boolean filters, retained
  for backward compatibility (`sched=true` ≡ `features=sched`, `nvme=true` ≡
  `features=ssd`). Prefer `features(...)` for new code.

```java
ProductsQuery query = ProductsQuery.builder()
    .features(List.of("gpu", "nvidia"))   // NVIDIA GPU instances only
    .families(List.of("p4d"))             // ...within the p4d family
    .build();

List<CloudProduct> products = client.getProducts("amazon", "us-east-1", query);
```

### Machine Families

Fetch the machine families available for a provider, optionally restricted to
those that have at least one product carrying all the requested capability
features:

```java
// All families for the provider
List<String> families = client.getFamilies("amazon");

// Only families with an NVIDIA GPU product
List<String> gpuFamilies = client.getFamilies("amazon", List.of("gpu", "nvidia"));
```

An unknown or non-lowercase feature token is rejected by the backend with
HTTP 400; the resulting `CloudInfoException` exposes the accepted tokens via
`getValidCapabilities()`.

### Custom Configuration

```java
CloudInfoClient client = CloudInfoClient.builder()
    .endpoint("https://cloudinfo.seqera.io")
    .connectTimeout(Duration.ofSeconds(30))
    .maxRetries(5)
    .build();
```

### Supported Providers

| Provider | ID |
|----------|-----|
| AWS | `amazon` |
| GCP | `google` |
| Azure | `azure` |

## Model Classes

| Class | Description |
|-------|-------------|
| `CloudRegion` | Region identifier and name |
| `CloudProduct` | Compute instance type with CPU, memory, GPU, pricing, machine `family`, and per-product capability `features` (lowercase tokens: `ssd`, `gpu`, `arm`, `x86`, `burst`, `hibernation`, `sched`, GPU vendor/model, …) |
| `CloudPrice` | Spot price for a specific zone |
| `CloudResponse` | API response wrapper containing products |
| `FamiliesResponse` | Response wrapper for the `/families` endpoint |
| `ProductAttributes` | Additional product attributes |
| `ProductsQuery` | Optional filters (`features`, `families`, plus legacy `sched`, `nvme`) for the products endpoint |
| `ErrorResponse` | Parsed API error body (`error`, `validCapabilities`) |

## Dependencies

- `lib-httpx`: HTTP client with retry support
- `lib-serde-jackson`: JSON serialization/deserialization
