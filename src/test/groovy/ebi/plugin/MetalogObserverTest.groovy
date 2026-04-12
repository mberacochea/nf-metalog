package ebi.plugin

import nextflow.NextflowMeta
import nextflow.Session
import nextflow.processor.TaskHandler
import nextflow.processor.TaskId
import nextflow.processor.TaskRun
import nextflow.script.WorkflowMetadata
import nextflow.trace.TraceRecord
import nextflow.trace.WorkflowStats
import nextflow.trace.event.TaskEvent
import spock.lang.Specification
import spock.lang.TempDir
import spock.util.concurrent.PollingConditions

import java.nio.file.Path
import java.time.OffsetDateTime

class MetalogObserverTest extends Specification {

    @TempDir
    Path tempDir

    def 'should create the observer instance'() {
        given:
        def factory = new MetalogFactory()
        def session = Mock(Session) {
            getRunName() >> 'test-run'
            getConfig() >> [:]
        }

        when:
        def result = factory.create(session)

        then:
        result.size() == 1
        result.first() instanceof MetalogObserver
    }

    def 'should create observer with custom SQLite file'() {
        given:
        def factory = new MetalogFactory()
        def session = Mock(Session) {
            getRunName() >> 'test-run'
            getConfig() >> [storageBackend: 'sqlite', sqlite: [file: tempDir.resolve('custom.db').toString()]]
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
        }
        def config = new MetalogConfig([:])
        def observer = new MetalogObserver(session, config)

        when:
        def events = [
                createTaskEvent('TEST_PROCESS', 'sample1', 'onTaskComplete', 1),
                createTaskEvent('CACHED_PROCESS', 'sample2', 'onTaskCached', 2),
                createTaskEvent('SUBMIT_PROCESS', 'sample3', 'onTaskSubmit', 3),
        ]
        events.each { event -> event.callback(observer, event.taskEvent) }

        then:
        noExceptionThrown()
    }

    def 'should skip task when no inputs'() {
        given:
        def session = Mock(Session) { getRunName() >> 'test-run' }
        def observer = new MetalogObserver(session, new MetalogConfig([:]))

        and:
        def task = Mock(TaskRun) {
            getName() >> 'NO_INPUT_PROCESS'
            getInputs() >> [:]
        }
        def event = new TaskEvent(Mock(TaskHandler) { getTask() >> task }, Mock(TraceRecord))

        when:
        observer.onTaskComplete(event)

        then:
        noExceptionThrown()
    }

    def 'should skip task when meta is not a Map'() {
        given:
        def session = Mock(Session) { getRunName() >> 'test-run' }
        def observer = new MetalogObserver(session, new MetalogConfig([:]))

        and:
        def task = Mock(TaskRun) {
            getName() >> 'TEST_PROCESS'
            getInputs() >> ['valueinparam<0:0>': 'not-a-map']
        }
        def event = new TaskEvent(Mock(TaskHandler) { getTask() >> task }, Mock(TraceRecord))

        when:
        observer.onTaskComplete(event)

        then:
        noExceptionThrown()
    }

    def 'should skip task when groupKey is absent from meta'() {
        given:
        def session = Mock(Session) { getRunName() >> 'test-run' }
        // Observer configured to look for 'sample_id', but meta only has 'id'
        def observer = new MetalogObserver(session, new MetalogConfig([groupKey: 'sample_id']))

        and:
        def task = Mock(TaskRun) {
            getName() >> 'TEST_PROCESS'
            getInputs() >> ['valueinparam<0:0>': [id: 'sample1']]
        }
        def event = new TaskEvent(Mock(TaskHandler) { getTask() >> task }, Mock(TraceRecord))

        when:
        observer.onTaskComplete(event)

        then:
        noExceptionThrown()
    }

    def 'should generate report and close backend on workflow complete'() {
        given:
        def workflow = Mock(WorkflowMetadata) {
            getRunName() >> 'test-run'
            getWorkDir() >> tempDir
            getStart() >> OffsetDateTime.now()
            getComplete() >> OffsetDateTime.now()
            getStats() >> Mock(WorkflowStats)
            getNextflow() >> Mock(NextflowMeta)
        }
        def session = Mock(Session) {
            getRunName() >> 'test-run'
            getWorkflowMetadata() >> workflow
        }
        def config = new MetalogConfig([report: [
                htmlFile: tempDir.resolve('out.html').toString(),
                csvFile : tempDir.resolve('out.csv').toString(),
        ]])
        def observer = new MetalogObserver(session, config)

        when:
        observer.onFlowComplete()

        then:
        noExceptionThrown()
        tempDir.resolve('out.html').toFile().exists()
    }

    def 'should respect custom groupKey'() {
        given:
        def session = Mock(Session) { getRunName() >> 'test-run' }
        def observer = new MetalogObserver(session, new MetalogConfig([groupKey: 'sample']))

        and:
        def task = Mock(TaskRun) {
            getName() >> 'TEST_PROCESS'
            getInputs() >> ['valueinparam<0:0>': [sample: 'my-sample-id']]
        }
        def trace = Mock(TraceRecord) {
            get(_) >> 'value'
            getTaskId() >> TaskId.of(1)
        }
        def event = new TaskEvent(Mock(TaskHandler) { getTask() >> task }, trace)

        when:
        observer.onTaskComplete(event)

        then:
        noExceptionThrown()
    }

    def 'should insert task events into database and verify data'() {
        given:
        def dbFile = tempDir.resolve('metalog.db')
        def session = Mock(Session) {
            getRunName() >> 'integration-test-run'
        }
        def config = new MetalogConfig([groupKey: 'id', storageBackend: 'sqlite', sqlite: [file: dbFile.toString()]])
        def observer = new MetalogObserver(session, config)

        when:
        def events = [
                createTaskEvent('PROCESS_A', 'sample-1', 'onTaskComplete', 1),
                createTaskEvent('PROCESS_B', 'sample-2', 'onTaskCached', 2),
                createTaskEvent('PROCESS_A', 'sample-3', 'onTaskSubmit', 3),
        ]
        events.each { event -> event.callback(observer, event.taskEvent) }

        and: 'wait for worker thread to process events'
        new PollingConditions(timeout: 15, delay: 1.0).eventually {
            def count = TestDatabaseUtils.withConnection(dbFile) { conn ->
                TestDatabaseUtils.tableExists(conn, 'metalog') ?
                    TestDatabaseUtils.getRowCount(conn, 'metalog') : 0
            }
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

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static class TaskEventData {
        final TaskEvent taskEvent
        final Closure callback

        TaskEventData(TaskEvent taskEvent, Closure callback) {
            this.taskEvent = taskEvent
            this.callback = callback
        }
    }

    private TaskEventData createTaskEvent(String processName, String sampleId, String eventType, int taskIndex) {
        def task = Mock(TaskRun) {
            getName() >> processName
            getInputs() >> ['valueinparam<0:0>': [id: sampleId]]
        }
        def trace = Mock(TraceRecord) {
            get('task_id') >> "task-${sampleId}"
            get(_) >> 'value'
            getTaskId() >> TaskId.of(taskIndex)
        }
        def event = new TaskEvent(Mock(TaskHandler) { getTask() >> task }, trace)

        Closure callback
        switch (eventType) {
            case 'onTaskComplete': callback = { obs, evt -> obs.onTaskComplete(evt) }; break
            case 'onTaskCached': callback = { obs, evt -> obs.onTaskCached(evt) }; break
            case 'onTaskSubmit': callback = { obs, evt -> obs.onTaskSubmit(evt) }; break
            default: throw new IllegalArgumentException("Unknown event type: ${eventType}")
        }
        return new TaskEventData(event, callback)
    }
}
