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

import spock.lang.Specification

class MetalogConfigTest extends Specification {

    def "test default configuration values"() {
        given:
        def config = new MetalogConfig()

        expect:
        config.enabled == true
        config.groupKey == "id"
        config.sqlite != null
        config.sqlite.file == "metalog.db"
        config.report != null
        config.report.csvFile == "metalog.csv"
        config.report.htmlFile == "metalog.html"
    }

    def "test configuration with custom values"() {
        given:
        def opts = [
            enabled: false,
            groupKey: "custom.id",
            sqlite: [
                file: "custom.db"
            ],
            report: [
                csvFile: "custom.csv",
                htmlFile: "custom.html"
            ]
        ]

        when:
        def config = new MetalogConfig(opts)

        then:
        config.enabled == false
        config.groupKey == "custom.id"
        config.sqlite.file == "custom.db"
        config.report.csvFile == "custom.csv"
        config.report.htmlFile == "custom.html"
    }

    def "test configuration with partial custom values"() {
        given:
        def opts = [
            enabled: true,
            groupKey: "meta.custom",
            sqlite: [
                file: "test.db"
            ]
            // report config not provided, should use defaults
        ]

        when:
        def config = new MetalogConfig(opts)

        then:
        config.enabled == true
        config.groupKey == "meta.custom"
        config.sqlite.file == "test.db"
        config.report.csvFile == "metalog.csv"  // default value
        config.report.htmlFile == "metalog.html" // default value
    }

    def "test configuration with only report custom values"() {
        given:
        def opts = [
            report: [
                csvFile: "output.csv",
                htmlFile: "output.html"
            ]
        ]

        when:
        def config = new MetalogConfig(opts)

        then:
        config.enabled == true  // default value
        config.groupKey == "id"  // default value
        config.sqlite.file == "metalog.db"  // default value
        config.report.csvFile == "output.csv"
        config.report.htmlFile == "output.html"
    }

    def "test SqliteConfig with null opts"() {
        given:
        def config = new MetalogConfig.SqliteConfig(null)

        expect:
        config.file == "metalog.db"
    }

    def "test ReportConfig with null opts"() {
        given:
        def config = new MetalogConfig.ReportConfig(null)

        expect:
        config.csvFile == "metalog.csv"
        config.htmlFile == "metalog.html"
    }

    def "test SqliteConfig with empty opts"() {
        given:
        def config = new MetalogConfig.SqliteConfig([:])

        expect:
        config.file == "metalog.db"
    }

    def "test ReportConfig with empty opts"() {
        given:
        def config = new MetalogConfig.ReportConfig([:])

        expect:
        config.csvFile == "metalog.csv"
        config.htmlFile == "metalog.html"
    }

    def "test default storage backend"() {
        given:
        def config = new MetalogConfig()

        expect:
        config.storageBackend == "memory"
    }

    def "test custom storage backend"() {
        given:
        def opts = [
            storageBackend: "memory"
        ]

        when:
        def config = new MetalogConfig(opts)

        then:
        config.storageBackend == "memory"
    }

    def "test storage backend with invalid value"() {
        given:
        def opts = [
            storageBackend: "invalid"
        ]

        when:
        def config = new MetalogConfig(opts)

        then:
        config.storageBackend == "invalid"  // Should accept any string value
    }

    def "test default overwrite value"() {
        given:
        def config = new MetalogConfig()

        expect:
        config.report.overwrite == false
    }

    def "test custom overwrite value"() {
        given:
        def opts = [
            report: [
                    overwrite: true
            ]
        ]

        when:
        def config = new MetalogConfig(opts)

        then:
        config.report.overwrite == true
    }

    def "test overwrite with explicit false"() {
        given:
        def opts = [
            report: [
                    overwrite: false
            ]
        ]

        when:
        def config = new MetalogConfig(opts)

        then:
        config.report.overwrite == false
    }
}