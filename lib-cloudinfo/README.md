# lib-cloudinfo

Java HTTP client for the [Cloudinfo API](https://cloudinfo.seqera.io), providing cloud provider information including regions and compute products with pricing.

## Installation

### Gradle

Add the dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-cloudinfo:1.0.0'
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
| `CloudProduct` | Compute instance type with CPU, memory, GPU, and pricing |
| `CloudPrice` | Spot price for a specific zone |
| `CloudResponse` | API response wrapper containing products |
| `ProductAttributes` | Additional product attributes |

## Dependencies

- `lib-httpx`: HTTP client with retry support
- `lib-serde-jackson`: JSON serialization/deserialization
