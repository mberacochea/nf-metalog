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
        SQLite database configuration.
    ''')
    final SqliteConfig sqlite

    // no-arg constructor is required to enable validation of config options
    MetalogConfig() {
    }

    MetalogConfig(Map opts) {
        this.enabled = opts.enabled != null ? opts.enabled as Boolean : true
        this.sqlite = opts.sqlite ? new SqliteConfig(opts.sqlite as Map) : new SqliteConfig()
        this.groupKey = opts.groupKey != null ? opts.groupKey : "meta.id"
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
        }

        SqliteConfig(Map opts) {
            this.file = opts?.file as String ?: 'metalog.db'
        }
    }
}
