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

package ebi.plugin.storage

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.processor.TaskId
import nextflow.trace.TraceRecord


@Slf4j
@CompileStatic
class MemoryStorageBackend implements StorageBackend {

    // % cannot be a column/key name in SQLite; apply the same mapping here for consistency
    private static final Map<String, String> COLUMN_MAPPING = ['%cpu': 'cpu_percent', '%mem': 'mem_percent']

    private final Map<TaskId, Map<String, String>> taskEvents = new LinkedHashMap<>()
    private boolean closed = false

    MemoryStorageBackend() {
        log.info "Memory storage backend initialized"
    }

    @Override
    void initialize() {
        // No initialization needed for in-memory storage
        log.info "Memory database initialized"
    }

    @Override
    void insertOrUpdateTaskEvent(String runName, String groupId, TraceRecord trace) {
        if (closed) {
            log.warn "Attempt to insert into closed memory database"
            return
        }

        try {
            synchronized (taskEvents) {
                def event = new HashMap<String, String>()
                event.put("run_name", runName)
                event.put("group_id", groupId)
                event.put("process", trace.getSimpleName())

                TraceRecord.FIELDS
                        .findAll { name, _ -> name != 'process' && trace?.get(name) != null }
                        .each { name, _ ->
                            def colName = COLUMN_MAPPING.getOrDefault(name, name)
                            event.put(colName, trace.get(name).toString())
                        }

                taskEvents[trace.taskId] = event
                log.debug "Inserted task event to memory backend for groupId={}", groupId
            }
        } catch (Exception e) {
            log.error "Error inserting task event to memory backend: {}", e.message, e
        }
    }

    @Override
    List<Map<String, String>> fetchAllData(String runName) {
        if (closed) {
            log.warn "Attempt to fetch from closed memory database"
            return []
        }

        try {
            synchronized (taskEvents) {
                return taskEvents
                        .collect { _, data -> data }
            }
        } catch (Exception e) {
            log.error "Error fetching data from memory backend: {}", e.message, e
            return []
        }
    }

    @Override
    void close() {
        if (closed) {
            log.warn "Memory database already closed"
            return
        }

        try {
            synchronized (taskEvents) {
                taskEvents.clear()
                closed = true
                log.info "Memory database closed, all data cleared"
            }
        } catch (Exception e) {
            log.error "Error closing memory backend: {}", e.message, e
        }
    }

    @Override
    boolean isClosed() {
        return closed
    }

    /**
     * Get the current size of the in-memory database
     */
    int getSize() {
        synchronized (taskEvents) {
            return taskEvents.size()
        }
    }
}