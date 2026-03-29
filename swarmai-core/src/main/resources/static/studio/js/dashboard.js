/**
 * SwarmAI Studio - Dashboard Charts
 * Chart.js-based visualizations for metrics and token usage.
 */
class DashboardCharts {

    constructor() {
        /** @type {Map<string, Chart>} */
        this._charts = new Map();
    }

    /* ============================================================
       KPI Overview Cards
       ============================================================ */

    /**
     * Renders the four KPI cards into the target container.
     * @param {string} containerId - DOM element ID
     * @param {object} metrics - {totalWorkflows, totalEvents, totalTokens, successRate, estimatedCostUsd}
     */
    renderOverviewCards(containerId, metrics) {
        const el = document.getElementById(containerId);
        if (!el) return;

        const m = metrics || {};
        const totalWorkflows = m.totalWorkflows != null ? m.totalWorkflows : 0;
        const successRate = m.successRate != null ? m.successRate : 0;
        const totalTokens = m.totalTokens != null ? m.totalTokens : 0;
        const estimatedCost = m.estimatedCostUsd != null ? m.estimatedCostUsd : 0;

        const successColor = successRate >= 90 ? 'kpi-success'
            : successRate >= 70 ? 'kpi-warning'
            : 'kpi-error';

        el.innerHTML = `
            <div class="kpi-card kpi-info">
                <div class="kpi-label">Total Workflows</div>
                <div class="kpi-value">${this._formatNumber(totalWorkflows)}</div>
                <div class="kpi-sub">${this._formatNumber(m.totalEvents || 0)} total events</div>
            </div>
            <div class="kpi-card ${successColor}">
                <div class="kpi-label">Success Rate</div>
                <div class="kpi-value">${successRate.toFixed(1)}%</div>
                <div class="kpi-sub">across all workflows</div>
            </div>
            <div class="kpi-card">
                <div class="kpi-label">Total Tokens</div>
                <div class="kpi-value">${this._formatTokens(totalTokens)}</div>
                <div class="kpi-sub">prompt + completion</div>
            </div>
            <div class="kpi-card kpi-warning">
                <div class="kpi-label">Estimated Cost</div>
                <div class="kpi-value">$${estimatedCost.toFixed(4)}</div>
                <div class="kpi-sub">USD (approx.)</div>
            </div>
        `;
    }

    /* ============================================================
       Token Usage Chart (stacked bar)
       ============================================================ */

    /**
     * Renders a stacked bar chart showing prompt vs completion tokens per workflow.
     * @param {string} containerId - ID of the canvas wrapper element
     * @param {Array<object>} data - [{correlationId, summary: {totalPromptTokens, totalCompletionTokens}}]
     */
    renderTokenChart(containerId, data) {
        const wrapper = document.getElementById(containerId);
        if (!wrapper) return;

        this._destroyChart(containerId);

        if (!data || data.length === 0) {
            wrapper.innerHTML = '<div class="empty-state-small">No token data available</div>';
            return;
        }

        wrapper.innerHTML = '<canvas></canvas>';
        const canvas = wrapper.querySelector('canvas');
        const ctx = canvas.getContext('2d');

        const labels = data.map(w => this._truncateId(w.correlationId || w.swarmId || 'unknown'));
        const promptTokens = data.map(w => {
            const s = w.summary || {};
            return s.totalPromptTokens || 0;
        });
        const completionTokens = data.map(w => {
            const s = w.summary || {};
            return s.totalCompletionTokens || 0;
        });

        const chart = new Chart(ctx, {
            type: 'bar',
            data: {
                labels: labels,
                datasets: [
                    {
                        label: 'Prompt Tokens',
                        data: promptTokens,
                        backgroundColor: 'rgba(79, 195, 247, 0.7)',
                        borderColor: 'rgba(79, 195, 247, 1)',
                        borderWidth: 1,
                        borderRadius: 4,
                    },
                    {
                        label: 'Completion Tokens',
                        data: completionTokens,
                        backgroundColor: 'rgba(171, 71, 188, 0.7)',
                        borderColor: 'rgba(171, 71, 188, 1)',
                        borderWidth: 1,
                        borderRadius: 4,
                    }
                ]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    legend: {
                        position: 'top',
                        labels: {
                            color: '#9ca3af',
                            font: { size: 11 },
                            padding: 16,
                            usePointStyle: true,
                            pointStyleWidth: 10,
                        }
                    },
                    tooltip: {
                        backgroundColor: '#16213e',
                        borderColor: '#1e3a5f',
                        borderWidth: 1,
                        titleColor: '#e0e0e0',
                        bodyColor: '#9ca3af',
                        padding: 12,
                        callbacks: {
                            label: function(ctx) {
                                return ctx.dataset.label + ': ' + ctx.parsed.y.toLocaleString();
                            }
                        }
                    }
                },
                scales: {
                    x: {
                        stacked: true,
                        ticks: { color: '#6b7280', font: { size: 10 } },
                        grid: { color: 'rgba(255,255,255,0.04)' },
                    },
                    y: {
                        stacked: true,
                        ticks: {
                            color: '#6b7280',
                            font: { size: 10 },
                            callback: function(value) {
                                if (value >= 1000) return (value / 1000).toFixed(1) + 'k';
                                return value;
                            }
                        },
                        grid: { color: 'rgba(255,255,255,0.04)' },
                    }
                }
            }
        });

        this._charts.set(containerId, chart);
    }

    /* ============================================================
       Latency Chart (horizontal bar)
       ============================================================ */

    /**
     * Renders a horizontal bar chart showing execution time per task/workflow.
     * @param {string} containerId - ID of the canvas wrapper element
     * @param {Array<object>} data - [{label, durationMs}]
     */
    renderLatencyChart(containerId, data) {
        const wrapper = document.getElementById(containerId);
        if (!wrapper) return;

        this._destroyChart(containerId);

        if (!data || data.length === 0) {
            wrapper.innerHTML = '<div class="empty-state-small">No latency data available</div>';
            return;
        }

        wrapper.innerHTML = '<canvas></canvas>';
        const canvas = wrapper.querySelector('canvas');
        const ctx = canvas.getContext('2d');

        const sorted = [...data].sort((a, b) => (b.durationMs || 0) - (a.durationMs || 0));
        const labels = sorted.map(d => d.label || 'Unknown');
        const durations = sorted.map(d => (d.durationMs || 0) / 1000);

        // Color based on duration
        const colors = durations.map(d => {
            if (d > 30) return 'rgba(239, 83, 80, 0.7)';
            if (d > 10) return 'rgba(255, 167, 38, 0.7)';
            return 'rgba(102, 187, 106, 0.7)';
        });

        const borderColors = durations.map(d => {
            if (d > 30) return 'rgba(239, 83, 80, 1)';
            if (d > 10) return 'rgba(255, 167, 38, 1)';
            return 'rgba(102, 187, 106, 1)';
        });

        const chart = new Chart(ctx, {
            type: 'bar',
            data: {
                labels: labels,
                datasets: [{
                    label: 'Duration (seconds)',
                    data: durations,
                    backgroundColor: colors,
                    borderColor: borderColors,
                    borderWidth: 1,
                    borderRadius: 4,
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                indexAxis: 'y',
                plugins: {
                    legend: { display: false },
                    tooltip: {
                        backgroundColor: '#16213e',
                        borderColor: '#1e3a5f',
                        borderWidth: 1,
                        titleColor: '#e0e0e0',
                        bodyColor: '#9ca3af',
                        padding: 12,
                        callbacks: {
                            label: function(ctx) {
                                const ms = ctx.parsed.x * 1000;
                                return DashboardCharts._formatDurationStatic(ms);
                            }
                        }
                    }
                },
                scales: {
                    x: {
                        ticks: {
                            color: '#6b7280',
                            font: { size: 10 },
                            callback: function(value) {
                                return value + 's';
                            }
                        },
                        grid: { color: 'rgba(255,255,255,0.04)' },
                    },
                    y: {
                        ticks: { color: '#9ca3af', font: { size: 11 } },
                        grid: { display: false },
                    }
                }
            }
        });

        this._charts.set(containerId, chart);
    }

    /* ============================================================
       Event Distribution Doughnut Chart
       ============================================================ */

    /**
     * Renders a doughnut chart of event type distribution.
     * @param {string} containerId
     * @param {object} distribution - {TASK_COMPLETED: 5, AGENT_STARTED: 3, ...}
     */
    renderEventDistributionChart(containerId, distribution) {
        const wrapper = document.getElementById(containerId);
        if (!wrapper) return;

        this._destroyChart(containerId);

        if (!distribution || Object.keys(distribution).length === 0) {
            wrapper.innerHTML = '<div class="empty-state-small">No event data</div>';
            return;
        }

        wrapper.innerHTML = '<canvas></canvas>';
        const canvas = wrapper.querySelector('canvas');
        const ctx = canvas.getContext('2d');

        const labels = Object.keys(distribution);
        const values = Object.values(distribution);

        const palette = [
            '#4fc3f7', '#66bb6a', '#ffa726', '#ef5350', '#ab47bc',
            '#42a5f5', '#78909c', '#ffca28', '#26a69a', '#ec407a'
        ];

        const chart = new Chart(ctx, {
            type: 'doughnut',
            data: {
                labels: labels.map(l => l.replace(/_/g, ' ')),
                datasets: [{
                    data: values,
                    backgroundColor: labels.map((_, i) => palette[i % palette.length] + 'cc'),
                    borderColor: labels.map((_, i) => palette[i % palette.length]),
                    borderWidth: 1,
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                cutout: '60%',
                plugins: {
                    legend: {
                        position: 'right',
                        labels: {
                            color: '#9ca3af',
                            font: { size: 10 },
                            padding: 8,
                            usePointStyle: true,
                            pointStyleWidth: 8,
                        }
                    },
                    tooltip: {
                        backgroundColor: '#16213e',
                        borderColor: '#1e3a5f',
                        borderWidth: 1,
                        titleColor: '#e0e0e0',
                        bodyColor: '#9ca3af',
                        padding: 12,
                    }
                }
            }
        });

        this._charts.set(containerId, chart);
    }

    /* ============================================================
       Token Breakdown Pie Chart (for Token Dashboard)
       ============================================================ */

    /**
     * Renders a pie chart for prompt vs completion token breakdown.
     * @param {string} containerId
     * @param {number} promptTokens
     * @param {number} completionTokens
     */
    renderTokenBreakdownChart(containerId, promptTokens, completionTokens) {
        const wrapper = document.getElementById(containerId);
        if (!wrapper) return;

        this._destroyChart(containerId);

        if (!promptTokens && !completionTokens) {
            wrapper.innerHTML = '<div class="empty-state-small">No token data</div>';
            return;
        }

        wrapper.innerHTML = '<canvas></canvas>';
        const canvas = wrapper.querySelector('canvas');
        const ctx = canvas.getContext('2d');

        const chart = new Chart(ctx, {
            type: 'doughnut',
            data: {
                labels: ['Prompt Tokens', 'Completion Tokens'],
                datasets: [{
                    data: [promptTokens || 0, completionTokens || 0],
                    backgroundColor: [
                        'rgba(79, 195, 247, 0.8)',
                        'rgba(171, 71, 188, 0.8)'
                    ],
                    borderColor: [
                        'rgba(79, 195, 247, 1)',
                        'rgba(171, 71, 188, 1)'
                    ],
                    borderWidth: 2,
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                cutout: '55%',
                plugins: {
                    legend: {
                        position: 'bottom',
                        labels: {
                            color: '#9ca3af',
                            font: { size: 12 },
                            padding: 20,
                            usePointStyle: true,
                        }
                    },
                    tooltip: {
                        backgroundColor: '#16213e',
                        borderColor: '#1e3a5f',
                        borderWidth: 1,
                        titleColor: '#e0e0e0',
                        bodyColor: '#9ca3af',
                        padding: 12,
                        callbacks: {
                            label: function(ctx) {
                                const total = ctx.dataset.data.reduce((a, b) => a + b, 0);
                                const pct = total > 0 ? ((ctx.parsed / total) * 100).toFixed(1) : 0;
                                return ctx.label + ': ' + ctx.parsed.toLocaleString() + ' (' + pct + '%)';
                            }
                        }
                    }
                }
            }
        });

        this._charts.set(containerId, chart);
    }

    /* ============================================================
       Cost Over Time Line Chart
       ============================================================ */

    /**
     * Renders a line chart of token cost per workflow.
     * @param {string} containerId
     * @param {Array<object>} workflows - [{correlationId, summary}]
     * @param {number} costPerPromptToken
     * @param {number} costPerCompletionToken
     */
    renderCostChart(containerId, workflows, costPerPromptToken, costPerCompletionToken) {
        const wrapper = document.getElementById(containerId);
        if (!wrapper) return;

        this._destroyChart(containerId);

        costPerPromptToken = costPerPromptToken || 0.000003;
        costPerCompletionToken = costPerCompletionToken || 0.000015;

        if (!workflows || workflows.length === 0) {
            wrapper.innerHTML = '<div class="empty-state-small">No cost data available</div>';
            return;
        }

        wrapper.innerHTML = '<canvas></canvas>';
        const canvas = wrapper.querySelector('canvas');
        const ctx = canvas.getContext('2d');

        const labels = workflows.map(w => this._truncateId(w.correlationId || w.swarmId || ''));
        const costs = workflows.map(w => {
            const s = w.summary || {};
            const promptCost = (s.totalPromptTokens || 0) * costPerPromptToken;
            const completionCost = (s.totalCompletionTokens || 0) * costPerCompletionToken;
            return promptCost + completionCost;
        });

        // Running total
        let cumulative = 0;
        const cumulativeCosts = costs.map(c => {
            cumulative += c;
            return cumulative;
        });

        const chart = new Chart(ctx, {
            type: 'line',
            data: {
                labels: labels,
                datasets: [{
                    label: 'Cumulative Cost ($)',
                    data: cumulativeCosts,
                    borderColor: '#ffa726',
                    backgroundColor: 'rgba(255, 167, 38, 0.1)',
                    fill: true,
                    tension: 0.3,
                    pointRadius: 4,
                    pointBackgroundColor: '#ffa726',
                    pointBorderColor: '#16213e',
                    pointBorderWidth: 2,
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    legend: {
                        labels: {
                            color: '#9ca3af',
                            font: { size: 11 },
                            usePointStyle: true,
                        }
                    },
                    tooltip: {
                        backgroundColor: '#16213e',
                        borderColor: '#1e3a5f',
                        borderWidth: 1,
                        titleColor: '#e0e0e0',
                        bodyColor: '#9ca3af',
                        padding: 12,
                        callbacks: {
                            label: function(ctx) {
                                return 'Total: $' + ctx.parsed.y.toFixed(6);
                            }
                        }
                    }
                },
                scales: {
                    x: {
                        ticks: { color: '#6b7280', font: { size: 10 } },
                        grid: { color: 'rgba(255,255,255,0.04)' },
                    },
                    y: {
                        ticks: {
                            color: '#6b7280',
                            font: { size: 10 },
                            callback: function(value) {
                                return '$' + value.toFixed(4);
                            }
                        },
                        grid: { color: 'rgba(255,255,255,0.04)' },
                    }
                }
            }
        });

        this._charts.set(containerId, chart);
    }

    /* ============================================================
       Cleanup
       ============================================================ */

    /**
     * Destroys a single chart by container ID.
     * @param {string} containerId
     */
    _destroyChart(containerId) {
        const chart = this._charts.get(containerId);
        if (chart) {
            chart.destroy();
            this._charts.delete(containerId);
        }
    }

    /**
     * Destroys all active charts. Call this when switching views.
     */
    destroyAll() {
        for (const [id, chart] of this._charts) {
            chart.destroy();
        }
        this._charts.clear();
    }

    /* ============================================================
       Formatting helpers
       ============================================================ */

    _formatNumber(n) {
        if (n == null) return '0';
        return Number(n).toLocaleString();
    }

    _formatTokens(n) {
        if (n == null) return '0';
        if (n >= 1000000) return (n / 1000000).toFixed(2) + 'M';
        if (n >= 1000) return (n / 1000).toFixed(1) + 'K';
        return n.toLocaleString();
    }

    _truncateId(id) {
        if (!id) return '?';
        if (id.length <= 12) return id;
        return id.substring(0, 8) + '...';
    }

    static _formatDurationStatic(ms) {
        if (ms == null) return '-';
        if (ms < 1000) return ms + 'ms';
        const seconds = Math.floor(ms / 1000);
        const minutes = Math.floor(seconds / 60);
        if (minutes === 0) return seconds + 's';
        return minutes + 'm ' + (seconds % 60) + 's';
    }
}
