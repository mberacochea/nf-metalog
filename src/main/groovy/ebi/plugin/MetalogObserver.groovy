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

import java.nio.file.Path

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import nextflow.Session
import nextflow.trace.TraceObserverV2
import nextflow.trace.event.TaskEvent


@Slf4j
@CompileStatic
class MetalogObserver implements TraceObserverV2 {

    private final Session session
    private final String groupByKey
    private final String runName
    private final DatabaseService databaseService

    MetalogObserver(Session session, MetalogConfig config) {
        this.session = session
        this.runName = session.runName

        // For now, hardcode groupBy to 'id' (from meta.id)
        this.groupByKey = config.groupKey

        // Initialize database service with SQLite
        final dbFileName = config.sqlite?.file ?: 'metalog.db'
        final dbFile = (session.workDir as Path).resolve(dbFileName)
        this.databaseService = new SqliteDatabaseService(dbFile)

        this.databaseService.initialize()

        log.info "Metalog observer initialized: runName={}, groupBy={}, dbFile={}", this.runName, this.groupByKey, dbFileName
    }

    @Override
    void onFlowCreate(Session session) {
        log.info "Metalog: Workflow created"
    }

    @Override
    void onTaskSubmit(TaskEvent event) {
        handleTaskEvent(event)
    }

    @Override
    void onTaskComplete(TaskEvent event) {
        handleTaskEvent(event)
    }

    @Override
    void onTaskCached(TaskEvent event) {
        handleTaskEvent(event)
    }

    @Override
    void onFlowError(TaskEvent event) {
        handleTaskEvent(event)
    }

    /**
     * Common handler for task events (submit, complete, cached, error)
     */
    private void handleTaskEvent(TaskEvent event) {
        try {
            // Extract grouping ID from inputs
            final String groupId = extractGroupId(event)
            if (groupId == null) {
                // Skip this task, we can't find the grouping key (meta.id - is the default)
                return
            }

            databaseService.insertOrUpdateTaskEvent(runName, groupId, event.handler, event.trace)
            log.debug("Row inserted to database for task {} with id={}", event?.handler?.task?.name ?: "unknown", groupId)

        } catch (Exception e) {
            log.error("Error processing task {}: {}", event?.handler?.task?.name ?: "unknown", e.message, e)
        }
    }

    @Override
    void onFlowComplete() {
        log.info 'Metalog: closing the db connection.'
        try {
            if (databaseService == null) {
                log.error "The databaseService is null, that really shouldn't be happening."
            } else {
                // Generate the HTML report before closing the database
                log.info 'Metalog: generating HTML report.'
                GenerateMetalogHtml.generate(databaseService, session.getWorkflowMetadata())
                databaseService.close()
            }

        } catch (Exception e) {
            log.error("Error closing database connection: {}", e.message, e)
        }
    }

    /**
     * Extracts the grouping ID (usually the meta.id) from task inputs.
     * Expects first input to be a tuple with a Map as the first element.
     * If the inputs don't have that, we ignore them... this may be revisited in the future.
     */
    private String extractGroupId(TaskEvent event) {
        try {
            final task = event.handler.task
            final inputs = task.inputs
            if (!inputs || inputs.isEmpty()) {
                log.debug("Task {} has no inputs, skipping", task?.name ?: "unknown")
                return null
            }

            // Inputs is a Map where keys are like "valueinparam<0:0>", "valueinparam<0:1>", etc.
            // Find the first tuple element (valueinparam<0:0>) which should be the meta map
            Object meta = null
            for (Map.Entry<?, Object> entry : inputs.entrySet()) {
                String key = entry.getKey().toString()
                // Look for the first element of the tuple (index 0:0)
                if (key.contains('valueinparam<0:0>') || key.contains('param<0:0>')) {
                    meta = entry.getValue()
                    break
                }
            }

            if (meta == null) {
                log.debug("Task {} has no tuple meta input, skipping", task?.name ?: "unknown")
                return null
            }

            // Meta should be a Map
            if (!(meta instanceof Map)) {
                log.debug("Task {} meta is not a Map (type: {}), skipping", task?.name ?: "unknown", meta.getClass())
                return null
            }

            final metaMap = meta as Map
            if (!metaMap.containsKey(groupByKey)) {
                log.warn("Task {} meta map does not contain key '{}', skipping", task?.name ?: "unknown", groupByKey)
                return null
            }

            return metaMap[groupByKey]?.toString()

        } catch (Exception e) {
            log.error("Error extracting group ID from task {}: {}", event?.handler?.task?.name ?: "unknown", e.message, e)
            return null
        }
    }
}
