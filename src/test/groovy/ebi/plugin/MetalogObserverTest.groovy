package ebi.plugin

import nextflow.Session
import nextflow.processor.TaskHandler
import nextflow.processor.TaskRun
import nextflow.trace.TraceRecord
import nextflow.trace.event.TaskEvent
import spock.lang.Specification
import spock.lang.TempDir
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

    def 'should handle onTaskComplete event' () {
        given:
        def session = Mock(Session) {
            getRunName() >> 'test-run'
            getWorkDir() >> tempDir
        }
        def config = new MetalogConfig([:])
        def observer = new MetalogObserver(session, config)

        and:
        def task = Mock(TaskRun) {
            getName() >> 'TEST_PROCESS'
            getInputs() >> ['valueinparam<0:0>': [id: 'sample1']]
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

    def 'should handle onTaskCached event' () {
        given:
        def session = Mock(Session) {
            getRunName() >> 'test-run'
            getWorkDir() >> tempDir
        }
        def config = new MetalogConfig([:])
        def observer = new MetalogObserver(session, config)

        and:
        def task = Mock(TaskRun) {
            getName() >> 'CACHED_PROCESS'
            getInputs() >> ['valueinparam<0:0>': [id: 'sample2']]
        }
        def handler = Mock(TaskHandler) {
            getTask() >> task
        }
        def trace = Mock(TraceRecord) {
            get(_) >> 'value'
        }
        def event = new TaskEvent(handler, trace)

        when:
        observer.onTaskCached(event)

        then:
        noExceptionThrown()
    }

    def 'should handle onTaskSubmit event' () {
        given:
        def session = Mock(Session) {
            getRunName() >> 'test-run'
            getWorkDir() >> tempDir
        }
        def config = new MetalogConfig([:])
        def observer = new MetalogObserver(session, config)

        and:
        def task = Mock(TaskRun) {
            getName() >> 'SUBMIT_PROCESS'
            getInputs() >> ['valueinparam<0:0>': [id: 'sample3']]
        }
        def handler = Mock(TaskHandler) {
            getTask() >> task
        }
        def trace = Mock(TraceRecord) {
            get(_) >> 'value'
        }
        def event = new TaskEvent(handler, trace)

        when:
        observer.onTaskSubmit(event)

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

    def 'should use SQLite by default when no database service provided' () {
        given:
        def session = Mock(Session) {
            getRunName() >> 'test-run'
            getWorkDir() >> tempDir
        }
        def config = new MetalogConfig([:])

        when:
        def observer = new MetalogObserver(session, config)

        then:
        notThrown(Exception)
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

    // TODO: Add integration test to verify actual SQLite database insertion
    // TODO: Add test for invalid meta (not a Map type)
    // TODO: Add test for missing groupBy key in meta map
    // TODO: Add test to verify JSON metadata structure in database
}
