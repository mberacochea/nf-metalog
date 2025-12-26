package ebi.plugin

import nextflow.Session
import nextflow.processor.TaskHandler
import nextflow.processor.TaskRun
import nextflow.trace.TraceRecord
import nextflow.trace.event.TaskEvent
import spock.lang.Specification
import spock.lang.TempDir
import spock.util.concurrent.PollingConditions
import nextflow.config.spec.ConfigScope

import java.nio.file.Path

/**
 * Basic tests for MetalogObserver with TraceObserverV2 API
 */
class MetalogObserverTest extends Specification {

    @TempDir
    Path tempDir

    def 'should create the observer instance' () {
        given:
        def factory = new MetalogFactory()
        def session = Mock(Session) {
            getRunName() >> 'test-run'
            getWorkDir() >> tempDir
            getConfig() >> [:]
        }
        when:
        def result = factory.create(session)
        then:
        result.size() == 1
        result.first() instanceof MetalogObserver
    }

    def 'should create observer with custom SQLite file' () {
        given:
        def factory = new MetalogFactory()
        def session = Mock(Session) {
            getRunName() >> 'test-run'
            getWorkDir() >> tempDir
            getConfig() >> [sqlite: [file: 'custom.db']]
        }
        when:
        def result = factory.create(session)
        then:
        result.size() == 1
        result.first() instanceof MetalogObserver
    }

    def 'should handle different task event types'() {
        given:
        def session = Mock(Session) {
            getRunName() >> 'test-run'
            getWorkDir() >> tempDir
        }
        def config = new MetalogConfig([:])
        def observer = new MetalogObserver(session, config)

        when:
        // Test all three event types with different samples
        def events = [
            createTaskEvent('TEST_PROCESS', 'sample1', 'onTaskComplete'),
            createTaskEvent('CACHED_PROCESS', 'sample2', 'onTaskCached'),
            createTaskEvent('SUBMIT_PROCESS', 'sample3', 'onTaskSubmit')
        ]

        events.each { event ->
            event.callback(observer, event.taskEvent)
        }

        then:
        noExceptionThrown()
    }

    def 'should skip task when no inputs' () {
        given:
        def session = Mock(Session) {
            getRunName() >> 'test-run'
            getWorkDir() >> tempDir
        }
        def config = new MetalogConfig([:])
        def observer = new MetalogObserver(session, config)

        and:
        def task = Mock(TaskRun) {
            getName() >> 'NO_INPUT_PROCESS'
            getInputs() >> [:]
        }
        def handler = Mock(TaskHandler) {
            getTask() >> task
        }
        def trace = Mock(TraceRecord)
        def event = new TaskEvent(handler, trace)

        when:
        observer.onTaskComplete(event)

        then:
        noExceptionThrown()
    }

    def 'should close database connection on workflow complete' () {
        given:
        def session = Mock(Session) {
            getRunName() >> 'test-run'
            getWorkDir() >> tempDir
        }
        def config = new MetalogConfig([:])
        def observer = new MetalogObserver(session, config)

        when:
        observer.onFlowComplete()

        then:
        noExceptionThrown()
    }



    def 'should respect custom groupKey' () {
        given:
        def session = Mock(Session) {
            getRunName() >> 'test-run'
            getWorkDir() >> tempDir
        }
        def config = new MetalogConfig([groupKey: 'sample'])
        def observer = new MetalogObserver(session, config)

        and:
        def task = Mock(TaskRun) {
            getName() >> 'TEST_PROCESS'
            getInputs() >> ['valueinparam<0:0>': [sample: 'my-sample-id']]
        }
        def handler = Mock(TaskHandler) {
            getTask() >> task
        }
        def trace = Mock(TraceRecord) {
            get(_) >> 'value'
        }
        def event = new TaskEvent(handler, trace)

        when:
        observer.onTaskComplete(event)

        then:
        noExceptionThrown()
    }

    def 'should insert task events into database and verify data'() {
        given:
        def session = Mock(Session) {
            getRunName() >> 'integration-test-run'
            getWorkDir() >> tempDir
        }
        def config = new MetalogConfig([groupKey: 'id'])
        def observer = new MetalogObserver(session, config)

        and:
        def dbFile = tempDir.resolve('metalog.db')

        when:
        // Create and process multiple task events
        def events = [
            createTaskEvent('PROCESS_A', 'sample-1', 'onTaskComplete'),
            createTaskEvent('PROCESS_B', 'sample-2', 'onTaskCached'),
            createTaskEvent('PROCESS_A', 'sample-3', 'onTaskSubmit')
        ]

        events.each { event ->
            event.callback(observer, event.taskEvent)
        }

        and: 'wait for worker thread to process events'
        new PollingConditions(timeout: 15, delay: 1.0).eventually {
            def count = TestDatabaseUtils.withConnection(dbFile) { conn ->
                TestDatabaseUtils.tableExists(conn, 'metalog') ? 
                    TestDatabaseUtils.getRowCount(conn, 'metalog') : 0
            }
            
            println "DEBUG: count=${count}"
            count == 3
        }

        then: 'should have inserted all events into database'
        def rowCount = TestDatabaseUtils.withConnection(dbFile) { conn ->
            TestDatabaseUtils.getRowCount(conn, 'metalog')
        }
        rowCount == 3

        and: 'should have correct run name'
        def runName = TestDatabaseUtils.withConnection(dbFile) { conn ->
            TestDatabaseUtils.getSingleStringResult(conn, "SELECT DISTINCT run_name FROM metalog")
        }
        runName == 'integration-test-run'

        and: 'should have correct group IDs'
        def groupIds = TestDatabaseUtils.withConnection(dbFile) { conn ->
            TestDatabaseUtils.getColumnValues(conn, "SELECT group_id FROM metalog ORDER BY group_id", "group_id")
        }
        groupIds == ['sample-1', 'sample-2', 'sample-3']

        cleanup:
        observer.onFlowComplete()
    }

    // TODO: Add integration test to verify actual SQLite database insertion
    // TODO: Add test for invalid meta (not a Map type)
    // TODO: Add test for missing groupBy key in meta map
    // TODO: Add test to verify JSON metadata structure in database

    // Helper method to create task events with different types
    private static class TaskEventData {
        final TaskEvent taskEvent
        final Closure callback

        TaskEventData(TaskEvent taskEvent, Closure callback) {
            this.taskEvent = taskEvent
            this.callback = callback
        }
    }

    private TaskEventData createTaskEvent(String processName, String sampleId, String eventType) {
        def task = Mock(TaskRun) {
            getName() >> processName
            getInputs() >> ['valueinparam<0:0>': [id: sampleId]]
        }
        def handler = Mock(TaskHandler) {
            getTask() >> task
        }
        def trace = Mock(TraceRecord) {
            get('task_id') >> "task-${sampleId}"
            get(_) >> 'value'
        }
        def event = new TaskEvent(handler, trace)

        def callback
        switch (eventType) {
            case 'onTaskComplete':
                callback = { observer, evt -> observer.onTaskComplete(evt) }
                break
            case 'onTaskCached':
                callback = { observer, evt -> observer.onTaskCached(evt) }
                break
            case 'onTaskSubmit':
                callback = { observer, evt -> observer.onTaskSubmit(evt) }
                break
            default:
                throw new IllegalArgumentException("Unknown event type: ${eventType}")
        }

        return new TaskEventData(event, callback)
    }

}
