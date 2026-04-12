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

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import nextflow.trace.TraceRecord

/**
 * SQLite implementation of DatabaseService.
 * Uses a queue with a worker thread to handle backpressure and to prevent the db from having issues with locks
 * and stuff - https://sqlite.org/wal.html
 */
@Slf4j
@CompileStatic
class SqliteStorageBackend implements StorageBackend {

    @CompileStatic
    static class EventRecord {
        String runName
        String groupId
        TraceRecord trace

        EventRecord(String runName, String groupId, TraceRecord trace) {
            this.runName = runName
            this.groupId = groupId
            this.trace = trace
        }
    }

    /**
     * Column mapping for non-sql friendly names
     */
    private static final Map<String, String> COLUMN_MAPPING = [
            '%cpu': 'cpu_percent',
            '%mem': 'mem_percent'
    ]

    private final Path dbFile
    private Connection dbConnection
    private final BlockingQueue<EventRecord> eventQueue
    private final Thread workerThread
    private volatile boolean shutdown = false
    private List<String> cachedAllColumns
    private String cachedUpsertSQL

    SqliteStorageBackend(Path dbFile) {
        this.dbFile = dbFile
        this.eventQueue = new LinkedBlockingQueue<EventRecord>()
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
                    final eventRecord = eventQueue.poll(100, TimeUnit.MILLISECONDS)
                    if (eventRecord != null) {
                        processTaskEvent(eventRecord)
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
            // Create parent directories if needed
            if (dbFile.parent != null && !Files.exists(dbFile.parent)) {
                Files.createDirectories(dbFile.parent)
                log.debug("Created directories: ${dbFile.parent}")
            }

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
                    run_name TEXT NOT NULL, -- The workflow execution run name
                    group_id TEXT NOT NULL, -- The meta key used to group the data
                    task_id TEXT PRIMARY KEY,
                    hash TEXT,
                    native_id TEXT,
                    process TEXT,
                    module TEXT,
                    container TEXT,
                    tag TEXT,
                    name TEXT,
                    status TEXT,
                    exit TEXT,
                    submit INTEGER,  -- Unix timestamp (seconds since epoch)
                    start INTEGER,   -- Unix timestamp
                    complete INTEGER, -- Unix timestamp
                    duration INTEGER, -- Duration in milliseconds
                    realtime INTEGER, -- Duration in milliseconds
                    cpu_percent REAL, -- Percentage value (0-100+)
                    mem_percent REAL, -- Percentage value (0-100)
                    rss INTEGER,      -- Memory in bytes
                    vmem INTEGER,     -- Memory in bytes
                    peak_rss INTEGER, -- Memory in bytes
                    peak_vmem INTEGER, -- Memory in bytes
                    rchar INTEGER,    -- Memory/bytes read
                    wchar INTEGER,    -- Memory/bytes written
                    syscr INTEGER,    -- Number of read syscalls
                    syscw INTEGER,    -- Number of write syscalls
                    read_bytes INTEGER,  -- Bytes read
                    write_bytes INTEGER, -- Bytes written
                    attempt INTEGER,
                    workdir TEXT,
                    script TEXT,
                    scratch TEXT,
                    queue TEXT,
                    cpus INTEGER,
                    memory INTEGER,   -- Memory in bytes
                    disk INTEGER,     -- Disk space in bytes
                    time INTEGER,     -- Time in milliseconds
                    env TEXT,
                    error_action TEXT,
                    vol_ctxt INTEGER, -- Voluntary context switches
                    inv_ctxt INTEGER, -- Involuntary context switches
                    hostname TEXT,
                    cpu_model TEXT
                )
            """.stripIndent()

            dbConnection.createStatement().withCloseable { stmt ->
                stmt.execute(createTableSQL)
            }
            log.info "SQLite table 'metalog' ready (WAL mode enabled, 10s busy timeout)"

            // Cache column list and upsert SQL — TraceRecord.FIELDS is static so this never changes
            def traceColumns = TraceRecord.FIELDS.keySet().collect { field ->
                COLUMN_MAPPING.getOrDefault(field, field)
            }
            cachedAllColumns = ['run_name', 'group_id'] + traceColumns
            def columnList = cachedAllColumns.join(', ')
            def placeholders = (['?'] * cachedAllColumns.size()).join(', ')
            def updateClauses = cachedAllColumns
                    .findAll { it != 'task_id' }
                    .collect { "${it} = excluded.${it}" }
                    .join(', ')
            cachedUpsertSQL = """
                INSERT INTO metalog (${columnList})
                VALUES (${placeholders})
                ON CONFLICT(task_id) DO UPDATE SET ${updateClauses}
            """.stripIndent()

            // Start the worker thread after DB is initialized
            workerThread.start()
            log.info "SQLite worker thread initialized"

        } catch (Exception e) {
            log.error("Error initializing SQLite: {}", e.message, e)
            throw e
        }
    }

    @Override
    void insertOrUpdateTaskEvent(String runName, String groupId, TraceRecord trace) {
        try {
            final eventRecord = new EventRecord(runName, groupId, trace)

            // Add event to queue for async processing
            eventQueue.put(eventRecord)

            final queueSize = eventQueue.size()
            if (queueSize > 100 && queueSize % 100 == 0) {
                log.warn "SQLite queue size is {} - backpressure piling up", queueSize
            }
        } catch (InterruptedException e) {
            log.error("Interrupted while queueing task event.", e)
            Thread.currentThread().interrupt()
        } catch (Exception e) {
            log.error("Error queueing task event.", e)
        }
    }

    /**
     * Processes a single task event from the queue and performs the upsert
     */
    private void processTaskEvent(EventRecord eventRecord) {
        String runName = eventRecord.runName
        String groupId = eventRecord.groupId
        TraceRecord traceRecord = eventRecord.trace

        def traceValues = TraceRecord.FIELDS.keySet().collect { field ->
            field == "process" ? traceRecord.getSimpleName() : traceRecord.get(field)?.toString()
        }
        def allValues = [runName, groupId] + traceValues

        dbConnection.prepareStatement(cachedUpsertSQL).withCloseable { PreparedStatement stmt ->
            // JDBC parameters are 1-indexed, not 0-indexed like arrays
            allValues.eachWithIndex { value, index ->
                stmt.setObject(index + 1, value)
            }
            stmt.executeUpdate()
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
     * Fetch all task records for a given run, returning each row as a column→value map.
     *
     * @param runName The Nextflow run name
     * @return A list of maps, one per task row, ready for CSV or HTML report generation
     */
    List<Map<String, String>> fetchAllData(String runName) {
        List<Map<String, String>> result = []

        if (dbConnection == null || dbConnection.isClosed()) {
            throw new IllegalStateException("Database connection is not open")
        }

        def columnList = cachedAllColumns.join(', ')

        final query = """
            SELECT ${columnList}
            FROM metalog
            WHERE run_name = ?
        """.stripIndent()

        try {
            dbConnection.prepareStatement(query).withCloseable { stmt ->
                stmt.setString(1, runName)
                stmt.executeQuery().withCloseable { ResultSet rs ->
                    while (rs.next()) {
                        Map<String, String> row = [:]
                        cachedAllColumns.each { column ->
                            row[column] = rs.getString(column)
                        }
                        result.add(row)
                    }
                }
            }
            return result
        } catch (Exception e) {
            log.error("Error fetching data: {}, no nf-metalog report will be generated.", e.message, e)
            return []
        }
    }

    @Override
    boolean isClosed() {
        try {
            return dbConnection == null || dbConnection.isClosed()
        } catch (SQLException e) {
            log.error("Error checking if SQLite connection is closed: {}", e.message, e)
            return true
        }
    }
}