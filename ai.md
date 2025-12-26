# ai.md

This file provides guidance to agentic code assistants when working with code in this repository.

## Project Overview

This is a Nextflow plugin (`nf-metalog`) that provides custom workflow execution tracking and extension capabilities. The plugin integrates with Nextflow's execution lifecycle through trace observers and extension points.

**Key Components:**
- **Provider:** EMBL-EBI
- **Version:** 0.1.0
- **Nextflow Compatibility:** 25.10.0+
- **Language:** Groovy
- **Build System:** Gradle with Nextflow plugin support

## Build Commands

```bash
# Build the plugin
make assemble
# or: ./gradlew assemble

# Run unit tests
make test
# or: ./gradlew test

# Clean build artifacts and Nextflow work directories
make clean
# or: ./gradlew clean

# Install plugin to local Nextflow plugins directory
make install
# or: ./gradlew install

# Publish/release the plugin
make release
# or: ./gradlew releasePlugin
```

## Testing

**Unit Tests:**
```bash
./gradlew test
```

**Testing with Nextflow:**
After building and installing locally:
```bash
nextflow run hello -plugins nf-metalog@0.1.0
```

## Architecture

The plugin implements two main extension mechanisms:

### 1. TraceObserver Pattern (MetalogFactory + MetalogObserver)

The plugin uses Nextflow's `TraceObserverFactoryV2` pattern to hook into workflow execution events:

- **MetalogFactory** (`src/main/groovy/ebi/plugin/MetalogFactory.groovy`): Factory that creates observer instances
- **MetalogObserver** (`src/main/groovy/ebi/plugin/MetalogObserver.groovy`): Implements lifecycle hooks:
  - `onFlowBegin()` - Called when workflow starts
  - `onProcessComplete()` - Called when a task completes successfully
  - `onProcessCached()` - Called when a cached task is found
  - `onFilePublish()` - Called when files are published
  - `onFlowError()` - Called when an error occurs
  - `onFlowComplete()` - Called when workflow finishes

The observer has access to `TaskHandler` and `TraceRecord` objects which contain task metadata, inputs, outputs, and execution details.

### Plugin Entry Point

- **MetalogPlugin** (`src/main/groovy/ebi/plugin/MetalogPlugin.groovy`): Main plugin class extending `BasePlugin`

## Configuration

Plugin registration is defined in `build.gradle`:
```groovy
nextflowPlugin {
    className = 'ebi.plugin.MetalogPlugin'
    extensionPoints = [
        'ebi.plugin.MetalogExtension',
        'ebi.plugin.MetalogFactory'
    ]
}
```

## Development Notes

- All source files use Apache License 2.0 (Copyright 2025, Seqera Labs)
- Code uses `@CompileStatic` for static compilation
- Observer uses `@Slf4j` for logging capabilities
- Tests are written using Spock framework (`src/test/groovy/ebi/plugin/`)