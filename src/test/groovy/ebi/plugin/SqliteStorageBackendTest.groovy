package ebi.plugin

import nextflow.trace.TraceRecord
import spock.lang.Specification
import spock.lang.TempDir
import spock.util.concurrent.PollingConditions

import java.nio.file.Path
import java.sql.ResultSet

import ebi.plugin.storage.SqliteStorageBackend

/**
 * Tests for SqliteStorageBackend focusing on queue handling,
 * concurrency, load testing, and database operations.
 */
class SqliteStorageBackendTest extends Specification {

    @TempDir
    Path tempDir

    def 'should initialize storage backend and create table'() {
        given:
        def dbFile = tempDir.resolve('test.db')
        def service = new SqliteStorageBackend(dbFile)

        when:
        service.initialize()

        then:
        dbFile.toFile().exists()

        and: 'table should exist with correct schema'
        def tableName = TestDatabaseUtils.withConnection(dbFile) { conn ->
            TestDatabaseUtils.getSingleStringResult(conn,
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='metalog'")
        }
        tableName == 'metalog'

        cleanup:
        service?.close()
    }

    def 'should insert task event into database'() {
        given:
        def dbFile = tempDir.resolve('test.db')
        def service = new SqliteStorageBackend(dbFile)
        service.initialize()

        and:
        def trace = createMockTraceRecord('task-123', 'COMPLETED')

        when:
        service.insertOrUpdateTaskEvent('test-run', 'sample-1', trace)

        and: 'wait for worker thread to process the event'
        new PollingConditions(timeout: 5, delay: 0.1).eventually {
            def count = TestDatabaseUtils.withConnection(dbFile) { conn ->
                TestDatabaseUtils.getRowCount(conn, 'metalog')
            }
            count == 1
        }

        then:
        def rowData = TestDatabaseUtils.withConnection(dbFile) { conn ->
            def stmt = conn.createStatement()
            try {
                def rs = stmt.executeQuery("SELECT * FROM metalog WHERE task_id = 'task-123'")
                try {
                    if (rs.next()) {
                        return [
                            run_name: rs.getString('run_name'),
                            group_id: rs.getString('group_id'),
                            process: rs.getString('process'),
                            status: rs.getString('status'),
                            cpu_percent: rs.getString('cpu_percent'),
                            mem_percent: rs.getString('mem_percent')
                        ]
                    }
                    return null
                } finally {
                    rs.close()
                }
            } finally {
                stmt.close()
            }
        }
        rowData.run_name == 'test-run'
        rowData.group_id == 'sample-1'
        rowData.process == 'TEST_PROCESS'
        rowData.status == 'COMPLETED'
        
        // Verify individual columns exist (no metadata column anymore)
        def cpuPercent = TestDatabaseUtils.withConnection(dbFile) { conn ->
            TestDatabaseUtils.getSingleStringResult(conn, "SELECT cpu_percent FROM metalog WHERE task_id = 'task-123'")
        }
        def memPercent = TestDatabaseUtils.withConnection(dbFile) { conn ->
            TestDatabaseUtils.getSingleStringResult(conn, "SELECT mem_percent FROM metalog WHERE task_id = 'task-123'")
        }
        cpuPercent != null
        memPercent != null

        cleanup:
        service?.close()
    }

    def 'should update existing task event (upsert behavior)'() {
        given:
        def dbFile = tempDir.resolve('test.db')
        def service = new SqliteStorageBackend(dbFile)
        service.initialize()

        and:
        def trace1 = createMockTraceRecord('task-123', 'RUNNING')
        def trace2 = createMockTraceRecord('task-123', 'COMPLETED')

        when: 'insert first event with RUNNING status'
        service.insertOrUpdateTaskEvent('test-run', 'sample-1', trace1)

        and: 'wait for processing'
        new PollingConditions(timeout: 5, delay: 0.1).eventually {
            queryDatabase(dbFile, "SELECT status FROM metalog WHERE task_id = 'task-123'") { rs ->
                rs.getString('status') == 'RUNNING'
            }
        }

        then: 'should have one record with RUNNING status'
        queryDatabase(dbFile, "SELECT COUNT(*) as cnt FROM metalog") { rs -> rs.getInt('cnt') } == 1

        when: 'update same task with COMPLETED status'
        service.insertOrUpdateTaskEvent('test-run', 'sample-1', trace2)

        and: 'wait for processing'
        new PollingConditions(timeout: 5, delay: 0.1).eventually {
            queryDatabase(dbFile, "SELECT status FROM metalog WHERE task_id = 'task-123'") { rs ->
                rs.getString('status') == 'COMPLETED'
            }
        }

        then: 'should still have one record but with updated status'
        queryDatabase(dbFile, "SELECT * FROM metalog WHERE task_id = 'task-123'") { rs ->
            rs.getString('status')
        } == 'COMPLETED'

        and: 'count should still be 1 (update, not insert)'
        queryDatabase(dbFile, "SELECT COUNT(*) as cnt FROM metalog") { rs -> rs.getInt('cnt') } == 1

        cleanup:
        service?.close()
    }

    def 'should handle concurrent task events from multiple threads'() {
        given:
        def dbFile = tempDir.resolve('test.db')
        def service = new SqliteStorageBackend(dbFile)
        service.initialize()

        and: 'setup for concurrent execution'
        def numConcurrentEvents = 100

        when: 'many threads submit events concurrently (simulating simultaneous task completions)'
        def threads = []
        numConcurrentEvents.times { eventId ->
            threads << Thread.start {
                def taskId = "task-${eventId}"
                def groupId = "sample-${eventId % 20}"  // 20 different samples
                def trace = createMockTraceRecord(taskId, 'COMPLETED')
                service.insertOrUpdateTaskEvent('concurrent-run', groupId, trace)
            }
        }

        and: 'wait for all threads to finish submitting'
        threads.each { it.join(10000) }

        and: 'wait for worker thread to process all events'
        new PollingConditions(timeout: 10, delay: 0.2).eventually {
            queryDatabase(dbFile, "SELECT COUNT(*) as cnt FROM metalog") { rs ->
                rs.getInt('cnt') == numConcurrentEvents
            }
        }

        then:
        queryDatabase(dbFile, "SELECT COUNT(*) as cnt FROM metalog") { rs -> rs.getInt('cnt') } == numConcurrentEvents

        cleanup:
        service?.close()
    }

    def 'should handle load test with many events in quick succession'() {
        given:
        def dbFile = tempDir.resolve('test.db')
        def service = new SqliteStorageBackend(dbFile)
        service.initialize()

        and:
        def numEvents = 500

        when: 'submit many events rapidly'
        numEvents.times { i ->
            def trace = createMockTraceRecord("task-${i}", 'COMPLETED')
            service.insertOrUpdateTaskEvent('load-test-run', "sample-${i % 50}", trace)
        }

        and: 'wait for all events to be processed'
        new PollingConditions(timeout: 30, delay: 0.5).eventually {
            queryDatabase(dbFile, "SELECT COUNT(*) as cnt FROM metalog") { rs ->
                rs.getInt('cnt') == numEvents
            }
        }

        then:
        queryDatabase(dbFile, "SELECT COUNT(*) as cnt FROM metalog") { rs -> rs.getInt('cnt') } == numEvents

        cleanup:
        service?.close()
    }

    def 'should handle graceful shutdown with events still in queue'() {
        given:
        def dbFile = tempDir.resolve('test.db')
        def service = new SqliteStorageBackend(dbFile)
        service.initialize()

        and:
        def numEvents = 100

        when: 'submit many events'
        numEvents.times { i ->
            def trace = createMockTraceRecord("task-${i}", 'COMPLETED')
            service.insertOrUpdateTaskEvent('shutdown-test', "sample-${i}", trace)
        }

        and: 'immediately close (events may still be in queue)'
        service.close()

        then: 'all events should still be processed before shutdown completes'
        queryDatabase(dbFile, "SELECT COUNT(*) as cnt FROM metalog") { rs -> rs.getInt('cnt') } == numEvents
    }

    def 'should store individual columns with trace fields'() {
        given:
        def dbFile = tempDir.resolve('test.db')
        def service = new SqliteStorageBackend(dbFile)
        service.initialize()

        and:
        def trace = Mock(TraceRecord) {
            get('task_id') >> 'task-json-123'
            get('process') >> 'TEST_PROCESS'
            get('status') >> 'COMPLETED'
            get('hash') >> 'abc123'
            get('name') >> 'test-task'
            get('exit') >> 0
            get('duration') >> 1000L
            get('realtime') >> 950L
            get('%cpu') >> 95.5
            get('peak_rss') >> '100 MB'
            get('container') >> 'docker://myimage:latest'
        }

        when:
        service.insertOrUpdateTaskEvent('test-run', 'sample-1', trace)

        and: 'wait for processing'
        new PollingConditions(timeout: 5, delay: 0.1).eventually {
            queryDatabase(dbFile, "SELECT COUNT(*) as cnt FROM metalog WHERE task_id = 'task-json-123'") { rs ->
                rs.getInt('cnt') == 1
            }
        }

        then:
        // Verify individual columns are stored correctly
        def hash = TestDatabaseUtils.withConnection(dbFile) { conn ->
            TestDatabaseUtils.getSingleStringResult(conn, "SELECT hash FROM metalog WHERE task_id = 'task-json-123'")
        }
        def duration = TestDatabaseUtils.withConnection(dbFile) { conn ->
            TestDatabaseUtils.getSingleStringResult(conn, "SELECT duration FROM metalog WHERE task_id = 'task-json-123'")
        }
        def container = TestDatabaseUtils.withConnection(dbFile) { conn ->
            TestDatabaseUtils.getSingleStringResult(conn, "SELECT container FROM metalog WHERE task_id = 'task-json-123'")
        }
        def cpuPercent = TestDatabaseUtils.withConnection(dbFile) { conn ->
            TestDatabaseUtils.getSingleStringResult(conn, "SELECT cpu_percent FROM metalog WHERE task_id = 'task-json-123'")
        }
        
        hash == 'abc123'
        duration == '1000'
        container == 'docker://myimage:latest'
        cpuPercent == '95.5'

        cleanup:
        service?.close()
    }

    def 'should handle mixed insert and update operations under load'() {
        given:
        def dbFile = tempDir.resolve('test.db')
        def service = new SqliteStorageBackend(dbFile)
        service.initialize()

        and:
        def numSamples = 50
        def updatesPerSample = 5

        when: 'simulate task lifecycle updates (submit -> running -> completed)'
        numSamples.times { sampleId ->
            updatesPerSample.times { updateNum ->
                def status = ['SUBMITTED', 'RUNNING', 'RUNNING', 'RUNNING', 'COMPLETED'][updateNum]
                def trace = createMockTraceRecord("task-${sampleId}", status)
                service.insertOrUpdateTaskEvent('mixed-test', "sample-${sampleId}", trace)
            }
        }

        and: 'wait for all events to be processed'
        new PollingConditions(timeout: 15, delay: 0.2).eventually {
            queryDatabase(dbFile, "SELECT COUNT(*) as cnt FROM metalog WHERE status = 'COMPLETED'") { rs ->
                rs.getInt('cnt') == numSamples
            }
        }

        then: 'should have one record per sample, all with COMPLETED status'
        queryDatabase(dbFile, "SELECT COUNT(*) as cnt FROM metalog") { rs -> rs.getInt('cnt') } == numSamples

        and: 'all should be in COMPLETED status (final update)'
        queryDatabase(dbFile, "SELECT COUNT(*) as cnt FROM metalog WHERE status = 'COMPLETED'") { rs -> rs.getInt('cnt') } == numSamples

        cleanup:
        service?.close()
    }

    // Helper methods

    private <T> T queryDatabase(Path dbFile, String sql, Closure<T> handler) {
        def conn = TestDatabaseUtils.createConnection(dbFile)
        try {
            def rs = TestDatabaseUtils.executeQuery(conn, sql)
            try {
                rs.next()
                return handler(rs)
            } finally {
                rs.close()
            }
        } finally {
            conn.close()
        }
    }

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
        }
    }
}
