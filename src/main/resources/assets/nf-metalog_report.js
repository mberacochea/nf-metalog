/**
 * nf-metalog Report Template
 *
 * Heavily inspired by Nextflow's reports but adapted for per-sample focus.
 * The code this is inspired from was licensed under Apache 2.0.
 * @license Apache 2.0
 */

// Global variables
let samplesTable = null;
let tasksTable = null;

/**
 * Expand columnar data {cols, rows} back to an array of objects.
 * @param {{cols: string[], rows: any[][]}} columnar
 * @returns {Object[]}
 */
function expandColumnar({ cols = [], rows = [] } = {}) {
    if (!Array.isArray(cols) || !Array.isArray(rows)) return [];
    return rows.map(row => Object.fromEntries(cols.map((col, i) => [col, Array.isArray(row) ? row[i] : undefined])));
}

/**
 * Format memory values in human-readable units
 * @param {number} bytes - Memory value in bytes
 * @param {string} type - Type of formatting ('display' or 'sort')
 * @returns {string|number} - Formatted memory string or raw bytes
 */
function formatMemory(bytes, type = 'display') {
    if (type === 'sort') return bytes;
    if (!bytes || bytes === '-' || bytes === 0) return bytes;

    const units = ['B', 'kB', 'MB', 'GB', 'TB', 'PB'];
    const i = bytes === 0 ? 0 : Math.floor(Math.log(bytes) / Math.log(1024));
    return (bytes / Math.pow(1024, i)).toFixed(2) * 1 + ' ' + units[i];
}

/**
 * Format duration values in human-readable format
 * @param {number} ms - Duration in milliseconds
 * @param {string} type - Type of formatting ('display' or 'sort')
 * @returns {string|number} - Formatted duration string or raw value for sorting
 */
function formatDuration(ms, type = 'display') {
    if (type === 'sort') return parseInt(ms || 0);
    if (!ms || ms === '-' || ms === 0) return ms;

    let seconds = Math.floor(ms / 1000);
    const days = Math.floor(seconds / 86400);
    seconds %= 86400;
    const hours = Math.floor(seconds / 3600);
    seconds %= 3600;
    const minutes = Math.floor(seconds / 60);
    seconds %= 60;

    if (days > 0) return `${days}d ${hours}h`;
    if (hours > 0) return `${hours}h ${minutes}m`;
    if (minutes > 0) return `${minutes}m ${seconds}s`;
    return `${seconds}.${Math.floor((ms % 1000) / 100)}s`;
}

/**
 * Format date timestamps in human-readable format
 * @param {number} timestamp - Unix timestamp in milliseconds
 * @param {string} type - Type of formatting ('display' or 'sort')
 * @returns {string} - Formatted date string
 */
function formatDate(timestamp, type = 'display') {
    if (type === 'sort') return timestamp;
    if (!timestamp || timestamp === '-' || timestamp === 0) return timestamp;

    return new Date(parseInt(timestamp)).toISOString().replace('T', ' ').replace(/\..+/, '');
}

// TODO: Implement aggregation, for example if the same sample was chunked and the same tool executed multiples times

function getUniqueSamples(data) {
    if (!Array.isArray(data)) return [];
    return [...new Set(data.map(t => t?.group_id).filter(Boolean))].sort();
}

function countTasksByStatus(data, sample) {
    const counts = {completed: 0, cached: 0, failed: 0, unknown: 0};
    if (!data || !sample) return counts;

    data.filter(t => t?.group_id === sample).forEach(task => {
        const status = (task.status || 'unknown').toLowerCase();
        if (counts.hasOwnProperty(status)) counts[status]++;
        else counts.unknown++;
    });
    return counts;
}

function updateOverviewStats() {
    const data = window.nfMetalogData || [];
    const samples = getUniqueSamples(data);
    document.getElementById('sample-count').textContent = samples.length;
    document.getElementById('total-tasks').textContent = data.length;
}

/**
 * Initialize DataTables for samples and tasks
 * Sets up interactive tables with pagination and sample selection
 *
 * @function initializeTables
 */
function initializeTables() {
    const data = window.nfMetalogData || [];
    const samples = getUniqueSamples(data);

    if (samples.length === 0) {
        document.getElementById('samples-table').innerHTML = `
            <div class="alert alert-info">
                <strong>No samples found</strong>
                <p class="mb-0">The workflow data doesn't contain any sample information (group_id field).</p>
            </div>`;
        document.getElementById('sample-count').textContent = '0';
        return;
    }

    const samplesData = samples.map(sample => {
        const {completed, cached, failed} = countTasksByStatus(data, sample);
        return {
            sample_id: sample,
            total_tasks: completed + cached + failed,
            completed,
            failed
        };
    });

    samplesTable = $('#samples-table').DataTable({
        data: samplesData,
        columns: [
            {title: "Sample ID", data: "sample_id"},
            {title: "Total Tasks", data: "total_tasks"},
            {title: "Completed", data: "completed"},
            {title: "Failed", data: "failed"}
        ],
        pageLength: 10,
        dom: 'lrtip',
        order: [[0, 'asc']],
        responsive: true,
        autoWidth: false
    });

    $('#samples-table').on('click', 'tbody tr', function () {
        const row = samplesTable.row(this);
        $(this).toggleClass('selected').siblings().removeClass('selected');
        if ($(this).hasClass('selected')) {
            selectSample(row.data().sample_id);
        }
    });

    updateTasksTable();
}

// Select sample and update views
function selectSample(sampleId) {
    document.getElementById('current-sample').textContent = sampleId;
    createCharts(sampleId);
    updateTasksTable(sampleId);
}

/**
 * Update tasks table with sample filter
 * Filters and displays task data based on selected sample
 *
 * @function updateTasksTable
 * @param {string|null} sampleFilter - Sample ID to filter by, or null for all tasks
 */
function updateTasksTable(sampleFilter = null) {
    let filteredData = window.nfMetalogData || [];

    if (sampleFilter) {
        filteredData = filteredData.filter(task => task?.group_id === sampleFilter);
    }

    if (filteredData.length === 0) {
        document.getElementById('tasks-table').innerHTML = `
            <div class="alert alert-info">
                <strong>No tasks found</strong>
                <p class="mb-0">No task data available for the selected sample.</p>
            </div>`;
        return;
    }

    if (tasksTable) {
        tasksTable.destroy();
        $('#tasks-table').empty();
    }

    const columnDefinitions = [
        {title: 'name', data: 'process', className: 'id'},
        {
            title: 'status', data: 'status', className: 'id', render: (data, type) => {
                if (type !== 'display') return data;
                const status = (data || '').toLowerCase();
                const badgeMap = {completed: 'success', failed: 'danger', cached: 'warning'};
                return `<span class="badge bg-${badgeMap[status] || 'secondary'}">${status}</span>`;
            }
        },
        {title: 'sample', data: 'group_id', className: 'id'},
        {title: 'exit', data: 'exit', className: 'id'},
        {title: 'submit', data: 'submit', className: 'metrics', render: formatDate},
        {title: 'start', data: 'start', className: 'metrics', render: formatDate},
        {title: 'complete', data: 'complete', className: 'metrics', render: formatDate},
        {title: 'duration', data: 'duration', className: 'metrics', render: formatDuration},
        {title: '%cpu', data: 'cpu_percent', className: 'metrics'},
        {title: '%mem', data: 'mem_percent', className: 'metrics'},
        {
            title: 'memory',
            data: 'memory',
            className: 'metrics',
            render: (data, type) => data ? formatMemory(data * 1024 * 1024, type) : data
        },
        {title: 'peak_rss', data: 'peak_rss', className: 'metrics', render: formatMemory},
        {title: 'peak_vmem', data: 'peak_vmem', className: 'metrics', render: formatMemory},
        {title: 'rchar', data: 'rchar', className: 'metrics', render: formatMemory},
        {title: 'wchar', data: 'wchar', className: 'metrics', render: formatMemory},
        {title: 'syscr', data: 'syscr', className: 'metrics', render: formatMemory},
        {title: 'syscw', data: 'syscw', className: 'metrics', render: formatMemory},
        {title: 'read_bytes', data: 'read_bytes', className: 'metrics', render: formatMemory},
        {title: 'write_bytes', data: 'write_bytes', className: 'metrics', render: formatMemory},
        {
            title: 'hash',
            data: 'hash',
            className: 'metrics',
            render: (data, type) => (type === 'display' && data) ? data.substring(0, 8) + '...' : data
        },
        {title: 'container', data: 'container', className: 'metrics', render: d => `<samp>${d}</samp>`},
        {title: 'disk', data: 'disk', className: 'metrics', render: d => d ?? "-"},
        {title: 'attempt', data: 'attempt', className: 'metrics'},
        {title: 'scratch', data: 'scratch', className: 'metrics', render: d => `<samp>${d}</samp>`},
        {title: 'workdir', data: 'workdir', className: 'metrics', render: d => `<samp>${d}</samp>`}
    ];

    tasksTable = $('#tasks-table').DataTable({
        data: filteredData,
        columns: columnDefinitions,
        pageLength: 20,
        dom: 'lrtip',
        scrollX: true,
        scrollCollapse: true,
        deferRender: true,
        responsive: true,
        autoWidth: false
    });
}

/**
 * Normalize an array of byte values for Plotly's '.4s' SI tick format.
 * Ported from the Nextflow execution report source (Apache 2.0).
 * @param {number[]} list - Array of byte values
 * @returns {number[]} - Normalized values suitable for SI-prefix display
 */
function norm_mem(list) {
    if (list == null) return null;
    return list.map(value => {
        if (!value) return 0;
        const x = Math.floor(Math.log10(value) / Math.log10(1024));
        if (x === 0) return Math.round(value / 1.024);
        let v = value;
        for (let j = 0; j < x; j++) v = v / 1.024;
        return Math.round(v);
    });
}

const ALL_CHART_IDS = ['cpu-chart', 'mem-physical-chart', 'mem-virtual-chart', 'mem-pct-chart', 'duration-chart', 'io-read-chart', 'io-write-chart'];

/**
 * Create resource usage charts for selected sample.
 * Immediately renders 4 default-visible charts; lazy-renders 3 secondary tab charts
 * on first tab activation (matching the Nextflow execution report pattern).
 *
 * @function createCharts
 * @param {string} sample - Sample ID to create charts for
 */
function createCharts(sample) {
    ALL_CHART_IDS.forEach(id => {
        Plotly.purge(id);
        document.getElementById(id).innerHTML = '';
    });

    if (!sample) {
        document.getElementById('charts-placeholder').classList.remove('d-none');
        document.getElementById('charts-content').classList.add('d-none');
        return;
    }

    document.getElementById('charts-placeholder').classList.add('d-none');
    document.getElementById('charts-content').classList.remove('d-none');
    window._metalogCurrentSample = sample;

    const data = window.nfMetalogData || [];
    const tasks = data.filter(t => t?.group_id === sample);
    if (tasks.length === 0) return;

    const processes = tasks.map(t => t.process);

    const plotBar = (id, yVals, title, yTitle, yFormat) => {
        const traces = processes.map((proc, i) => ({
            x: [proc],
            y: [yVals[i]],
            name: proc,
            type: 'bar',
        }));
        Plotly.newPlot(id, traces, {
            title: {text: title},
            xaxis: {tickangle: -45, automargin: true},
            yaxis: {title: {text: yTitle}, tickformat: yFormat, rangemode: 'tozero'},
            height: 650,
            margin: {t: 60, b: 120, pad: 10},
            plot_bgcolor: '#f8f9fa',
            barmode: 'overlay',
            showlegend: true,
        }, {responsive: true, displaylogo: false});
    };

    // --- Immediately rendered (default-visible tabs) ---

    plotBar('cpu-chart',
        tasks.map(t => t.cpu_percent ?? 0),
        'CPU Usage', '% single core CPU usage', '.1f');

    plotBar('mem-physical-chart',
        norm_mem(tasks.map(t => t.peak_rss ?? 0)),
        'Physical Memory Usage', 'Memory', '.4s');

    plotBar('duration-chart',
        tasks.map(t => t.duration ? (parseInt(t.duration) / 60000).toFixed(1) : 0),
        'Task execution real-time', 'Execution time (minutes)', '.1f');

    plotBar('io-read-chart',
        norm_mem(tasks.map(t => t.read_bytes ?? 0)),
        'Number of bytes read', 'Read bytes', '.4s');

    // --- Lazy-rendered (secondary tabs) ---

    $('#mem-virtual-tab').off('shown.bs.tab').on('shown.bs.tab', function () {
        if ($('#mem-virtual-chart').is(':empty')) {
            plotBar('mem-virtual-chart',
                norm_mem(tasks.map(t => t.peak_vmem ?? 0)),
                'Virtual Memory Usage', 'Memory', '.4s');
        }
    });

    $('#mem-pct-tab').off('shown.bs.tab').on('shown.bs.tab', function () {
        if ($('#mem-pct-chart').is(':empty')) {
            plotBar('mem-pct-chart',
                tasks.map(t => t.mem_percent ?? 0),
                '% Requested Physical Memory Used', '% Memory', '.1f');
        }
    });

    $('#io-write-tab').off('shown.bs.tab').on('shown.bs.tab', function () {
        if ($('#io-write-chart').is(':empty')) {
            plotBar('io-write-chart',
                norm_mem(tasks.map(t => t.write_bytes ?? 0)),
                'Number of bytes written', 'Written bytes', '.4s');
        }
    });
}

// ===============//
// Initialization //
// ===============//

$(document).ready(() => {
    if (window.nfMetalogData?.cols) {
        window.nfMetalogData = expandColumnar(window.nfMetalogData);
    }
    const data = window.nfMetalogData || [];
    console.log(`nf-metalog report initialized with ${getUniqueSamples(data).length} samples and ${data.length} tasks`);

    initializeTables();
    updateOverviewStats();
});