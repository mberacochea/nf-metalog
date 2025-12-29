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
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.json.JSONObject

import nextflow.processor.TaskHandler
import nextflow.trace.TraceRecord

/**
 * SQLite implementation of DatabaseService.
 * Uses a queue with a worker thread to handle backpressure and to prevent the db from having issues with locks
 * and stuff - https://sqlite.org/wal.html
 */
@Slf4j
@CompileStatic
class SqliteDatabaseService implements DatabaseService {

    private static class TaskEvent {
        final String runName
        final String groupId
        final String taskName
        final TraceRecord trace

        TaskEvent(String runName, String groupId, String taskName, TraceRecord trace) {
            this.runName = runName
            this.groupId = groupId
            this.taskName = taskName
            this.trace = trace
        }
    }

    private final Path dbFile
    private Connection dbConnection
    private final BlockingQueue<TaskEvent> eventQueue
    private final Thread workerThread
    private volatile boolean shutdown = false

    SqliteDatabaseService(Path dbFile) {
        this.dbFile = dbFile
        this.eventQueue = new LinkedBlockingQueue<TaskEvent>()
        this.workerThread = createWorkerThread()
    }

    /**
     * Creates and starts the worker thread that processes events from the queue
     */
    private Thread createWorkerThread() {
        final thread = new Thread({
            log.info "SQLite worker thread started"
            // We let the queue be drained after being shutdown
            while (!shutdown || !eventQueue.isEmpty()) {
                try {
                    final event = eventQueue.poll(100, TimeUnit.MILLISECONDS)
                    if (event != null) {
                        processTaskEvent(event)
                    }
                } catch (InterruptedException ignored) {
                    log.debug "Worker thread interrupted"
                    Thread.currentThread().interrupt()
                } catch (Exception e) {
                    log.error("Error processing queued event: {}", e.message, e)
                }
            }
            log.info "SQLite worker thread finished, processed all queued events"
        } as Runnable, "metalog-sqlite-worker")
        thread.setDaemon(false)  // Not a daemon so it finishes processing queue on shutdown
        return thread
    }

    @Override
    void initialize() {
        try {
            // Load SQLite JDBC driver
            Class.forName('org.sqlite.JDBC')

            // Create connection
            this.dbConnection = DriverManager.getConnection("jdbc:sqlite:${dbFile.toString()}")

            // Configure SQLite for concurrent access
            dbConnection.createStatement().withCloseable { stmt ->
                // Enable WAL mode for better concurrent access (multiple readers + one writer)
                stmt.execute("PRAGMA journal_mode=WAL")

                // Wait up to 10 seconds when database is locked instead of failing immediately
                stmt.execute("PRAGMA busy_timeout=10000")

                // Faster writes (less fsync) - safe with WAL mode
                stmt.execute("PRAGMA synchronous=NORMAL")
            }

            // Create table if it doesn't exist with task_id as primary key
            final createTableSQL = """
                CREATE TABLE IF NOT EXISTS metalog (
                    run_name TEXT NOT NULL,
                    group_id TEXT NOT NULL,
                    ingested TEXT NOT NULL,
                    process TEXT,
                    task_id TEXT PRIMARY KEY,
                    status TEXT,
                    metadata TEXT
                )
            """.stripIndent()

            dbConnection.createStatement().withCloseable { stmt ->
                stmt.execute(createTableSQL)
            }
            log.info "SQLite table 'metalog' ready (WAL mode enabled, 30s busy timeout)"

            // Start the worker thread after DB is initialized
            workerThread.start()
            log.info "SQLite worker thread initialized"

        } catch (Exception e) {
            log.error("Error initializing SQLite: {}", e.message, e)
            throw e
        }
    }

    @Override
    void insertOrUpdateTaskEvent(String runName, String groupId, TaskHandler handler, TraceRecord trace) {
        try {
            final taskName = handler.task.name
            final event = new TaskEvent(runName, groupId, taskName, trace)

            // Add event to queue for async processing
            eventQueue.put(event)

            final queueSize = eventQueue.size()
            if (queueSize > 100 && queueSize % 100 == 0) {
                log.warn "SQLite queue size is {} - backpressure piling up", queueSize
            }
        } catch (InterruptedException e) {
            log.error("Interrupted while queueing task event for {}: {}", handler?.task?.name ?: "unknown", e.message, e)
            Thread.currentThread().interrupt()
        } catch (Exception e) {
            log.error("Error queueing task event for {}: {}", handler?.task?.name ?: "unknown", e.message, e)
        }
    }

    /**
     * Processes a single task event from the queue and performs the upsert
     */
    private void processTaskEvent(TaskEvent event) {
        try {
            final String taskId = event.trace.get('task_id')?.toString()
            final JSONObject jsonMetadata = buildTraceJSON(event.trace)

            // Use INSERT OR REPLACE for upsert behavior based on task_id
            // This allows tracking task status transitions (pending -> running -> cached/completed/failed)
            final upsertSQL = """
                INSERT INTO metalog (run_name, ingested, group_id, process, task_id, status, metadata)
                VALUES (?, datetime('now'), ?, ?, ?, ?, ?)
                ON CONFLICT(task_id) DO UPDATE SET
                    ingested = datetime('now'),
                    status = excluded.status,
                    metadata = excluded.metadata
            """.stripIndent()

            dbConnection.prepareStatement(upsertSQL).withCloseable { PreparedStatement stmt ->
                stmt.setString(1, event.runName)
                stmt.setString(2, event.groupId)
                stmt.setString(3, event.trace.get('process')?.toString())
                stmt.setString(4, taskId)
                stmt.setString(5, event.trace.get('status')?.toString())
                stmt.setString(6, jsonMetadata.toString())

                stmt.executeUpdate()
            }

        } catch (Exception e) {
            log.error("Error upserting to SQLite for task {}: {}", event?.taskName ?: "unknown", e.message, e)
        }
    }


    @Override
    void close() {
        try {
            // Signal shutdown to worker thread
            shutdown = true
            log.info "Signaled shutdown to SQLite worker thread ({} events remaining in queue)", eventQueue.size()

            // Wait for worker thread to finish processing all queued events
            if (workerThread != null && workerThread.isAlive()) {
                workerThread.join(10000)  // Wait up to 10 seconds
                if (workerThread.isAlive()) {
                    log.error "SQLite worker thread did not finish within 10 seconds, interrupting"
                    workerThread.interrupt()
                }
            }

            // Close database connection
            if (dbConnection != null && !dbConnection.isClosed()) {
                dbConnection.close()
                log.info "SQLite connection closed"
            }
        } catch (InterruptedException e) {
            log.error("Interrupted while waiting for worker thread to finish: {}", e.message, e)
            Thread.currentThread().interrupt()
        } catch (Exception e) {
            log.error("Error closing SQLite connection: {}", e.message, e)
            throw e
        }
    }

    /**
     * Builds a JSON object with all TraceRecord fields (except status which is a separate column)
     */
    private static JSONObject buildTraceJSON(TraceRecord trace) {
        final json = new JSONObject()

        // Add all TraceRecord fields to JSON (status is excluded as it's a separate column)
        final fields = [
            'hash', 'native_id', 'tag', 'name', 'exit',
            'submit', 'start', 'complete', 'duration', 'realtime',
            '%cpu', 'peak_rss', 'peak_vmem', 'rchar', 'wchar',
            'syscr', 'syscw', 'read_bytes', 'write_bytes',
            '%mem', 'vmem', 'rss', 'cpu_model', 'container',
            'attempt', 'workdir', 'scratch', 'queue',
            'cpus', 'memory', 'disk', 'time'
        ]

        fields.each { String field ->
            Object value = trace.get(field)
            if (value != null) {
                json.put(field, value)
            }
        }

        return json
    }

    /**
     * Extracts all metadata from the JSON column into separate columns for CSV export.
     * This provides a complete flattened view of all task metadata fields.
     * 
     * @return A list of maps where each map represents a row with all metadata extracted
     *         into separate columns, ready for CSV writing or other data processing
     */
    List<Map<String, Object>> fetchAllData() {
        try {
            if (dbConnection == null || dbConnection.isClosed()) {
                throw new IllegalStateException("Database connection is not open")
            }

            // Clean, readable SQL query that extracts all metadata fields from JSON
            final query = """
                SELECT 
                    run_name,
                    group_id,
                    ingested,
                    process,
                    task_id,
                    status,
                    -- Task identification and execution metadata
                    json_extract(metadata, '\$.hash') as hash,
                    json_extract(metadata, '\$.native_id') as native_id,
                    json_extract(metadata, '\$.tag') as tag,
                    json_extract(metadata, '\$.name') as name,
                    json_extract(metadata, '\$.exit') as exit,
                    json_extract(metadata, '\$.submit') as submit,
                    json_extract(metadata, '\$.start') as start,
                    json_extract(metadata, '\$.complete') as complete,
                    json_extract(metadata, '\$.duration') as duration,
                    json_extract(metadata, '\$.realtime') as realtime,
                    -- Resource usage metrics
                    json_extract(metadata, '\$.cpu') as cpu,
                    json_extract(metadata, '\$.peak_rss') as peak_rss,
                    json_extract(metadata, '\$.peak_vmem') as peak_vmem,
                    json_extract(metadata, '\$.rchar') as rchar,
                    json_extract(metadata, '\$.wchar') as wchar,
                    json_extract(metadata, '\$.syscr') as syscr,
                    json_extract(metadata, '\$.syscw') as syscw,
                    json_extract(metadata, '\$.read_bytes') as read_bytes,
                    json_extract(metadata, '\$.write_bytes') as write_bytes,
                    json_extract(metadata, '\$.mem') as mem,
                    json_extract(metadata, '\$.vmem') as vmem,
                    json_extract(metadata, '\$.rss') as rss,
                    -- Environment and execution context
                    json_extract(metadata, '\$.container') as container,
                    json_extract(metadata, '\$.attempt') as attempt,
                    json_extract(metadata, '\$.workdir') as workdir,
                    json_extract(metadata, '\$.queue') as queue,
                    -- Resource requests
                    json_extract(metadata, '\$.cpus') as cpus,
                    json_extract(metadata, '\$.memory') as memory,
                    json_extract(metadata, '\$.disk') as disk,
                    json_extract(metadata, '\$.time') as time
                FROM metalog
            """

            List<Map<String, Object>> result = []
            dbConnection.createStatement().withCloseable { stmt ->
                stmt.executeQuery(query).withCloseable { ResultSet rs ->
                    while (rs.next()) {
                        Map<String, Object> row = [:]
                        
                        // Basic task information
                        row.run_name = rs.getString("run_name")
                        row.group_id = rs.getString("group_id")
                        row.ingested = rs.getString("ingested")
                        row.process = rs.getString("process")
                        row.task_id = rs.getString("task_id")
                        row.status = rs.getString("status")
                        
                        // Task metadata (all lowercase, consistent naming)
                        row.hash = rs.getString("hash")
                        row.native_id = rs.getString("native_id")
                        row.tag = rs.getString("tag")
                        row.name = rs.getString("name")
                        row.exit = rs.getString("exit")
                        row.submit = rs.getString("submit")
                        row.start = rs.getString("start")
                        row.complete = rs.getString("complete")
                        row.duration = rs.getString("duration")
                        row.realtime = rs.getString("realtime")
                        // Resource usage
                        row.cpu = rs.getString("cpu")
                        row.peak_rss = rs.getString("peak_rss")
                        row.peak_vmem = rs.getString("peak_vmem")
                        row.rchar = rs.getString("rchar")
                        row.wchar = rs.getString("wchar")
                        row.syscr = rs.getString("syscr")
                        row.syscw = rs.getString("syscw")
                        row.read_bytes = rs.getString("read_bytes")
                        row.write_bytes = rs.getString("write_bytes")
                        row.mem = rs.getString("mem")
                        row.vmem = rs.getString("vmem")
                        row.rss = rs.getString("rss")
                        // Environment
                        row.container = rs.getString("container")
                        row.attempt = rs.getString("attempt")
                        row.workdir = rs.getString("workdir")
                        row.queue = rs.getString("queue")
                        // Resource requests
                        row.cpus = rs.getString("cpus")
                        row.memory = rs.getString("memory")
                        row.disk = rs.getString("disk")
                        row.time = rs.getString("time")
                        
                        result.add(row)
                    }
                }
            }
            return result
        } catch (Exception e) {
            log.error("Error fetching data with extracted metadata: {}", e.message, e)
            throw e
        }
    }
}