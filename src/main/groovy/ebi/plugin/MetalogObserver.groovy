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
import ebi.plugin.storage.StorageBackend
import ebi.plugin.storage.SqliteStorageBackend
import ebi.plugin.storage.MemoryStorageBackend

import nextflow.Session
import nextflow.trace.TraceObserverV2
import nextflow.trace.event.TaskEvent


@Slf4j
@CompileStatic
class MetalogObserver implements TraceObserverV2 {

    private final Session session
    private final String groupByKey
    private final String runName
    private final StorageBackend storageBackend
    private final MetalogConfig.ReportConfig reportConfig

    MetalogObserver(Session session, MetalogConfig config) {
        this.session = session
        this.runName = session.runName
        this.groupByKey = config.groupKey
        this.reportConfig = config.report

        // Initialize storage backend based on configuration
        final String storageBackend = config.storageBackend ?: 'sqlite'

        if (storageBackend == 'memory') {
            this.storageBackend = new MemoryStorageBackend()
            log.info "Metalog observer initialized with memory backend: runName={}, groupBy={}", this.runName, this.groupByKey
        } else {
            // Default to SQLite — resolve relative paths against launchDir, keep absolute paths as-is
            final dbFileName = config.sqlite?.file ?: 'metalog.db'
            final dbPath = Path.of(dbFileName)
            final dbFile = dbPath.isAbsolute() ? dbPath : Path.of(System.getProperty("user.dir")).resolve(dbFileName)
            this.storageBackend = new SqliteStorageBackend(dbFile)
            log.info "Metalog observer initialized with SQLite backend: runName={}, groupBy={}, dbFile={}", this.runName, this.groupByKey, dbFile
        }

        this.storageBackend.initialize()
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
        if (event?.handler != null) {
            handleTaskEvent(event)
        }
    }

    /**
     * Common handler for task events (submit, complete, cached, error)
     */
    private void handleTaskEvent(TaskEvent event) {
        try {
            // Extract grouping ID from inputs
            final String groupId = extractGroupId(event)
            if (groupId == null) {
                log.warn("Could not extract group ID for task {}, skipping", event?.handler?.task?.name ?: "unknown")
                return
            }

            storageBackend.insertOrUpdateTaskEvent(runName, groupId, event.trace)
            log.debug("Row inserted to database for task {} with id={}", event?.handler?.task?.name ?: "unknown", groupId)

        } catch (Exception e) {
            log.error("Error processing task {}: {}", event?.handler?.task?.name ?: "unknown", e.message, e)
        }
    }

    @Override
    void onFlowComplete() {
        log.info 'Metalog: closing the db connection.'
        try {
            if (storageBackend == null) {
                log.error "The storageBackend is null, that really shouldn't be happening."
            } else if (storageBackend.isClosed()) {
                log.warn "Metalog: storage backend already closed, skipping report generation."
            } else {
                log.info 'Metalog: generating HTML report.'
                Report.generate(storageBackend, session.getWorkflowMetadata(), reportConfig)
                storageBackend.close()
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
