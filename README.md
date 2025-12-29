# nf-metalog plugin

The nf-metalog Nextflow plugin implements a custom observer. This plugin logs the workflow task events into a database
(SQLite is the only one supported at the moment), using the meta Map to group the tasks and their metadata.
The objective is to be able to monitor your workflow execution by following the different samples
through the pipeline.

There is a companion TUI (textual user interface) to this plugin
called [nf-metalog-ui](https://github.com/mberacochea/nf-metalog-ui).

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
    sqlite {
        file = 'metalog.db'
    }
}
```

## Building

To build the plugin:
```bash
make assemble
```

## Testing with Nextflow

The plugin can be tested without a local Nextflow installation:

1. Build and install the plugin to your local Nextflow installation: `make install`
2. Run a pipeline with the plugin: `nextflow run hello -plugins nf-metalog@0.1.0`

## Publishing

Plugins can be published to a central plugin registry to make them accessible to the Nextflow community. 

Follow these steps to publish the plugin to the Nextflow Plugin Registry:

1. Create a file named `$HOME/.gradle/gradle.properties`, where $HOME is your home directory. Add the following properties:

    * `npr.apiKey`: Your Nextflow Plugin Registry access token.

2. Use the following command to package and create a release for your plugin on GitHub: `make release`.


