# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

`nf-metalog` is a Nextflow plugin that monitors workflow execution by grouping tasks by a metadata key (typically
`meta.id`), persisting execution data to SQLite or memory, and generating interactive HTML + CSV reports.

## Build Commands

```bash
make assemble     # Build the plugin
make test         # Run unit tests (Spock)
make clean        # Clean build artifacts and Nextflow work dirs
make install      # Install to local Nextflow plugins directory
make release      # Publish to plugin registry (./gradlew releasePlugin)
```

Run a single test class:

```bash
./gradlew test --tests "ebi.plugin.SqliteStorageBackendTest"
```

Test with a real Nextflow workflow (after `make install`):

```bash
cd test-pipeline && nextflow run main.nf -plugins nf-metalog@0.1.0
```

## Architecture

### Data Flow

```
Nextflow task events
  → MetalogFactory (TraceObserverFactoryV2)
  → MetalogObserver (intercepts lifecycle hooks)
  → extracts group ID from task inputs (first tuple element = meta map)
  → StorageBackend (SqliteStorageBackend | MemoryStorageBackend)
  → Report.generate() on workflow complete → CSV + HTML
```

### Key Components

**Plugin wiring** — `MetalogPlugin` is the entry point; `MetalogFactory` creates the observer and reads `MetalogConfig`
from Nextflow's `metalog {}` config scope.

**MetalogObserver** — Core event handler. On `onTaskComplete` / `onTaskCached` it extracts the group key by looking for
`valueinparam<0:0>` or `param<0:0>` in task inputs (first tuple element = `meta` map). Falls back gracefully if the key
is absent.

**SqliteStorageBackend** — Primary storage. Uses a dedicated worker thread + `BlockingQueue` to serialize writes and
avoid lock contention. WAL mode enabled; upserts via `ON CONFLICT(task_id) DO UPDATE` so status transitions (submit →
complete) update the same row. Queue backpressure is logged as a warning above 100 events.

**MemoryStorageBackend** — Alternative in-memory backend; data is lost after the workflow run. Useful for testing or
ephemeral runs.

**Report** — Generates output on `onFlowComplete`. Loads the HTML template and JS/CSS assets from classpath resources (
`src/main/resources/`). Uses `GStringTemplateEngine`. Prevents accidental file overwrite unless
`report.overwrite = true`.

### Configuration (in `nextflow.config`)

```groovy
metalog {
    enabled = true
    storageBackend = 'memory'   // 'memory' (default) or 'sqlite'
    groupKey = 'id'             // direct key lookup in the meta Map (not a dot-path)
    sqlite {
        file = 'metalog.db'
    }
    report {
        csvFile = 'metalog.csv'
        htmlFile = 'metalog.html'
        overwrite = false
    }
}
```

### Report Frontend

The HTML report (`src/main/resources/nf-metalog_report.html`) bundles Bootstrap, DataTables, and Plotly. Custom logic
lives in `assets/nf-metalog_report.js`. All assets are embedded in the final HTML at report-generation time.

### Testing

Tests use the Spock framework. `TestDatabaseUtils.groovy` provides shared helpers. The `test-pipeline/` directory
contains a minimal Nextflow workflow for end-to-end validation.

## Development Notes

- Source files use `@CompileStatic` for static compilation.
- Logging is via `@Slf4j` (SLF4J).
- Plugin extension points are declared in `build.gradle` under `nextflowPlugin { extensionPoints = [...] }` — update
  there when adding new factory or extension classes.
