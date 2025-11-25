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
            while (!shutdown || !eventQueue.isEmpty()) {
                try {
                    final event = eventQueue.poll(100, TimeUnit.MILLISECONDS)
                    if (event != null) {
                        processTaskEvent(event)
                    }
                } catch (InterruptedException ignored) {
                    log.debug "Worker thread interrupted"
                    Thread.currentThread().interrupt()
                    break
                } catch (Exception e) {
                    log.error("Error processing queued event: ${e.message}", e)
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
            final stmt = dbConnection.createStatement()

            // Enable WAL mode for better concurrent access (multiple readers + one writer)
            stmt.execute("PRAGMA journal_mode=WAL")

            // Wait up to 10 seconds when database is locked instead of failing immediately
            stmt.execute("PRAGMA busy_timeout=10000")

            // Faster writes (less fsync) - safe with WAL mode
            stmt.execute("PRAGMA synchronous=NORMAL")

            stmt.close()

            // Create table if it doesn't exist with task_id as primary key
            final createTableSQL = """
                CREATE TABLE IF NOT EXISTS metalog (
                    run_name TEXT NOT NULL,
                    id TEXT NOT NULL,
                    ingested TEXT NOT NULL,
                    process TEXT,
                    task_id TEXT PRIMARY KEY,
                    status TEXT,
                    metadata TEXT
                )
            """.stripIndent()

            dbConnection.createStatement().execute(createTableSQL)
            log.info "SQLite table 'metalog' ready (WAL mode enabled, 30s busy timeout)"

            // Start the worker thread after DB is initialized
            workerThread.start()
            log.info "SQLite worker thread initialized"

        } catch (Exception e) {
            log.error("Error initializing SQLite: ${e.message}", e)
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
                log.warn "SQLite queue size is ${queueSize} - backpressure piling up"
            }
        } catch (InterruptedException e) {
            log.error("Interrupted while queueing task event for ${handler.task.name}: ${e.message}", e)
            Thread.currentThread().interrupt()
        } catch (Exception e) {
            log.error("Error queueing task event for ${handler.task.name}: ${e.message}", e)
        }
    }

    /**
     * Processes a single task event from the queue and performs the upsert
     */
    private void processTaskEvent(TaskEvent event) {
        PreparedStatement stmt = null
        try {
            final String taskId = event.trace.get('task_id')?.toString()
            final JSONObject jsonMetadata = buildTraceJSON(event.trace)

            // Use INSERT OR REPLACE for upsert behavior based on task_id
            // This allows tracking task status transitions (pending -> running -> cached/completed/failed)
            final upsertSQL = """
                INSERT INTO metalog (run_name, ingested, id, process, task_id, status, metadata)
                VALUES (?, datetime('now'), ?, ?, ?, ?, ?)
                ON CONFLICT(task_id) DO UPDATE SET
                    ingested = datetime('now'),
                    status = excluded.status,
                    metadata = excluded.metadata
            """.stripIndent()

            stmt = dbConnection.prepareStatement(upsertSQL)

            stmt.setString(1, event.runName)
            stmt.setString(2, event.groupId)
            stmt.setString(3, event.trace.get('process')?.toString())
            stmt.setString(4, taskId)
            stmt.setString(5, event.trace.get('status')?.toString())
            stmt.setString(6, jsonMetadata.toString())

            stmt.executeUpdate()

        } catch (Exception e) {
            log.error("Error upserting to SQLite for task ${event.taskName}: ${e.message}", e)
        } finally {
            if (stmt != null) {
                try {
                    stmt.close()
                } catch (Exception e) {
                    log.warn("Error closing statement: ${e.message}")
                }
            }
        }
    }


    @Override
    void close() {
        try {
            // Signal shutdown to worker thread
            shutdown = true
            log.info "Signaled shutdown to SQLite worker thread (${eventQueue.size()} events remaining in queue)"

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
            log.error("Interrupted while waiting for worker thread to finish: ${e.message}", e)
            Thread.currentThread().interrupt()
        } catch (Exception e) {
            log.error("Error closing SQLite connection: ${e.message}", e)
            throw e
        }
    }

    /**
     * Builds a JSON object with all TraceRecord fields (except status which is a separate column)
     */
    private JSONObject buildTraceJSON(TraceRecord trace) {
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

        fields.each { field ->
            def value = trace.get(field)
            if (value != null) {
                json.put(field, value)
            }
        }

        return json
    }
}