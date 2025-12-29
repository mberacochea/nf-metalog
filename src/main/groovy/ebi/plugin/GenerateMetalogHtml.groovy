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

class GenerateMetalogHtml {

    static void generate(DatabaseService databaseService, WorkflowMetadata workflow) {
        try {
            def csvData = databaseService.fetchAllData()

            // TODO: the csv file needs to be a parameter
            writeCsv(csvData, "metalog.csv")

            def templateString = getTemplateFromClasspath("report.html")

            def binding = [
                workflow: workflow,
                data: new JsonBuilder( csvData ).toString()
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
     * Pull the template as a string
     * @param templateName
     * @return
     */
    static String getTemplateFromClasspath(String templateName) {
        // Try to load from classpath first
        def classLoader = GenerateMetalogHtml.class.classLoader
        def resource = classLoader.getResource(templateName)

        if (resource != null) {
            return resource.text
        }

        // Fallback to direct file access (for development)
        def file = new File("src/main/resources/${templateName}")
        if (file.exists()) {
            return file.text
        }

        throw new FileNotFoundException("Template ${templateName} not found in classpath or file system")
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
