# wave-utils

Utility classes for Wave container operations, file handling, and template processing.

## Usage

Common utilities for Docker operations and file management:

```groovy

// Template rendering
def template = new TemplateRenderer()
def dockerfile = template.render(templatePath, [
    baseImage: 'ubuntu:22.04',
    packages: ['curl', 'wget']
])
```

## Testing

```bash
./gradlew :wave-utils:test
```
