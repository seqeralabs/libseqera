# lib-cloudinfo

Java HTTP client for the [Cloudinfo API](https://cloudinfo.seqera.io), providing cloud provider information including regions and compute products with pricing.

## Installation

### Gradle

Add the dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-cloudinfo:2.3.0'
}
```

**Note:** Check the project's `VERSION` file for the current version number.

## Features

- Fetch cloud regions for AWS, GCP, and Azure
- Fetch compute products (instance types) with on-demand and spot pricing, plus per-type disk and GPU facts
- Fetch per-region block-storage (disk) prices
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
- `gpuModels(List<String>)` — keep only products whose canonical `gpuModel` is
  **any** of the given IDs. Serialised as `?gpuModel=nvidia-t4,nvidia-l4`.
- `minGpuCapability(String)` — keep only products whose NVIDIA CUDA compute
  capability is at least this `major.minor` version (`?minGpuCapability=8.0`
  keeps Ampere and newer).
- `cuda(String)` — keep only products whose GPU this CUDA toolkit release can
  target (`?cuda=12.4`).
- `minGpuMemoryGB(Long)` — keep only products with at least this many GB per
  GPU; a fractional GPU counts its share of the card (`?minGpuMemoryGB=40`).
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

### Storage Prices

Fetch the on-demand block-storage (disk) prices of a region. Only `amazon`,
`azure` and `google` serve them. For an enabled provider without storage
prices, or a known region not scraped yet, CloudInfo answers 404 with an
RFC 7807 problem body, which is returned as an empty `Optional` so callers can
fall back to their own prices. Any other 404 (a backend older than 0.25.0, a
wrong endpoint, a proxy), an unknown provider or region (400), and any other
failure throw `CloudInfoException`.

```java
Optional<StoragePrices> prices = client.getStoragePrices("amazon", "us-east-1");

prices.flatMap(p -> p.getVolumes().stream()   // never null
        .filter(v -> "gp3".equals(v.getVolumeType()))
        .findFirst())
    .ifPresent(gp3 -> {
        // gp3: pricePerGBMonth, plus pricePerIopsMonth / pricePerMiBpsMonth
        // above includedIops (3000) / includedThroughputMiBps (125)
    });
```

Each `VolumePrice` carries the capacity, IOPS and throughput prices separately.
A price component that is not billed, or can't be reduced to one rate (e.g.
amazon io2 IOPS), is `null`, not `0`. Volume types billed per disk (Azure
Standard HDD / Standard SSD / Premium SSD) use `tiers` instead, and Google
`local-ssd` adds per-family rates in `familyPricesPerGBMonth`.

### Instance Disk and GPU Facts

From cloudinfo 0.25.0, `CloudProduct` also carries per-instance-type disk and
GPU facts, under each cloud's own field names:

- **Amazon:** `ebsMax/BaselineBandwidthMbps`, `ebsMax/BaselineThroughputMBps`,
  `ebsMax/BaselineIops`, `instanceStoreGB`, `gpuMemoryMiB`
- **Azure:** `quotaFamily`, `ephemeralOSDiskSupported`,
  `supportedEphemeralOSDiskPlacements`, `cachedDiskBytes`, `nvmeDiskSizeInMiB`,
  `maxResourceVolumeMB`, `uncachedDiskIOPS`, `uncachedDiskBytesPerSecond`,
  `hyperVGenerations`, `diskControllerTypes`, `maxDataDiskCount`, `premiumIO`,
  `acceleratedNetworkingEnabled`, `maxNetworkInterfaces`
- **Google:** `acceleratorType`, `gpuMemoryMiB`, `localSsdPartitions`,
  `localSsdGiB`, `attachableLocalSsdCounts`, `maximumPersistentDisks`,
  `maximumPersistentDisksSizeGb`, `isSharedCpu`, `bootDiskTypes`

All are `null` when the provider doesn't report them, and on older backends.
The flags (`ephemeralOSDiskSupported`, `premiumIO`,
`acceleratedNetworkingEnabled`, `isSharedCpu`) are primitive `boolean`s:
CloudInfo omits them when false, so an absent flag reads as `false`.

### GPU Model, Capability and Memory

From cloudinfo 0.30.0, every GPU product carries cloud-neutral GPU facts, so
the same GPU can be asked for on every provider without a per-cloud table:

| Field | Example | Meaning |
|-------|---------|---------|
| `gpuModel` | `nvidia-a10g` | Canonical GPU model ID, the same on every provider. Memory appears in the ID only for models sold in several sizes (`nvidia-a100-40gb`, `nvidia-a100-80gb`). |
| `gpuVendor` | `nvidia` | `nvidia` or `amd`. |
| `gpuCapability` | `8.6` | NVIDIA CUDA compute capability. Compare numerically: `10.0` is above `9.0`. |
| `gpuArch` | `ampere` | Architecture of the capability. |
| `gpuCudaMin` / `gpuCudaMax` | `11.1` / null | CUDA toolkit releases that can target the GPU; `gpuCudaMax` is null while still supported (K80 has `11.8`). |
| `gpuMemoryGB` | `24` | Memory of one whole GPU as the vendor states it. A fractional GPU still reports the whole card. |

All are `null` for CPU-only, TPU, Inferentia and Trainium types, for GPUs not
mapped yet, and on older backends. The CUDA fields are `null` for AMD GPUs and
for parts whose chip is not disclosed. `gpuMemoryMiB` stays the provider's own
total for the instance; from 0.30.0 it is derived as
`gpusPerVm x gpuMemoryGB x 1024` where the provider reports none (Azure).

```java
ProductsQuery query = ProductsQuery.builder()
    .gpuModels(List.of("nvidia-a100-80gb", "nvidia-h100"))
    .cuda("12.4")
    .minGpuMemoryGB(40L)
    .build();

for (CloudProduct p : client.getProducts("google", "us-central1", query)) {
    // p.getGpuModel(), p.getGpuCapability(), p.getGpuMemoryGB(), ...
}
```

The CUDA range is what the toolkit supports, not what the driver of a given
machine image provides.

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
| `CloudProduct` | Compute instance type with CPU, memory, GPU, pricing, machine `family`, per-cloud disk and GPU facts, and per-product capability `features` (lowercase tokens: `ssd`, `gpu`, `arm`, `x86`, `burst`, `hibernation`, `sched`, GPU vendor/model, …) |
| `CloudPrice` | Spot price for a specific zone |
| `CloudResponse` | API response wrapper containing products |
| `FamiliesResponse` | Response wrapper for the `/families` endpoint |
| `StoragePrices` | Response of the `/storage` endpoint: `source`, `scrapingTime` and one `VolumePrice` per volume type |
| `VolumePrice` | Disk prices of one volume type: capacity, IOPS and throughput rates, free baselines, limits, `tiers`, `familyPricesPerGBMonth` |
| `VolumeTier` | One fixed-size, per-disk tier (e.g. Azure `P10`) with size, monthly price, IOPS and throughput |
| `ProductAttributes` | Additional product attributes |
| `ProductsQuery` | Optional filters (`features`, `families`, plus legacy `sched`, `nvme`) for the products endpoint |
| `ErrorResponse` | Parsed API error body (`error`, `validCapabilities`) |

## Dependencies

- `lib-httpx`: HTTP client with retry support
- `lib-serde-jackson`: JSON serialization/deserialization
