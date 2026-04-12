# nf-metalog plugin

**[mberacochea.github.io/nf-metalog](https://mberacochea.github.io/nf-metalog/)**

The nf-metalog Nextflow plugin implements a custom observer. This plugin logs workflow task events using the meta Map to
group tasks and their metadata, with support for in-memory and SQLite storage backends.
It generates an HTML report, very similar to Nextflow's Trace Report, but with focus on "samples" taken from the meta
map.
The objective is to be able to monitor your workflow execution by following the different samples
through the pipeline.

## Installation

Add the plugin to your Nextflow configuration:

```groovy
plugins {
   id "nf-metalog@0.1.0"
}
```

## Usage

```groovy
plugins {
    id "nf-metalog@0.1.0"
}

metalog {
    enabled = true
    groupKey = 'id'
    report {
        csvFile = 'metalog.csv'
        htmlFile = 'metalog.html'
    }
}
```

### Plugin Users

The nf-metalog plugin is designed to help users monitor workflow execution by tracking different samples through the pipeline. The plugin automatically generates:

1. CSV Report: Tabular data of all workflow execution events
2. HTML Report: Interactive visualization of the workflow execution
3. SQLite Database: Contains all task events grouped by sample ID (only when `storageBackend = 'sqlite'`)

#### Basic Configuration

The minimal configuration requires no additional setup beyond enabling the plugin:

```groovy
metalog {
    enabled = true
}
```

With no path settings specified, reports are written to the launch directory. When using the `sqlite` backend, the
database file is also written there.

#### Advanced Configuration

For more control over the plugin behaviour:

```groovy
metalog {
    enabled = true
    storageBackend = 'memory'  // 'memory' (default) or 'sqlite' for persistent storage
    groupKey = 'id'            // Key within the meta Map used to group tasks by sample (default: 'id')
    sqlite {
       file = 'metalog.db'      // Custom database file name or full path (default: metalog.db)
    }
    report {
       csvFile = 'metalog.csv'   // Custom CSV output file
       htmlFile = 'metalog.html' // Custom HTML output file
       overwrite = false        // Prevent overwriting existing files (default: false)
    }
}
```

The `sqlite.file` option accepts either a file name (resolved relative to the launch directory) or an absolute path, so
you can use `params.outdir`:

```groovy
metalog {
   sqlite {
      file = "${params.outdir}/metalog.db"
   }
   report {
      csvFile = "${params.outdir}/metalog.csv"
      htmlFile = "${params.outdir}/metalog.html"
    }
}
```

Storage Backend Options:

- `memory`: (default) In-memory storage — fast, no files written, data is lost when the workflow completes
- `sqlite`: Persistent storage using SQLite — data survives workflow completion and can be queried later

Reports overwriting:

By default, the plugin will not overwrite existing files. To enable overwriting:

```groovy
metalog {
    report {
       csvFile = 'metalog.csv'
       htmlFile = 'metalog.html'
       overwrite = true
    }
}
```

### Plugin Developers

#### SQLite Configuration and Limitations

The plugin uses SQLite as its database backend with the following settings:

- WAL Mode: Write-Ahead Logging is enabled for better concurrency
- Busy Timeout: 10 seconds to handle database locks gracefully
- Thread Safety: Uses a worker thread with a queue for database operations
- Connection Pooling: Single connection with proper lifecycle management

SQLite Limitations to Consider:

1. Concurrency: SQLite has limited write concurrency. The plugin uses a worker thread to serialize writes.
2. Performance: For very large workflows (10,000+ tasks), consider increasing the busy timeout.

#### Adding New Storage Backends

To add a new storage backend:

1. Implement StorageBackend Interface: Create a new class that implements `StorageBackend`
2. Add Configuration Option: Add the backend name to the `storageBackend` configuration
3. Update MetalogObserver: Add logic to instantiate the new backend in the constructor

## Building

To build the plugin:
```bash
make assemble
```

## Testing with Nextflow

1. Build and install the plugin to your local Nextflow installation: `make install`
2. Run a pipeline with the plugin: `nextflow run hello -plugins nf-metalog@0.1.0`

## Credits

This project includes adaptations of code from [Nextflow](https://github.com/nextflow-io/nextflow):

- Copyright: 2013-2025, Seqera Labs
- License: Apache License, Version 2.0
- Adaptations: Modified for the per-sample analysis focus of this plugin

## Publishing

Plugins can be published to a central plugin registry to make them accessible to the Nextflow community.

Follow these steps to publish the plugin to the Nextflow Plugin Registry:

1. Create a file named `$HOME/.gradle/gradle.properties`, where $HOME is your home directory. Add the following properties:

    * `npr.apiKey`: Your Nextflow Plugin Registry access token.

2. Use the following command to package and create a release for your plugin on GitHub: `make release`.
