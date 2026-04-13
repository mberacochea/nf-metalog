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

import groovy.util.logging.Slf4j
import groovy.json.JsonBuilder
import ebi.plugin.storage.StorageBackend
import groovy.text.GStringTemplateEngine
import nextflow.exception.AbortOperationException
import nextflow.file.FileHelper
import nextflow.script.WorkflowMetadata

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter

@Slf4j
class Report {

    static void generate(StorageBackend storageBackend, WorkflowMetadata workflow, MetalogConfig.ReportConfig reportConfig) {
        try {
            def csvData = storageBackend.fetchAllData(workflow.runName)

            // Check if files already exist and handle override logic
            checkFileOverwrite(FileHelper.toPath(reportConfig.csvFile), reportConfig.overwrite)
            checkFileOverwrite(FileHelper.toPath(reportConfig.htmlFile), reportConfig.overwrite)

            // Create parent directories if needed
            createParentDirs(Paths.get(reportConfig.csvFile))
            createParentDirs(Paths.get(reportConfig.htmlFile))

            // Use configuration parameters for file names
            writeCsv(csvData, reportConfig.csvFile)

            def templateString = readAsset("nf-metalog_report.html")
            def jsAssets = []
            jsAssets.add(readAsset("assets/datatables.min.js"))
            jsAssets.add(readAsset("assets/bootstrap.bundle.min.js"))
            jsAssets.add(readAsset("assets/nf-metalog_report.js"))
            jsAssets.add(readAsset("assets/plotly-basic-3.3.1.min.js"))

            def cssAssets = []
            cssAssets.add(readAsset("assets/datatables.min.css"))
            cssAssets.add(readAsset("assets/bootstrap.min.css"))
            cssAssets.add(readAsset("assets/nf-metalog_report.css"))

            def binding = [
                workflow: workflow,
                data: new JsonBuilder( toColumnar(csvData) ).toString(),
                js_assets: jsAssets,
                css_assets: cssAssets
            ]

            def engine = new GStringTemplateEngine()
            def template = engine.createTemplate(templateString).make(binding)

            // Use configuration parameter for HTML file name
            Files.write(Paths.get(reportConfig.htmlFile), template.toString().getBytes())

            log.info("Successfully generated ${reportConfig.htmlFile}")
        } catch (Exception e) {
            log.error("Error generating and writing the nf-metalog report", e)
        }
    }

    /**
     * Convert a list of maps to a columnar format to reduce JSON payload size.
     * Instead of repeating key names for every row, keys are stored once in 'cols'
     * and each row becomes a plain value array in 'rows'.
     *
     * @param data List of maps with identical key sets
     * @return Map with 'cols' (List<String>) and 'rows' (List<List<Object>>)
     */
    // Fields present in storage but excluded from the HTML report payload
    private static final Set<String> REPORT_EXCLUDED_FIELDS = Collections.unmodifiableSet(new HashSet<>([
        'script',       // task script body — can be very large
        'env',          // environment variables — can be very large
        'native_id',    // executor-specific job ID — not displayed
        'module',       // Nextflow module system field — not displayed
        'vol_ctxt',     // voluntary context switches — not displayed
        'inv_ctxt',     // involuntary context switches — not displayed
        'rss',          // memory snapshot — superseded by peak_rss
        'vmem',         // memory snapshot — superseded by peak_vmem
        'realtime',     // duplicate of duration
        'queue',        // executor queue name — not displayed
        'time',         // requested time limit — not displayed
        'run_name',     // already in workflow metadata — redundant per row
        'error_action', // not displayed
        'cpu_model',    // not displayed
        'hostname',     // not displayed
        'cpus',         // not displayed
    ]))

    private static Map toColumnar(List<Map<String, Object>> data) {
        if (!data) return [cols: [], rows: []]
        // Assumes all rows share the same key set — safe for TraceRecord-derived data
        // which has a static schema. All values are strings (stored via .toString()).
        def cols = (data[0].keySet() as List<String>).findAll { !REPORT_EXCLUDED_FIELDS.contains(it) }
        def rows = data.collect { row -> cols.collect { col -> row[col] } }
        return [cols: cols, rows: rows]
    }

    /**
     * Read the document HTML template from the application classpath
     *
     * @param path A resource path location
     * @return The loaded template as a string
     */
    private static String readAsset(String path) {
        // Ensure path starts with "/" for proper resource loading
        String resourcePath = path.startsWith("/") ? path : "/${path}"
        final res = Report.class.getResourceAsStream(resourcePath)
        
        if (res == null) {
            throw new FileNotFoundException("Resource not found: ${resourcePath}")
        }
        
        try {
            return new InputStreamReader(res, 'UTF-8').text
        } finally {
            res.close()
        }
    }

    /**
     * Write the data to a CSV file using Apache Commons CSV
     * @param data
     * @param csvFile
     */
    static void writeCsv(List<Map<String, Object>> data, String csvFile) {
        if (data.size() == 0) {
            log.info("No data to write in the metalog CSV file")
            return
        }
        
        // Get headers from the first row
        def headers = data[0].keySet() as String[]
        
        // Create CSV format with headers
        def csvFormat = CSVFormat.DEFAULT.withHeader(headers)
        
        // Use BufferedWriter to write to file
        Path csvPath = Paths.get(csvFile)
        try (def writer = Files.newBufferedWriter(csvPath);
             def csvPrinter = new CSVPrinter(writer, csvFormat)) {
            
            // Write each row
            data.each { row ->
                def values = headers.collect { header -> row[header] }
                csvPrinter.printRecord(values)
            }
            
            csvPrinter.flush()
        } catch (Exception e) {
            log.error("Error writing CSV file: ${csvFile}", e)
            throw e
        }
    }

    /**
     * Create parent directories for the given path if they do not already exist.
     * @param path
     */
    static void createParentDirs(Path path) {
        final parent = path.parent
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent)
            log.debug("Created directories: ${parent}")
        }
    }

    /**
     * Check whether a file already exists and throw an
     * error if it cannot be overwritten.
     *
     * @param path
     * @param overwrite
     */
    static void checkFileOverwrite(Path path, boolean overwrite) {
        final attrs = FileHelper.readAttributes(path)
        if( attrs ) {
            if( overwrite && (attrs.isDirectory() || !path.delete()) )
                throw new AbortOperationException("Unable to overwrite existing provenance file: ${path.toUriString()}")
            else if( !overwrite )
                throw new AbortOperationException("Provenance file already exists: ${path.toUriString()}")
        }
    }
}
