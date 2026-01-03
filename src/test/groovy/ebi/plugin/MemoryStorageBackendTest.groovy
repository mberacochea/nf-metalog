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
import nextflow.processor.TaskHandler
import nextflow.trace.TraceRecord
import ebi.plugin.storage.MemoryStorageBackend

class MemoryStorageBackendTest extends Specification {

    def "test memory backend initialization"() {
        given:
        def dbService = new MemoryStorageBackend()

        when:
        dbService.initialize()

        then:
        !dbService.isClosed()
    }

    def "test insert and fetch task events"() {
        given:
        def dbService = new MemoryStorageBackend()
        dbService.initialize()
        
        // Create mock task handler and trace
        def taskHandler = Mock(TaskHandler)
        def traceRecord = Mock(TraceRecord)

        when:
        dbService.insertOrUpdateTaskEvent("test-run", "sample1", taskHandler, traceRecord)
        def results = dbService.fetchAllData("test-run")

        then:
        results.size() == 1
        results[0].run_name == "test-run"
        results[0].group_id == "sample1"
        !dbService.isClosed()
    }

    def "test multiple inserts and fetch"() {
        given:
        def dbService = new MemoryStorageBackend()
        dbService.initialize()
        
        def taskHandler = Mock(TaskHandler)
        def traceRecord = Mock(TraceRecord)

        when:
        dbService.insertOrUpdateTaskEvent("test-run", "sample1", taskHandler, traceRecord)
        dbService.insertOrUpdateTaskEvent("test-run", "sample2", taskHandler, traceRecord)
        dbService.insertOrUpdateTaskEvent("other-run", "sample3", taskHandler, traceRecord)
        
        def results = dbService.fetchAllData("test-run")
        def otherResults = dbService.fetchAllData("other-run")

        then:
        results.size() == 2
        otherResults.size() == 1
        results.every { it.run_name == "test-run" }
        otherResults.every { it.run_name == "other-run" }
    }

    def "test database close and reopen"() {
        given:
        def dbService = new MemoryStorageBackend()
        dbService.initialize()
        
        def taskHandler = Mock(TaskHandler)
        def traceRecord = Mock(TraceRecord)
        
        // Add some data
        dbService.insertOrUpdateTaskEvent("test-run", "sample1", taskHandler, traceRecord)

        when:
        dbService.close()
        
        then:
        dbService.isClosed()
        
        when:
        def results = dbService.fetchAllData("test-run")
        
        then:
        results.size() == 0  // Data should be cleared after close
    }

    def "test thread safety with concurrent inserts"() {
        given:
        def dbService = new MemoryStorageBackend()
        dbService.initialize()
        
        def taskHandler = Mock(TaskHandler)
        def traceRecord = Mock(TraceRecord)
        
        def threads = []
        def threadCount = 10

        when:
        for (i in 0..<threadCount) {
            def thread = new Thread({
                dbService.insertOrUpdateTaskEvent("test-run", "sample${i}", taskHandler, traceRecord)
            })
            threads << thread
            thread.start()
        }
        
        // Wait for all threads to complete
        threads.each { it.join() }
        
        def results = dbService.fetchAllData("test-run")

        then:
        results.size() == threadCount
        !dbService.isClosed()
    }

    def "test empty fetch on new database"() {
        given:
        def dbService = new MemoryStorageBackend()
        dbService.initialize()

        when:
        def results = dbService.fetchAllData("test-run")

        then:
        results.size() == 0
        !dbService.isClosed()
    }

    def "test insert after close should be ignored"() {
        given:
        def dbService = new MemoryStorageBackend()
        dbService.initialize()
        dbService.close()
        
        def taskHandler = Mock(TaskHandler)
        def traceRecord = Mock(TraceRecord)

        when:
        dbService.insertOrUpdateTaskEvent("test-run", "sample1", taskHandler, traceRecord)
        def results = dbService.fetchAllData("test-run")

        then:
        results.size() == 0
        dbService.isClosed()
    }
}