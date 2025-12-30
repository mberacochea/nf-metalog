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

import groovy.json.JsonBuilder
import groovy.text.GStringTemplateEngine
import nextflow.script.WorkflowMetadata

import java.nio.file.Files
import java.nio.file.Paths
import java.io.StringWriter
import java.io.FileNotFoundException

class GenerateMetalogHtml {

    static void generate(DatabaseService databaseService, WorkflowMetadata workflow) {
        try {
            def csvData = databaseService.fetchAllData(workflow.runName)

            // TODO: the csv file needs to be a parameter
            writeCsv(csvData, "metalog.csv")

            def templateString = readAsset("nf-metalog_report.html")
            def jsAssets = []
            def cssAssets = []
            jsAssets.add(readAsset("assets/plotly-latest.min.js"))
            jsAssets.add(readAsset("assets/gridjs.umd.js"))
            jsAssets.add(readAsset("assets/bootstrap.bundle.min.js"))
            jsAssets.add(readAsset("assets/nf-metalog_report.js"))
            cssAssets.add(readAsset("assets/bootstrap.min.css"))
            cssAssets.add(readAsset("assets/gridjs-mermaid.min.css"))
            cssAssets.add(readAsset("assets/nf-metalog_report.css"))

            def binding = [
                workflow: workflow,
                data: new JsonBuilder( csvData ).toString(),
                js_assets: jsAssets,
                css_assets: cssAssets
            ]

            def engine = new GStringTemplateEngine()
            def template = engine.createTemplate(templateString).make(binding)

            // TODO: the html needs to be parameter too
            Files.write(Paths.get("metalog.html"), template.toString().getBytes())

            println "Successfully generated metalog.html"
        } catch (Exception e) {
            println "Error generating and writing the nf-metalog report: ${e.message}"
            e.printStackTrace()
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
        final res = GenerateMetalogHtml.class.getResourceAsStream(resourcePath)
        
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
}
