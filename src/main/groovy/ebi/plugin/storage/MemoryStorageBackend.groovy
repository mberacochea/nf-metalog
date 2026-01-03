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
import nextflow.processor.TaskHandler
import nextflow.trace.TraceRecord

@Slf4j
@CompileStatic
class MemoryStorageBackend implements StorageBackend {

    private final List<Map<String, Object>> taskEvents = new ArrayList<>()
    private final Object lock = new Object()
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
    void insertOrUpdateTaskEvent(String runName, String groupId, TaskHandler handler, TraceRecord trace) {
        if (closed) {
            log.warn "Attempt to insert into closed memory database"
            return
        }

        try {
            synchronized (lock) {
                // For memory storage, we'll just append new events
                def event = createTaskEventMap(runName, groupId, handler, trace)
                taskEvents.add(event)
                log.debug "Inserted task event to memory backend for groupId={}", groupId
            }
        } catch (Exception e) {
            log.error "Error inserting task event to memory backend: {}", e.message, e
        }
    }

    @Override
    List<Map<String, Object>> fetchAllData(String runName) {
        if (closed) {
            log.warn "Attempt to fetch from closed memory database"
            return []
        }

        try {
            synchronized (lock) {
                return taskEvents
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
            synchronized (lock) {
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
     * Create a task event map from handler and trace objects
     */
    private Map<String, Object> createTaskEventMap(String runName, String groupId, TaskHandler handler, TraceRecord trace) {
        def event = new HashMap<String, Object>()
        
        // Extract basic information
        event.put("run_name", runName)
        event.put("group_id", groupId)
        event.put("process_name", trace?.getSimpleName() ?: "unknown")  // Use getSimpleName() like SQLite
        event.put("status", handler?.status?.toString() ?: "unknown")
        
        // Extract all trace fields dynamically (similar to SqliteDatabaseService)
        if (trace != null) {
            TraceRecord.FIELDS.each { name, _type ->
                Object value = trace.get(name)
                if (value != null) {
                    event.put(name, value.toString())
                }
            }
        }
        
        // Add current timestamp
        event.put("timestamp", new Date().toString())
        
        return event
    }

    /**
     * Get the current size of the in-memory database
     */
    int getSize() {
        synchronized (lock) {
            return taskEvents.size()
        }
    }
}