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

import groovy.transform.CompileStatic
import nextflow.processor.TaskHandler
import nextflow.trace.TraceRecord

/**
 * Interface for database operations in the Metalog plugin.
 * Implementations should provide concrete database access for different database types.
 */
@CompileStatic
interface DatabaseService {

    /**
     * Initialize the database connection and schema
     * @throws Exception if initialization fails
     */
    void initialize()

    /**
     * Insert or update a task event record in the database.
     * Uses task_id as the unique identifier to track task status transitions
     * (pending -> running -> cached/completed/failed).
     *
     * @param runName The Nextflow run name
     * @param groupId The grouping ID (usually the meta.id) extracted from task inputs
     * @param handler The task handler
     * @param trace The trace record containing task metadata including task_id and status
     * @throws Exception if upsert fails
     */
    void insertOrUpdateTaskEvent(String runName, String groupId, TaskHandler handler, TraceRecord trace)

    /**
     * Close the database connection and cleanup resources
     * @throws Exception if close fails
     */
    void close()

    /**
     * Fetch all data from the metalog table with metadata extracted from JSON column.
     * This provides a complete flattened view of all task metadata fields ready for CSV export.
     * @return A list of maps representing the rows with all metadata extracted into separate columns
     * @throws Exception if fetching data fails
     */
    List<Map<String, Object>> fetchAllData()
}
