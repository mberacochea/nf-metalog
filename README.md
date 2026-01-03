# nf-metalog plugin

The nf-metalog Nextflow plugin implements a custom observer. This plugin can log the workflow task events into a database
(SQLite is the only one supported at the moment), using the meta Map to group the tasks and their metadata.
If also generates an HTML report, very similar to Nextflow's Trace Report, but with focus on "samples" taken from the metamap.
The objective is to be able to monitor your workflow execution by following the different samples
through the pipeline.

## Installation

This is pending, I haven't published the plugin in the Nextflow registry.

It is possible to compile the plugin and install it manually, look at the Building instructions.

## Usage

Once this is published in the registry, Nextflow will handle the installation automatically.

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

1. **SQLite Database**: Contains all task events grouped by sample ID
2. **CSV Report**: Tabular data of all workflow execution events
3. **HTML Report**: Interactive visualization of the workflow execution

#### Basic Configuration

The minimal configuration requires no additional setup beyond enabling the plugin:

```groovy
metalog {
    enabled = true
}
```

#### Advanced Configuration

For more control over the plugin behavior:

```groovy
metalog {
    enabled = true
    storageBackend = 'sqlite'  // Use SQLite instead of the in-memory storage 
    groupKey = 'meta.id'  // Key to use for grouping tasks (default: 'meta.id')
    sqlite {
        file = 'custom_metalog.db'  // Custom database file name (SQLite only)
    }
    report {
        csvFile = 'workflow_report.csv'  // Custom CSV output file
        htmlFile = 'workflow_report.html'  // Custom HTML output file
        override = false  // Prevent overwriting existing files (default: false)
    }
}
```

Storage Backend Options:

- **`memory`**: (default) In-memory storage, data is lost when workflow completes
- **`sqlite`**: Persistent storage using SQLite database

Reports overwriting:

By default, the plugin will not overwrite existing files. To enable overwriting:

```groovy
metalog {
    enabled = true
    report {
        csvFile = 'metalog.csv'
        htmlFile = 'metalog.html'
        override = true  // Allow overwriting existing files
    }
}
```

### Plugin Developers

#### SQLite Configuration and Limitations

The plugin uses SQLite as its database backend with the following settings:

- **WAL Mode**: Write-Ahead Logging is enabled for better concurrency
- **Busy Timeout**: 30 seconds to handle database locks gracefully
- **Thread Safety**: Uses a worker thread with a queue for database operations
- **Connection Pooling**: Single connection with proper lifecycle management

**SQLite Limitations to Consider:**

1. **Concurrency**: SQLite has limited write concurrency. The plugin uses a worker thread to serialize writes.
2. **Performance**: For very large workflows (10,000+ tasks), consider:
   - Increasing the busy timeout
   - Using a more performant database in future versions
3. **File Size**: SQLite databases are limited to ~140TB, which is sufficient for most workflows.
4. **Network Access**: SQLite is file-based and not designed for network access.

#### Adding New Storage Backends

To add a new storage backend:

1. Implement StorageBackend Interface: Create a new class that implements `StorageBackend`
2. Add Configuration Option: Add the backend name to the `storageBackend` configuration
3. Update MetalogObserver: Add logic to instantiate the new backend in the constructor

## Building

## Building

To build the plugin:
```bash
make assemble
```

## Testing with Nextflow

The plugin can be tested without a local Nextflow installation:

1. Build and install the plugin to your local Nextflow installation: `make install`
2. Run a pipeline with the plugin: `nextflow run hello -plugins nf-metalog@0.1.0`

## Credits

This project includes adaptations of code from [Nextflow](https://github.com/nextflow-io/nextflow):

- **Original Code**: 
- **Copyright**: 2013-2025, Seqera Labs  
- **License**: Apache License, Version 2.0
- **Adaptations**: Modified for the per-sample analysis focus of this plugin

## Publishing

Plugins can be published to a central plugin registry to make them accessible to the Nextflow community. 

Follow these steps to publish the plugin to the Nextflow Plugin Registry:

1. Create a file named `$HOME/.gradle/gradle.properties`, where $HOME is your home directory. Add the following properties:

    * `npr.apiKey`: Your Nextflow Plugin Registry access token.

2. Use the following command to package and create a release for your plugin on GitHub: `make release`.


