package ebi.plugin

import nextflow.processor.TaskHandler
import nextflow.processor.TaskRun
import nextflow.trace.TraceRecord
import spock.lang.Specification
import spock.lang.TempDir
import spock.util.concurrent.PollingConditions

import java.nio.file.Path
import java.sql.ResultSet

import ebi.plugin.TestDatabaseUtils
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
        def conn = TestDatabaseUtils.createConnection(dbFile)
        def rs = TestDatabaseUtils.executeQuery(conn,
            "SELECT name FROM sqlite_master WHERE type='table' AND name='metalog'"
        )
        rs.next()
        rs.getString('name') == 'metalog'

        cleanup:
        rs?.close()
        conn?.close()
        service?.close()
    }

    def 'should insert task event into database'() {
        given:
        def dbFile = tempDir.resolve('test.db')
        def service = new SqliteStorageBackend(dbFile)
        service.initialize()

        and:
        def handler = createMockTaskHandler('TEST_PROCESS')
        def trace = createMockTraceRecord('task-123', 'COMPLETED')

        when:
        service.insertOrUpdateTaskEvent('test-run', 'sample-1', handler, trace)

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
                            status: rs.getString('status')
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
        
        // Verify metadata exists separately
        def metadata = TestDatabaseUtils.withConnection(dbFile) { conn ->
            TestDatabaseUtils.getSingleStringResult(conn, "SELECT metadata FROM metalog WHERE task_id = 'task-123'")
        }
        metadata != null

        cleanup:
        service?.close()
    }

    def 'should update existing task event (upsert behavior)'() {
        given:
        def dbFile = tempDir.resolve('test.db')
        def service = new SqliteStorageBackend(dbFile)
        service.initialize()

        and:
        def handler = createMockTaskHandler('TEST_PROCESS')
        def trace1 = createMockTraceRecord('task-123', 'RUNNING')
        def trace2 = createMockTraceRecord('task-123', 'COMPLETED')

        when: 'insert first event with RUNNING status'
        service.insertOrUpdateTaskEvent('test-run', 'sample-1', handler, trace1)

        and: 'wait for processing'
        new PollingConditions(timeout: 5, delay: 0.1).eventually {
            def rs = queryDatabase(dbFile, "SELECT status FROM metalog WHERE task_id = 'task-123'")
            def status = rs?.getString('status')
            rs?.close()
            status == 'RUNNING'
        }

        then: 'should have one record with RUNNING status'
        def rs1 = queryDatabase(dbFile, "SELECT COUNT(*) as cnt FROM metalog")
        rs1.getInt('cnt') == 1
        rs1.close()

        when: 'update same task with COMPLETED status'
        service.insertOrUpdateTaskEvent('test-run', 'sample-1', handler, trace2)

        and: 'wait for processing'
        new PollingConditions(timeout: 5, delay: 0.1).eventually {
            def rs = queryDatabase(dbFile, "SELECT status FROM metalog WHERE task_id = 'task-123'")
            def status = rs?.getString('status')
            rs?.close()
            status == 'COMPLETED'
        }

        then: 'should still have one record but with updated status'
        def rs2 = queryDatabase(dbFile, "SELECT * FROM metalog WHERE task_id = 'task-123'")
        rs2.getString('status') == 'COMPLETED'

        and: 'count should still be 1 (update, not insert)'
        rs2.close()
        def rs3 = queryDatabase(dbFile, "SELECT COUNT(*) as cnt FROM metalog")
        rs3.getInt('cnt') == 1

        cleanup:
        rs2?.close()
        rs3?.close()
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
                def handler = createMockTaskHandler("PROCESS_${eventId % 5}")  // 5 different processes
                def trace = createMockTraceRecord(taskId, 'COMPLETED')

                service.insertOrUpdateTaskEvent('concurrent-run', groupId, handler, trace)
            }
        }

        and: 'wait for all threads to finish submitting'
        threads.each { it.join(10000) }

        and: 'wait for worker thread to process all events'
        new PollingConditions(timeout: 10, delay: 0.2).eventually {
            def count = queryDatabase(dbFile, "SELECT COUNT(*) as cnt FROM metalog").getInt('cnt')
            count == numConcurrentEvents
        }

        then:
        def rs = queryDatabase(dbFile, "SELECT COUNT(*) as cnt FROM metalog")
        rs.getInt('cnt') == numConcurrentEvents

        cleanup:
        rs?.close()
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
            def handler = createMockTaskHandler("PROCESS_${i % 10}")
            def trace = createMockTraceRecord("task-${i}", 'COMPLETED')
            service.insertOrUpdateTaskEvent('load-test-run', "sample-${i % 50}", handler, trace)
        }

        and: 'wait for all events to be processed'
        new PollingConditions(timeout: 30, delay: 0.5).eventually {
            def count = queryDatabase(dbFile, "SELECT COUNT(*) as cnt FROM metalog").getInt('cnt')
            count == numEvents
        }

        then:
        def rs = queryDatabase(dbFile, "SELECT COUNT(*) as cnt FROM metalog")
        rs.getInt('cnt') == numEvents

        cleanup:
        rs?.close()
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
            def handler = createMockTaskHandler("PROCESS")
            def trace = createMockTraceRecord("task-${i}", 'COMPLETED')
            service.insertOrUpdateTaskEvent('shutdown-test', "sample-${i}", handler, trace)
        }

        and: 'immediately close (events may still be in queue)'
        service.close()

        then: 'all events should still be processed before shutdown completes'
        def rs = queryDatabase(dbFile, "SELECT COUNT(*) as cnt FROM metalog")
        rs.getInt('cnt') == numEvents

        cleanup:
        rs?.close()
    }

    def 'should store JSON metadata with trace fields'() {
        given:
        def dbFile = tempDir.resolve('test.db')
        def service = new SqliteStorageBackend(dbFile)
        service.initialize()

        and:
        def handler = createMockTaskHandler('TEST_PROCESS')
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
        service.insertOrUpdateTaskEvent('test-run', 'sample-1', handler, trace)

        and: 'wait for processing'
        new PollingConditions(timeout: 5, delay: 0.1).eventually {
            def count = queryDatabase(dbFile, "SELECT COUNT(*) as cnt FROM metalog WHERE task_id = 'task-json-123'").getInt('cnt')
            count == 1
        }

        then:
        def rs = queryDatabase(dbFile, "SELECT metadata FROM metalog WHERE task_id = 'task-json-123'")
        def metadata = rs.getString('metadata')
        metadata.contains('"hash":"abc123"')
        metadata.contains('"duration":1000')
        metadata.contains('"container":"docker://myimage:latest"')

        cleanup:
        rs?.close()
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
                def handler = createMockTaskHandler("PROCESS_${sampleId}")
                def status = ['SUBMITTED', 'RUNNING', 'RUNNING', 'RUNNING', 'COMPLETED'][updateNum]
                def trace = createMockTraceRecord("task-${sampleId}", status)
                service.insertOrUpdateTaskEvent('mixed-test', "sample-${sampleId}", handler, trace)
            }
        }

        and: 'wait for all events to be processed'
        new PollingConditions(timeout: 15, delay: 0.2).eventually {
            def rsCheck = queryDatabase(dbFile, "SELECT COUNT(*) as cnt FROM metalog WHERE status = 'COMPLETED'")
            def count = rsCheck.getInt('cnt')
            rsCheck.close()
            count == numSamples
        }

        then: 'should have one record per sample, all with COMPLETED status'
        def rs = queryDatabase(dbFile, "SELECT COUNT(*) as cnt FROM metalog")
        rs.getInt('cnt') == numSamples
        rs.close()

        and: 'all should be in COMPLETED status (final update)'
        def rs2 = queryDatabase(dbFile, "SELECT COUNT(*) as cnt FROM metalog WHERE status = 'COMPLETED'")
        rs2.getInt('cnt') == numSamples

        cleanup:
        rs2?.close()
        service?.close()
    }

    // Helper methods

    private ResultSet queryDatabase(Path dbFile, String sql) {
        def conn = TestDatabaseUtils.createConnection(dbFile)
        try {
            return TestDatabaseUtils.executeQuery(conn, sql)
        } catch (Exception e) {
            conn?.close()
            throw e
        }
    }

    private TaskHandler createMockTaskHandler(String processName) {
        def task = Mock(TaskRun) {
            getName() >> processName
        }
        Mock(TaskHandler) {
            getTask() >> task
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
        }
    }
}
