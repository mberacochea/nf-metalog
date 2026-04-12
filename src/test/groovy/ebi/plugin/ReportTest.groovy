package ebi.plugin

import ebi.plugin.storage.MemoryStorageBackend
import nextflow.NextflowMeta
import nextflow.processor.TaskId
import nextflow.script.WorkflowMetadata
import nextflow.trace.TraceRecord
import nextflow.trace.WorkflowStats
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path
import java.time.OffsetDateTime

class ReportTest extends Specification {

    @TempDir
    Path tempDir

    def 'should write CSV file with sample data'() {
        given:
        def data = [
                [run_name: 'test-run', group_id: 'sample1', process: 'FASTQC', status: 'COMPLETED', task_id: '1'],
                [run_name: 'test-run', group_id: 'sample2', process: 'TRIM', status: 'COMPLETED', task_id: '2'],
        ]
        def csvFile = tempDir.resolve('report.csv').toString()

        when:
        Report.writeCsv(data, csvFile)

        then:
        def lines = tempDir.resolve('report.csv').readLines()
        lines.size() == 3          // header + 2 data rows
        lines[0].contains('run_name')
        lines[0].contains('group_id')
        lines[1].contains('sample1')
        lines[2].contains('sample2')
    }

    def 'should skip CSV write when data is empty'() {
        given:
        def csvFile = tempDir.resolve('empty.csv').toString()

        when:
        Report.writeCsv([], csvFile)

        then:
        noExceptionThrown()
        !tempDir.resolve('empty.csv').toFile().exists()
    }

    def 'should create parent directories for output paths'() {
        given:
        def nested = tempDir.resolve('sub/dir/report.html')

        when:
        Report.createParentDirs(nested)

        then:
        nested.parent.toFile().isDirectory()
    }

    def 'should generate HTML and CSV report files with sample data'() {
        given:
        def backend = new MemoryStorageBackend()
        backend.initialize()

        def trace = Mock(TraceRecord) {
            get('task_id') >> 'task-1'
            get('process') >> 'FASTQC'
            get('status') >> 'COMPLETED'
            get(_) >> 'value'
            getSimpleName() >> 'FASTQC'
            getTaskId() >> TaskId.of(1)
        }
        backend.insertOrUpdateTaskEvent('test-run', 'sample1', trace)

        def workflow = Mock(WorkflowMetadata) {
            getRunName() >> 'test-run'
            getWorkDir() >> tempDir
            getStart() >> OffsetDateTime.now()
            getComplete() >> OffsetDateTime.now()
            getStats() >> Mock(WorkflowStats)
            getNextflow() >> Mock(NextflowMeta)
        }
        def config = new MetalogConfig.ReportConfig([
                htmlFile: tempDir.resolve('report.html').toString(),
                csvFile : tempDir.resolve('report.csv').toString(),
        ])

        when:
        Report.generate(backend, workflow, config)

        then:
        tempDir.resolve('report.html').toFile().exists()
        tempDir.resolve('report.csv').toFile().exists()
        tempDir.resolve('report.html').text.contains('test-run')
        tempDir.resolve('report.csv').text.contains('sample1')
    }
}
