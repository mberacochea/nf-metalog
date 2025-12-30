/**
 * nf-metalog Report Template
 *
 * Heavily inspired by Nextflow's reports but adapted for per-sample focus.
 * From of the code was taken from
 *
 * @version 1.0
 * @license Apache 2.0
 */

// ============================================
// DATA INITIALIZATION
// ============================================

// Global variables
let currentSample = null;
let samplesGrid = null;
let tasksGrid = null;

/**
 * Normalize memory values to appropriate units (bytes -> KB/MB/GB)
 * Inspired by Nextflow's norm_mem function
 * @param {Array} list - Array of memory values in bytes
 * @returns {Array} - Array of normalized memory values
 */
function norm_mem(list) {
    if (list == null) return null;
    var result = new Array(list.length);
    for (var i = 0; i < list.length; i++) {
        var value = list[i];
        var x = Math.floor(Math.log10(value) / Math.log10(1024));
        if (x == 0) {
            value = value / 1.024;
        } else {
            for (var j = 0; j < x; j++) {
                value = value / 1.024;
            }
        }
        result[i] = Math.round(value);
    }
    return result;
}

/**
 * Format memory values in human-readable units
 * @param {number} bytes - Memory value in bytes
 * @returns {string} - Formatted memory string (e.g., "1.25 GB")
 */
function formatMemory(bytes) {
    if (bytes == '-' || bytes == 0 || bytes == null) {
        return bytes;
    }

    var thresh = 1024;
    if (Math.abs(bytes) < thresh) {
        return bytes + ' B';
    }

    var units = ['kB', 'MB', 'GB', 'TB', 'PB', 'EB', 'ZB', 'YB'];
    var u = -1;
    do {
        bytes /= thresh;
        ++u;
    } while (Math.abs(bytes) >= thresh && u < units.length - 1);

    return bytes.toFixed(3) + ' ' + units[u];
}

/**
 * Format duration values in human-readable format
 * @param {number} ms - Duration in milliseconds
 * @param {string} type - Type of formatting ('display' or 'sort')
 * @returns {string|number} - Formatted duration string or raw value for sorting
 */
function formatDuration(ms, type = 'display') {
    if (type === 'sort') {
        return parseInt(ms);
    }

    if (ms == '-' || ms == 0 || ms == null) {
        return ms;
    }

    // Convert to seconds for moment-like duration handling
    var duration = parseInt(ms) / 1000;

    var days = Math.floor(duration / 86400);
    duration -= days * 86400;
    var hours = Math.floor(duration / 3600);
    duration -= hours * 3600;
    var minutes = Math.floor(duration / 60);
    duration -= minutes * 60;
    var seconds = Math.floor(duration);

    if (days > 0) {
        return days + "d " + hours + "h";
    }
    if (hours > 0) {
        return hours + "h " + minutes + "m";
    }
    if (minutes > 0) {
        return minutes + "m " + seconds + "s";
    }
    return seconds + "." + Math.floor((ms % 1000) / 100) + "s";
}

/**
 * Format date timestamps in human-readable format
 * @param {number} timestamp - Unix timestamp in milliseconds
 * @returns {string} - Formatted date string
 */
function formatDate(timestamp) {
    if (timestamp == '-' || timestamp == 0 || timestamp == null) {
        return timestamp;
    }

    var date = new Date(parseInt(timestamp));
    return date.toISOString().replace('T', ' ').replace('Z', '');
}

// TODO: Implement aggregation, for example if the same sample was chunked and the same tool executed multiples times

// Function to extract unique samples
function getUniqueSamples(data) {
    if (!data || !Array.isArray(data)) {
        console.warn('getUniqueSamples: Invalid data format', data);
        return [];
    }

    console.log('getUniqueSamples: Processing', data.length, 'tasks');

    const samples = {};
    let tasksWithGroupId = 0;
    let tasksWithoutGroupId = 0;

    data.forEach((task, index) => {
        if (task && task.group_id) {
            samples[task.group_id] = true;
            tasksWithGroupId++;
        } else {
            tasksWithoutGroupId++;
            if (tasksWithoutGroupId <= 3) { // Log first few missing ones
                console.warn('Task without group_id:', task);
            }
        }
    });

    const uniqueSamples = Object.keys(samples).sort();
    console.log('getUniqueSamples: Found', uniqueSamples.length, 'unique samples from', tasksWithGroupId, 'tasks with group_id');

    if (tasksWithoutGroupId > 0) {
        console.warn('Warning:', tasksWithoutGroupId, 'tasks missing group_id field');
    }

    return uniqueSamples;
}

// Function to count tasks by status for a sample
function countTasksByStatus(data, sample) {
    if (!data || !Array.isArray(data) || !sample) {
        console.warn('countTasksByStatus: Invalid parameters', { data, sample });
        return { completed: 0, cached: 0, failed: 0, unknown: 0 };
    }

    const sampleTasks = data.filter(task => task && task.group_id === sample);
    const counts = { completed: 0, cached: 0, failed: 0, unknown: 0 };

    sampleTasks.forEach(task => {
        const status = task.status || 'unknown';
        if (counts.hasOwnProperty(status)) {
            counts[status]++;
        } else {
            counts.unknown++;
        }
    });

    return counts;
}

// Update overview statistics
function updateOverviewStats() {
    const samples = getUniqueSamples(window.nfMetalogData);
    const totalTasks = window.nfMetalogData;

    console.log('Data statistics:', {
        totalTasks: totalTasks,
        uniqueSamples: samples,
        sampleCount: samples.length
    });
    document.getElementById('sample-count').textContent = samples.length;
    document.getElementById('total-tasks').textContent = totalTasks;
}

/**
 * Initialize GridJS tables for samples and tasks
 * Sets up interactive tables with pagination and sample selection
 *
 * @function initializeTables
 */
function initializeTables() {

    const samples = getUniqueSamples(window.nfMetalogData);
    if (!samples || samples.length === 0) {
        console.warn('No samples found in data');
        return;
    }

    // Samples table with click-to-select
    const samplesData = samples.map(sample => {
        const counts = countTasksByStatus(window.nfMetalogData, sample);
        return [
            sample,
            counts.completed + counts.cached + counts.failed,
            counts.completed,
            counts.failed
        ];
    });

    // Handle empty samples case
    if (samplesData.length === 0) {
        console.warn('No samples found - displaying empty state');
        
        // Show a message instead of empty table
        const samplesGridContainer = document.getElementById('samples-grid');
        samplesGridContainer.innerHTML = `
            <div class="alert alert-info">
                <strong>No samples found</strong>
                <p class="mb-0">The workflow data doesn't contain any sample information (group_id field).</p>
                <p class="mb-0 small text-muted">This could be because:</p>
                <ul class="small text-muted mb-0">
                    <li>No tasks have been executed yet</li>
                    <li>The workflow doesn't use sample grouping</li>
                    <li>Data loading issue - check browser console for details</li>
                </ul>
            </div>
        `;
        
        // Also update the overview to show 0 samples
        document.getElementById('sample-count').textContent = '0';
        
        return; // Exit early since there are no samples to display
    }

    samplesGrid = new gridjs.Grid({
        columns: ["Sample ID", "Total Tasks", "Completed", "Failed"],
        data: samplesData,
        search: true,
        sort: true,
        pagination: {
            enabled: true,
            limit: 10
        },
        style: {
            table: {
                'white-space': 'nowrap'
            },
            th: {
                'background-color': '#f8f9fa',
                'font-weight': '600'
            }
        }
    });

    // Use GridJS built-in events instead of manual event listeners
    samplesGrid.on('rowClick', (event, row) => {
        // Extract sample ID from the first cell
        const sampleId = row.cells[0].data;
        console.log('Sample selected via GridJS event:', sampleId);

        // Remove selection from all rows first
        document.querySelectorAll('.gridjs-tr.selected-row').forEach(row => {
            row.classList.remove('selected-row');
        });

        // Add selection to clicked row (we need to find the DOM element)
        const rowElement = event.target.closest('.gridjs-tr');
        if (rowElement) {
            rowElement.classList.add('selected-row');
        }
        // Select the sample (this will handle charts and tasks)
        selectSample(sampleId);
    });

    samplesGrid.render(document.getElementById('samples-grid'));

    // Initialize tasks table (empty at first)
    updateTasksTable();
}

// Reapply selection highlighting after pagination or other DOM changes
function reapplySelectionHighlight() {
    if (currentSample) {
        // Remove selection from all rows first
        document.querySelectorAll('.gridjs-tr.selected-row').forEach(row => {
            row.classList.remove('selected-row');
        });

        // Find and highlight the selected row if it's visible
        const allRows = document.querySelectorAll('.gridjs-tr:not(.gridjs-header)');
        allRows.forEach(row => {
            const rowSample = row.querySelector('.gridjs-td:nth-child(1)')?.textContent;
            if (rowSample === currentSample) {
                row.classList.add('selected-row');
            }
        });
    }
}

// Select sample and update views
function selectSample(sampleId) {
    currentSample = sampleId;
    document.getElementById('current-sample').textContent = sampleId;

    // Update charts and tasks table
    createCharts(sampleId);
    updateTasksTable(sampleId);

    // Reapply selection highlighting in case of pagination
    reapplySelectionHighlight();
}

/**
 * Update tasks table with sample filter
 * Filters and displays task data based on selected sample
 *
 * @function updateTasksTable
 * @param {string|null} sampleFilter - Sample ID to filter by, or null for all tasks
 */
function updateTasksTable(sampleFilter = null) {

    let filteredData = window.nfMetalogData;

    if (sampleFilter) {
        filteredData = filteredData.filter(task => task && task.group_id === sampleFilter);
    }

    if (!filteredData || filteredData.length === 0) {
        console.log('No tasks found for filter:', sampleFilter);
        // TODO: Show empty state in table
        return;
    }

    // Get the first element to get the columns
    const columns = Object.keys(filteredData[0])

    if (tasksGrid) {
        tasksGrid.updateConfig({
            data: filteredData
        }).forceRender();
    } else {
        tasksGrid = new gridjs.Grid({
            columns: columns,
            data: filteredData,
            search: true,
            sort: true,
            pagination: {
                enabled: true,
                limit: 20
            },
            style: {
                table: {
                    'width': '100%'
                },
                th: {
                    'background-color': '#f8f9fa',
                    'font-weight': '600'
                }
            }
        });

        tasksGrid.render(document.getElementById('tasks-grid'));
    }
}

/**
 * Create resource usage charts for selected sample
 * Generates Plotly bar charts for CPU, Memory, Duration, and Disk usage
 *
 * @function createCharts
 * @param {string} sample - Sample ID to create charts for
 */
function createCharts(sample) {
    // Show loading states
    document.getElementById('cpu-chart').innerHTML = '<div class="chart-loading"></div>';
    document.getElementById('memory-chart').innerHTML = '<div class="chart-loading"></div>';
    document.getElementById('duration-chart').innerHTML = '<div class="chart-loading"></div>';
    document.getElementById('disk-chart').innerHTML = '<div class="chart-loading"></div>';

    if (!sample) {
        throw new Error('No sample provided for chart creation');
    }

    const sampleTasks = window.nfMetalogData.filter(task => task && task.group_id === sample);

    if (!sampleTasks || sampleTasks.length === 0) {
        console.warn('No tasks found for sample:', sample);
    }

    if (sampleTasks.length === 0) {
        document.getElementById('cpu-chart').innerHTML = '<p class="text-muted">No task data available for this sample.</p>';
        document.getElementById('memory-chart').innerHTML = '';
        document.getElementById('duration-chart').innerHTML = '';
        document.getElementById('disk-chart').innerHTML = '';
        return;
    }

    // Helper function to filter out zero/empty values and create chart data
    function createChartData(tasks, valueKey, title, yAxisTitle, color) {
        if (!tasks || !Array.isArray(tasks) || tasks.length === 0) {
            console.warn(`createChartData: No valid tasks data for \${valueKey}`);
            return { hasData: false };
        }

        const nonZeroTasks = tasks.filter(task => task && task[valueKey] && task[valueKey] > 0);

        if (nonZeroTasks.length === 0) {
            console.log(`createChartData: No non-zero data for \${valueKey}`);
            return { hasData: false };
        }

        // Create enhanced tooltips with task details
        const hoverText = nonZeroTasks.map(task => {
            return `
                <b>\${task.name}</b><br>
                Status: \${task.status || 'N/A'}<br>
                Sample: \${task.group_id || 'N/A'}
                `;
        });

        return {
            hasData: true,
            data: [{
                x: nonZeroTasks.map(task => task.name),
                y: nonZeroTasks.map(task => task[valueKey] || 0),
                type: 'bar',
                marker: {
                    color: color,
                    line: {
                        color: '#ffffff',
                        width: 1
                    }
                },
                hovertemplate: hoverText.map((text, i) => {
                    return text + '<br>%{y:.2f} ' + yAxisTitle + '<extra></extra>';
                }),
                text: nonZeroTasks.map(task => task[valueKey] || 0),
                textposition: 'outside',
                textfont: {
                    size: 10,
                    color: color
                }
            }],
            layout: {
                title: {
                    text: title,
                    font: {
                        size: 16,
                        family: 'Arial, sans-serif'
                    }
                },
                xaxis: {
                    title: 'Task',
                    tickangle: -45,
                    automargin: true
                },
                yaxis: {
                    title: yAxisTitle,
                    titlefont: {
                        size: 14
                    }
                },
                margin: {t: 60, b: 100, l: 60, r: 20},
                hovermode: 'closest',
                plot_bgcolor: '#f8f9fa',
                paper_bgcolor: '#ffffff',
                font: {
                    family: 'Arial, sans-serif'
                }
            },
            config: {
                responsive: true,
                displayModeBar: true,
                displaylogo: false
            }
        };
    }

    // Create CPU chart
    const cpuChart = createChartData(sampleTasks, 'cpu', 'CPU Usage', 'CPU Usage', '#198754');
    if (cpuChart.hasData) {
        Plotly.newPlot('cpu-chart', cpuChart.data, cpuChart.layout, cpuChart.config);
    } else {
        document.getElementById('cpu-chart').innerHTML = '<p class="text-muted">No CPU data available</p>';
    }

    // Create Memory chart
    const memoryChart = createChartData(sampleTasks, 'memory', 'Memory Usage', 'Memory (MB)', '#0d6efd');
    if (memoryChart.hasData) {
        Plotly.newPlot('memory-chart', memoryChart.data, memoryChart.layout, memoryChart.config);
    } else {
        document.getElementById('memory-chart').innerHTML = '<p class="text-muted">No memory data available</p>';
    }

    // Create Duration chart
    const durationChart = createChartData(sampleTasks, 'duration', 'Task Duration', 'Duration (ms)', '#ffc107');
    if (durationChart.hasData) {
        Plotly.newPlot('duration-chart', durationChart.data, durationChart.layout, durationChart.config);
    } else {
        document.getElementById('duration-chart').innerHTML = '<p class="text-muted">No duration data available</p>';
    }

    // Create Disk chart
    const diskChart = createChartData(sampleTasks, 'disk', 'Disk Usage', 'Disk (MB)', '#0dcaf0');
    if (diskChart.hasData) {
        Plotly.newPlot('disk-chart', diskChart.data, diskChart.layout, diskChart.config);
    } else {
        document.getElementById('disk-chart').innerHTML = '<p class="text-muted">No disk data available</p>';
    }
}

// Initialize when DOM is loaded
document.addEventListener('DOMContentLoaded', function() {
    console.log('nf-metalog report initialized', {
        samples: window.nfMetalogData ? getUniqueSamples(window.nfMetalogData).length : 0,
        totalTasks: window.nfMetalogData ? window.nfMetalogData.length : 0
    });

    initializeTables();
    updateOverviewStats();

    // Set up mutation observer to handle pagination changes
    // TODO: this needs to be done with gridjs API
    const samplesGridContainer = document.getElementById('samples-grid');
    const observer = new MutationObserver(function(mutations) {
        mutations.forEach(function(mutation) {
            if (mutation.addedNodes.length > 0) {
                // DOM has changed (likely due to pagination)
                reapplySelectionHighlight();
            }
        });
    });

    // Start observing the grid container for changes
    observer.observe(samplesGridContainer, {
        childList: true,
        subtree: true
    });
});
