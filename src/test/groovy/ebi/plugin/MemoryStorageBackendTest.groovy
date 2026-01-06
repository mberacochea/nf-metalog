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
import nextflow.trace.TraceRecord
import nextflow.processor.TaskId
import ebi.plugin.storage.MemoryStorageBackend

class MemoryStorageBackendTest extends Specification {

    private TraceRecord createMockTraceRecord(String taskId, String status) {
        Mock(TraceRecord) {
            get('task_id') >> taskId
            get('process') >> 'TEST_PROCESS'
            get('status') >> status
            get('hash') >> 'abc123'
            get('name') >> 'test-task'
            get('exit') >> (status == 'COMPLETED' ? 0 : null)
            get('%cpu') >> 50.0
            get('%mem') >> 75.0
            getSimpleName() >> 'TEST_PROCESS'
            getTaskId() >> TaskId.of(taskId)
        }
    }

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
        def traceRecord = createMockTraceRecord("1", "COMPLETED")

        when:
        dbService.insertOrUpdateTaskEvent("test-run", "sample1", traceRecord)
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
        
        def traceRecord1 = createMockTraceRecord("1", "COMPLETED")
        def traceRecord2 = createMockTraceRecord("2", "COMPLETED")

        when:
        dbService.insertOrUpdateTaskEvent("test-run", "sample1", traceRecord1)
        dbService.insertOrUpdateTaskEvent("test-run", "sample2", traceRecord2)

        def results = dbService.fetchAllData("test-run")

        then:
        results.size() == 2
        results.every { it.run_name == "test-run" }
    }

    def "test database close and reopen"() {
        given:
        def dbService = new MemoryStorageBackend()
        dbService.initialize()
        
        def traceRecord = createMockTraceRecord("1", "COMPLETED")
        
        // Add some data
        dbService.insertOrUpdateTaskEvent("test-run", "sample1", traceRecord)

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
        
        def threads = []
        def threadCount = 10

        when:
        for (i in 0..<threadCount) {
            def index = i
            def thread = new Thread({
                def traceRecord = createMockTraceRecord("${index}", "COMPLETED")
                dbService.insertOrUpdateTaskEvent("test-run", "sample${i}", traceRecord)
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
        
        def traceRecord = createMockTraceRecord("1", "COMPLETED")

        when:
        dbService.insertOrUpdateTaskEvent("test-run", "sample1", traceRecord)
        def results = dbService.fetchAllData("test-run")

        then:
        results.size() == 0
        dbService.isClosed()
    }

    def "test upsert behavior - same task id should update existing record"() {
        given:
        def dbService = new MemoryStorageBackend()
        dbService.initialize()
        
        def traceRecord1 = createMockTraceRecord("1", "RUNNING")
        def traceRecord2 = createMockTraceRecord("1", "COMPLETED")

        when:
        dbService.insertOrUpdateTaskEvent("test-run", "sample1", traceRecord1)
        dbService.insertOrUpdateTaskEvent("test-run", "sample1", traceRecord2)
        
        def results = dbService.fetchAllData("test-run")

        then:
        results.size() == 1  // Should only have one record
        results[0].status == "COMPLETED"  // Should have the updated status
    }
}