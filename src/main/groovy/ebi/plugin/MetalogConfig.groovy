/*
 * Copyright 2025, Martin Beracochea
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ebi.plugin

import groovy.transform.CompileStatic
import nextflow.config.spec.ConfigOption
import nextflow.config.spec.ConfigScope
import nextflow.config.spec.ScopeName
import nextflow.script.dsl.Description

@CompileStatic
@ScopeName('metalog')
@Description('''
    The `metalog` scope allows you to configure the `nf-metalog` plugin.
''')
class MetalogConfig implements ConfigScope {

    @ConfigOption
    @Description('''
        Enable or disable the metalog plugin. Default: true
    ''')
    final Boolean enabled

    @ConfigOption
    @Description('''
        The meta Map key to be used to group the data (meta.id by default)
    ''')
    final String groupKey

    @ConfigOption
    @Description('''
        Storage backend to use for metalog data. Options: 'sqlite' or 'memory'. Default: 'sqlite'
    ''')
    final String storageBackend

    @ConfigOption
    @Description('''
        SQLite database configuration.
    ''')
    final SqliteConfig sqlite

    @ConfigOption
    @Description('''
        Configuration for the metalog report generation.
    ''')
    final ReportConfig report

    // no-arg constructor is required to enable validation of config options
    MetalogConfig() {
        this.enabled = true
        this.groupKey = "meta.id"
        this.storageBackend = "sqlite"
        this.sqlite = new SqliteConfig()
        this.report = new ReportConfig()
    }

    MetalogConfig(Map opts) {
        this.enabled = opts.enabled != null ? opts.enabled as Boolean : true
        this.storageBackend = opts.storageBackend != null ? opts.storageBackend : "sqlite"
        this.sqlite = opts.sqlite ? new SqliteConfig(opts.sqlite as Map) : new SqliteConfig()
        this.groupKey = opts.groupKey != null ? opts.groupKey : "meta.id"
        this.report = opts.report ? new ReportConfig(opts.report as Map) : new ReportConfig()
    }

    @CompileStatic
    @Description('''
        SQLite database configuration options.
    ''')
    static class SqliteConfig implements ConfigScope {

        @ConfigOption
        @Description('''
            SQLite database file name. Default: metalog.db
        ''')
        final String file

        SqliteConfig() {
            this.file = 'metalog.db'
        }

        SqliteConfig(Map opts) {
            this.file = opts?.file as String ?: 'metalog.db'
        }
    }

    @CompileStatic
    @Description('''
        Report generation configuration options.
    ''')
    static class ReportConfig implements ConfigScope {

        @ConfigOption
        @Description('''
            CSV file name for the metalog report. Default: metalog.csv
        ''')
        final String csvFile

        @ConfigOption
        @Description('''
            HTML file name for the metalog report. Default: metalog.html
        ''')
        final String htmlFile

        @ConfigOption
        @Description('''
            Overwrite existing files if they already exist. Default: false
        ''')
        final Boolean overwrite

        ReportConfig() {
            this.csvFile = 'metalog.csv'
            this.htmlFile = 'metalog.html'
            this.overwrite = false
        }

        ReportConfig(Map opts) {
            this.csvFile = opts?.csvFile as String ?: 'metalog.csv'
            this.htmlFile = opts?.htmlFile as String ?: 'metalog.html'
            this.overwrite = opts?.override != null ? opts.override as boolean : false
        }
    }
}
