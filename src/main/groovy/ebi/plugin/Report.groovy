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

@Slf4j
class Report {

    static void generate(StorageBackend storageBackend, WorkflowMetadata workflow, MetalogConfig.ReportConfig reportConfig) {
        try {
            def csvData = storageBackend.fetchAllData(workflow.runName)

            // Check if files already exist and handle override logic
            checkFileOverwrite(FileHelper.toPath(reportConfig.csvFile), reportConfig.overwrite)
            checkFileOverwrite(FileHelper.toPath(reportConfig.htmlFile), reportConfig.overwrite)

            // Use configuration parameters for file names
            writeCsv(csvData, reportConfig.csvFile)

            def templateString = readAsset("nf-metalog_report.html")
            def jsAssets = []
            jsAssets.add(readAsset("assets/bootstrap.bundle.min.js"))
            jsAssets.add(readAsset("assets/datatables.min.js"))
            jsAssets.add(readAsset("assets/nf-metalog_report.js"))
            jsAssets.add(readAsset("assets/plotly-basic-3.3.1.min.js"))

            def cssAssets = []
            cssAssets.add(readAsset("assets/bootstrap.min.css"))
            cssAssets.add(readAsset("assets/datatables.min.css"))
            cssAssets.add(readAsset("assets/nf-metalog_report.css"))

            def binding = [
                workflow: workflow,
                data: new JsonBuilder( csvData ).toString(),
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
     * Read the document HTML template from the application classpath
     *
     * @param path A resource path location
     * @return The loaded template as a string
     */
    private static String readAsset(String path) {
        final writer = new StringWriter()
        // Ensure path starts with "/" for proper resource loading
        String resourcePath = path.startsWith("/") ? path : "/${path}"
        final res = Report.class.getResourceAsStream(resourcePath)
        
        if (res == null) {
            throw new FileNotFoundException("Resource not found: ${resourcePath}")
        }
        
        try {
            int ch
            while ((ch = res.read()) != -1) {
                writer.append(ch as char)
            }
            return writer.toString()
        } finally {
            if (res != null) {
                res.close()
            }
        }
    }

    /**
     * Write the data to a CSV file
     * @param data
     * @param csvFile
     */
    static void writeCsv(List<Map<String, Object>> data, String csvFile) {
        def csv = new StringBuilder()
        if (data.size() == 0) {
            log.info("No data to write in the metalog CSV file")
            return
        }
        // TODO: there has to be a CSV write in Groovy
        // Write headers
        def headers = data[0].keySet()
        csv.append(headers.join(","))
        csv.append("\n")

        // Write rows
        data.each { row ->
            def values = headers.collect { header -> row[header] }
            csv.append(values.join(","))
            csv.append("\n")
        }

        Files.write(Paths.get(csvFile), csv.toString().getBytes())
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
