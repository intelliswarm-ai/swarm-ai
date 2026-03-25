/**
 * SwarmAI Studio - Main Application
 * Single-page application with hash routing, SSE client, and view management.
 */
class StudioApp {

    constructor() {
        /** @type {DashboardCharts} */
        this.charts = new DashboardCharts();
        /** @type {WorkflowGraph|null} */
        this.graph = null;
        /** @type {EventTimeline|null} */
        this.timeline = null;
        /** @type {EventSource|null} */
        this._sse = null;
        /** @type {boolean} */
        this._sseConnected = false;
        /** @type {number|null} */
        this._sseRetryTimer = null;
        /** @type {number} */
        this._sseRetryDelay = 3000;
        /** @type {string} */
        this._currentView = '';
        /** @type {string|null} */
        this._currentWorkflowId = null;
        /** @type {Array<object>} Sidebar event buffer (most recent first) */
        this._sidebarEvents = [];
        /** @type {number} Max sidebar events */
        this._maxSidebarEvents = 30;
        /** @type {AbortController|null} */
        this._activeFetch = null;

        this._init();
    }

    /* ============================================================
       Initialization
       ============================================================ */

    _init() {
        // Hash router
        window.addEventListener('hashchange', () => this._onHashChange());

        // Task panel close
        document.getElementById('taskPanelClose').addEventListener('click', () => this._closeTaskPanel());
        document.getElementById('taskPanelOverlay').addEventListener('click', () => this._closeTaskPanel());

        // Keyboard shortcut: Escape closes panel
        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') this._closeTaskPanel();
        });

        // Start SSE
        this.connectSSE();

        // Initial route
        this._onHashChange();
    }

    /* ============================================================
       Hash Router
       ============================================================ */

    /**
     * Parses the current hash and renders the correct view.
     */
    _onHashChange() {
        const hash = window.location.hash || '#/dashboard';
        this.navigate(hash);
    }

    /**
     * Navigates to a hash route, parsing params and rendering the view.
     * @param {string} hash
     */
    navigate(hash) {
        // Cancel in-flight fetches
        if (this._activeFetch) {
            this._activeFetch.abort();
            this._activeFetch = null;
        }

        // Cleanup previous view
        this.charts.destroyAll();
        if (this.graph) {
            this.graph.destroy();
            this.graph = null;
        }
        this._closeTaskPanel();

        // Parse route
        const path = hash.replace(/^#/, '');

        // Update nav active states
        this._updateNav(path);

        if (path === '/' || path === '/dashboard' || path === '') {
            this._currentView = 'dashboard';
            this.renderDashboard();
        } else if (path === '/workflows') {
            this._currentView = 'workflows';
            this.renderWorkflowList();
        } else if (path.startsWith('/workflow/')) {
            const id = path.replace('/workflow/', '');
            this._currentView = 'workflow-detail';
            this._currentWorkflowId = id;
            this.renderWorkflowDetail(id);
        } else if (path === '/tokens') {
            this._currentView = 'tokens';
            this.renderTokenDashboard();
        } else {
            this._currentView = 'dashboard';
            this.renderDashboard();
        }
    }

    _updateNav(path) {
        // Header nav
        document.querySelectorAll('.nav-link').forEach(link => {
            link.classList.remove('active');
            const href = link.getAttribute('href') || '';
            if (path === '/' || path === '/dashboard' || path === '') {
                if (href === '#/dashboard') link.classList.add('active');
            } else if (path.startsWith('/workflow')) {
                if (href === '#/workflows') link.classList.add('active');
            } else if (href === '#' + path) {
                link.classList.add('active');
            }
        });

        // Sidebar nav
        document.querySelectorAll('.sidebar-link').forEach(link => {
            link.classList.remove('active');
            const href = link.getAttribute('href') || '';
            if (path === '/' || path === '/dashboard' || path === '') {
                if (href === '#/dashboard') link.classList.add('active');
            } else if (path.startsWith('/workflow')) {
                if (href === '#/workflows') link.classList.add('active');
            } else if (href === '#' + path) {
                link.classList.add('active');
            }
        });
    }

    /* ============================================================
       API Client
       ============================================================ */

    /**
     * Fetches JSON from the Studio API.
     * @param {string} path - path relative to /api/studio/
     * @returns {Promise<any>}
     */
    async fetchApi(path) {
        const controller = new AbortController();
        this._activeFetch = controller;

        try {
            const response = await fetch('/api/studio/' + path, {
                signal: controller.signal,
                headers: { 'Accept': 'application/json' },
            });

            if (!response.ok) {
                throw new Error('API returned ' + response.status + ' ' + response.statusText);
            }

            const contentType = response.headers.get('content-type') || '';
            if (contentType.includes('text/plain')) {
                return await response.text();
            }
            return await response.json();
        } catch (err) {
            if (err.name === 'AbortError') {
                return null; // Navigation cancelled this request
            }
            throw err;
        } finally {
            if (this._activeFetch === controller) {
                this._activeFetch = null;
            }
        }
    }

    /* ============================================================
       SSE Client
       ============================================================ */

    /**
     * Connects to the SSE event stream.
     */
    connectSSE() {
        this.disconnectSSE();

        try {
            this._sse = new EventSource('/api/studio/events/stream');

            this._sse.onopen = () => {
                this._sseConnected = true;
                this._sseRetryDelay = 3000;
                this._updateConnectionStatus(true);
            };

            this._sse.onmessage = (event) => {
                try {
                    const data = JSON.parse(event.data);
                    this._handleSSEEvent(data);
                } catch (e) {
                    console.warn('[SSE] Failed to parse event:', e);
                }
            };

            this._sse.onerror = () => {
                this._sseConnected = false;
                this._updateConnectionStatus(false);
                this.disconnectSSE();

                // Retry with backoff
                this._sseRetryTimer = setTimeout(() => {
                    this._sseRetryDelay = Math.min(this._sseRetryDelay * 1.5, 30000);
                    this.connectSSE();
                }, this._sseRetryDelay);
            };
        } catch (e) {
            console.warn('[SSE] Failed to connect:', e);
            this._updateConnectionStatus(false);
        }
    }

    /**
     * Disconnects the SSE stream.
     */
    disconnectSSE() {
        if (this._sseRetryTimer) {
            clearTimeout(this._sseRetryTimer);
            this._sseRetryTimer = null;
        }
        if (this._sse) {
            this._sse.close();
            this._sse = null;
        }
        this._sseConnected = false;
    }

    _updateConnectionStatus(connected) {
        const indicator = document.getElementById('connectionIndicator');
        const label = indicator.querySelector('.connection-label');
        const liveDot = document.getElementById('liveDot');
        const liveLabel = document.getElementById('liveLabel');

        if (connected) {
            indicator.classList.add('connected');
            label.textContent = 'Connected';
            if (liveDot) liveDot.classList.add('active');
            if (liveLabel) liveLabel.textContent = 'Live events';
        } else {
            indicator.classList.remove('connected');
            label.textContent = 'Disconnected';
            if (liveDot) liveDot.classList.remove('active');
            if (liveLabel) liveLabel.textContent = 'Events paused';
        }
    }

    /**
     * Handles a parsed SSE event.
     * @param {object} event
     */
    _handleSSEEvent(event) {
        // Update sidebar feed
        this._addSidebarEvent(event);

        // Update current view if applicable
        if (this._currentView === 'dashboard') {
            // Append to live feed on dashboard
            const liveFeed = document.getElementById('dashboardLiveFeed');
            if (liveFeed) {
                this._appendLiveFeedEvent(liveFeed, event);
            }
        }

        if (this._currentView === 'workflow-detail' && this._currentWorkflowId) {
            // Check if event belongs to this workflow
            const eventCorrelation = event.correlationId || '';
            if (eventCorrelation === this._currentWorkflowId) {
                // Append to timeline
                if (this.timeline) {
                    this.timeline.appendEvent(event);
                    // Update event count display
                    const countEl = document.getElementById('timelineEventCount');
                    if (countEl) countEl.textContent = this.timeline.eventCount + ' events';
                }
                // Update graph node if possible
                if (this.graph && event.taskId) {
                    const status = this._inferStatusFromEvent(event);
                    if (status) {
                        this.graph.updateNodeStatus(event.taskId, status);
                    }
                }
            }
        }
    }

    _inferStatusFromEvent(event) {
        const type = (event.type || event.eventType || '').toUpperCase();
        if (type.includes('COMPLETED') || type.includes('PASSED')) return 'completed';
        if (type.includes('STARTED')) return 'running';
        if (type.includes('FAILED')) return 'failed';
        return null;
    }

    _addSidebarEvent(event) {
        this._sidebarEvents.unshift(event);
        if (this._sidebarEvents.length > this._maxSidebarEvents) {
            this._sidebarEvents = this._sidebarEvents.slice(0, this._maxSidebarEvents);
        }

        const feed = document.getElementById('sidebarEventFeed');
        if (!feed) return;

        const type = event.type || event.eventType || 'EVENT';
        const category = this._eventCategory(type);
        const msg = event.message || '';

        const html = `
            <div class="sidebar-event-item event-${category}">
                <div class="event-type">${this._escape(type.replace(/_/g, ' '))}</div>
                <div class="event-msg">${this._escape(this._truncate(msg, 60))}</div>
            </div>
        `;

        // Replace empty state if present
        const emptyState = feed.querySelector('.empty-state-small');
        if (emptyState) emptyState.remove();

        feed.insertAdjacentHTML('afterbegin', html);

        // Trim excess
        while (feed.children.length > this._maxSidebarEvents) {
            feed.removeChild(feed.lastChild);
        }
    }

    /* ============================================================
       VIEW: Dashboard
       ============================================================ */

    async renderDashboard() {
        const main = document.getElementById('mainContent');
        main.innerHTML = `
            <div class="view-header">
                <h1>Dashboard</h1>
                <p>Runtime overview of SwarmAI agent workflows</p>
            </div>
            <div class="kpi-grid" id="dashboardKpis">
                <div class="kpi-card"><div class="kpi-label">Loading...</div><div class="kpi-value">--</div></div>
                <div class="kpi-card"><div class="kpi-label">Loading...</div><div class="kpi-value">--</div></div>
                <div class="kpi-card"><div class="kpi-label">Loading...</div><div class="kpi-value">--</div></div>
                <div class="kpi-card"><div class="kpi-label">Loading...</div><div class="kpi-value">--</div></div>
            </div>
            <div class="grid-2">
                <div class="card">
                    <div class="card-header">
                        <span class="card-title">Recent Workflows</span>
                    </div>
                    <div id="dashboardWorkflowsTable">
                        <div class="view-loading"><div class="spinner"></div></div>
                    </div>
                </div>
                <div class="card">
                    <div class="card-header">
                        <span class="card-title">Live Event Feed</span>
                    </div>
                    <div id="dashboardLiveFeed" style="max-height: 400px; overflow-y: auto;">
                        <div class="empty-state-small">Waiting for events...</div>
                    </div>
                </div>
            </div>
        `;

        // Load metrics and workflows in parallel
        try {
            const [metrics, workflows] = await Promise.all([
                this.fetchApi('metrics').catch(() => null),
                this.fetchApi('workflows').catch(() => null),
            ]);

            if (this._currentView !== 'dashboard') return; // User navigated away

            // KPIs
            if (metrics) {
                this.charts.renderOverviewCards('dashboardKpis', metrics);
            } else {
                this.charts.renderOverviewCards('dashboardKpis', {
                    totalWorkflows: 0, successRate: 0, totalTokens: 0, estimatedCostUsd: 0, totalEvents: 0
                });
            }

            // Recent workflows table
            const tableContainer = document.getElementById('dashboardWorkflowsTable');
            if (workflows && Array.isArray(workflows) && workflows.length > 0) {
                const recent = workflows.slice(0, 10);
                tableContainer.innerHTML = this._renderWorkflowTable(recent);
            } else {
                tableContainer.innerHTML = `
                    <div class="empty-state">
                        <svg class="empty-state-icon" viewBox="0 0 64 64" fill="none" stroke="#6b7280" stroke-width="2">
                            <rect x="8" y="8" width="48" height="48" rx="8"/>
                            <line x1="20" y1="24" x2="44" y2="24"/>
                            <line x1="20" y1="32" x2="44" y2="32"/>
                            <line x1="20" y1="40" x2="36" y2="40"/>
                        </svg>
                        <h3>No workflows yet</h3>
                        <p>Run a swarm workflow and it will appear here for inspection.</p>
                    </div>
                `;
            }

            // Pre-populate live feed with sidebar events
            const liveFeed = document.getElementById('dashboardLiveFeed');
            if (liveFeed && this._sidebarEvents.length > 0) {
                liveFeed.innerHTML = '';
                for (const evt of this._sidebarEvents.slice(0, 20)) {
                    this._appendLiveFeedEvent(liveFeed, evt);
                }
            }

        } catch (err) {
            if (this._currentView !== 'dashboard') return;
            this._showError(main, 'Failed to load dashboard', err);
        }
    }

    _appendLiveFeedEvent(container, event) {
        const type = event.type || event.eventType || 'EVENT';
        const category = this._eventCategory(type);
        const msg = event.message || '';
        const time = this.formatTimestamp(event.eventInstant || event.timestamp);

        // Remove empty state if present
        const emptyState = container.querySelector('.empty-state-small');
        if (emptyState) emptyState.remove();

        const html = `
            <div class="sidebar-event-item event-${category}" style="animation: fade-slide-in 300ms ease;">
                <div style="display:flex; justify-content:space-between; align-items:center;">
                    <span class="event-type">${this._escape(type.replace(/_/g, ' '))}</span>
                    <span style="font-size:0.65rem; color:var(--text-muted);">${time}</span>
                </div>
                <div class="event-msg">${this._escape(this._truncate(msg, 80))}</div>
            </div>
        `;

        container.insertAdjacentHTML('afterbegin', html);

        // Trim
        while (container.children.length > 50) {
            container.removeChild(container.lastChild);
        }
    }

    /* ============================================================
       VIEW: Workflow List
       ============================================================ */

    async renderWorkflowList() {
        const main = document.getElementById('mainContent');
        main.innerHTML = `
            <div class="view-header">
                <h1>Workflows</h1>
                <p>All recorded workflow executions</p>
            </div>
            <div id="workflowListContent">
                <div class="view-loading"><div class="spinner"></div><p>Loading workflows...</p></div>
            </div>
        `;

        try {
            const workflows = await this.fetchApi('workflows');
            if (this._currentView !== 'workflows') return;

            const content = document.getElementById('workflowListContent');

            if (!workflows || !Array.isArray(workflows) || workflows.length === 0) {
                content.innerHTML = `
                    <div class="empty-state">
                        <svg class="empty-state-icon" viewBox="0 0 64 64" fill="none" stroke="#6b7280" stroke-width="2">
                            <rect x="8" y="8" width="48" height="48" rx="8"/>
                            <line x1="20" y1="24" x2="44" y2="24"/>
                            <line x1="20" y1="32" x2="44" y2="32"/>
                            <line x1="20" y1="40" x2="36" y2="40"/>
                        </svg>
                        <h3>No workflows recorded</h3>
                        <p>Workflows will appear here once you run a swarm. The observability system captures all events automatically.</p>
                    </div>
                `;
                return;
            }

            content.innerHTML = `
                <div class="table-container">
                    ${this._renderWorkflowTable(workflows)}
                </div>
            `;
        } catch (err) {
            if (this._currentView !== 'workflows') return;
            this._showError(document.getElementById('workflowListContent'), 'Failed to load workflows', err);
        }
    }

    _renderWorkflowTable(workflows) {
        const rows = workflows.map(w => {
            const summary = w.summary || {};
            const id = w.correlationId || w.swarmId || 'unknown';
            const displayId = id.length > 20 ? id.substring(0, 8) + '...' + id.substring(id.length - 4) : id;

            return `
                <tr>
                    <td>
                        <a href="#/workflow/${encodeURIComponent(id)}" class="table-link mono">${this._escape(displayId)}</a>
                    </td>
                    <td>${this._escape(w.swarmId || '-')}</td>
                    <td>${this.statusBadge(w.status)}</td>
                    <td class="mono">${this.formatDuration(w.durationMs)}</td>
                    <td>${summary.uniqueAgents != null ? summary.uniqueAgents : '-'}</td>
                    <td>${summary.uniqueTasks != null ? summary.uniqueTasks : '-'}</td>
                    <td class="mono">${this.formatTokens(summary.totalTokens)}</td>
                    <td>${summary.errorCount > 0 ? '<span class="text-error">' + summary.errorCount + '</span>' : '<span class="text-muted">0</span>'}</td>
                    <td class="text-muted">${this.formatTimestamp(w.startTime)}</td>
                </tr>
            `;
        }).join('');

        return `
            <table class="data-table">
                <thead>
                    <tr>
                        <th>Correlation ID</th>
                        <th>Swarm</th>
                        <th>Status</th>
                        <th>Duration</th>
                        <th>Agents</th>
                        <th>Tasks</th>
                        <th>Tokens</th>
                        <th>Errors</th>
                        <th>Started</th>
                    </tr>
                </thead>
                <tbody>
                    ${rows}
                </tbody>
            </table>
        `;
    }

    /* ============================================================
       VIEW: Workflow Detail
       ============================================================ */

    async renderWorkflowDetail(correlationId) {
        const main = document.getElementById('mainContent');
        main.innerHTML = `
            <a href="#/workflows" class="back-link">
                <svg width="14" height="14" viewBox="0 0 14 14" fill="currentColor"><path d="M10 2L5 7l5 5" fill="none" stroke="currentColor" stroke-width="2"/></svg>
                Back to Workflows
            </a>
            <div id="workflowDetailContent">
                <div class="view-loading"><div class="spinner"></div><p>Loading workflow detail...</p></div>
            </div>
        `;

        try {
            // Fetch workflow, graph, and events in parallel
            const [workflow, graphData, events] = await Promise.all([
                this.fetchApi('workflows/' + encodeURIComponent(correlationId)).catch(() => null),
                this.fetchApi('workflows/' + encodeURIComponent(correlationId) + '/graph').catch(() => null),
                this.fetchApi('workflows/' + encodeURIComponent(correlationId) + '/events').catch(() => null),
            ]);

            if (this._currentView !== 'workflow-detail') return;

            const content = document.getElementById('workflowDetailContent');

            if (!workflow) {
                content.innerHTML = `
                    <div class="error-state">
                        <h3>Workflow not found</h3>
                        <p>No recording found for correlation ID: ${this._escape(correlationId)}</p>
                        <button onclick="window.location.hash='#/workflows'">Back to Workflows</button>
                    </div>
                `;
                return;
            }

            const summary = workflow.summary || {};

            content.innerHTML = `
                <div class="workflow-detail-layout">
                    <div class="workflow-detail-header">
                        <div class="view-header" style="margin-bottom:12px;">
                            <h1>Workflow: ${this._escape(this._truncate(correlationId, 24))}</h1>
                        </div>
                        <div class="workflow-info-bar">
                            <div class="workflow-info-item">
                                <span class="info-label">Status</span>
                                <span class="info-value">${this.statusBadge(workflow.status)}</span>
                            </div>
                            <div class="info-divider"></div>
                            <div class="workflow-info-item">
                                <span class="info-label">Swarm ID</span>
                                <span class="info-value mono">${this._escape(workflow.swarmId || '-')}</span>
                            </div>
                            <div class="info-divider"></div>
                            <div class="workflow-info-item">
                                <span class="info-label">Duration</span>
                                <span class="info-value">${this.formatDuration(workflow.durationMs)}</span>
                            </div>
                            <div class="info-divider"></div>
                            <div class="workflow-info-item">
                                <span class="info-label">Agents</span>
                                <span class="info-value">${summary.uniqueAgents != null ? summary.uniqueAgents : '-'}</span>
                            </div>
                            <div class="info-divider"></div>
                            <div class="workflow-info-item">
                                <span class="info-label">Tasks</span>
                                <span class="info-value">${summary.uniqueTasks != null ? summary.uniqueTasks : '-'}</span>
                            </div>
                            <div class="info-divider"></div>
                            <div class="workflow-info-item">
                                <span class="info-label">Tokens</span>
                                <span class="info-value">${this.formatTokens(summary.totalTokens)}</span>
                            </div>
                            <div class="info-divider"></div>
                            <div class="workflow-info-item">
                                <span class="info-label">Errors</span>
                                <span class="info-value ${summary.errorCount > 0 ? 'text-error' : ''}">${summary.errorCount || 0}</span>
                            </div>
                        </div>
                    </div>

                    <div class="workflow-graph-container">
                        <div class="graph-toolbar">
                            <button id="graphZoomIn" title="Zoom in">+</button>
                            <button id="graphZoomOut" title="Zoom out">-</button>
                            <button id="graphFit" title="Fit to view">&#8644;</button>
                        </div>
                        <div id="workflowGraphCanvas" class="graph-canvas"></div>
                    </div>

                    <div class="workflow-timeline-container">
                        <div class="timeline-header">
                            <span>Event Timeline</span>
                            <span class="event-count" id="timelineEventCount">${(events || []).length} events</span>
                        </div>
                        <div class="timeline-content" id="workflowTimeline"></div>
                    </div>
                </div>

                <div class="decision-section mt-24" id="decisionSection" style="display:none;">
                    <div class="card">
                        <div class="card-header">
                            <span class="card-title">Decision Trace</span>
                        </div>
                        <div id="decisionContent"></div>
                    </div>
                </div>
            `;

            // Render graph
            this.graph = new WorkflowGraph('workflowGraphCanvas');
            if (graphData) {
                this.graph.render(graphData);
            }
            this.graph.onNodeClick((nodeData) => {
                this._onGraphNodeClick(correlationId, nodeData);
            });

            // Graph toolbar
            document.getElementById('graphZoomIn').addEventListener('click', () => this.graph && this.graph.zoomIn());
            document.getElementById('graphZoomOut').addEventListener('click', () => this.graph && this.graph.zoomOut());
            document.getElementById('graphFit').addEventListener('click', () => this.graph && this.graph.fitToContainer());

            // Render timeline
            this.timeline = new EventTimeline('workflowTimeline');
            this.timeline.render(events || workflow.timeline || []);

            // Load decision trace (non-blocking)
            this._loadDecisionTrace(correlationId);

        } catch (err) {
            if (this._currentView !== 'workflow-detail') return;
            this._showError(document.getElementById('workflowDetailContent'), 'Failed to load workflow', err);
        }
    }

    async _loadDecisionTrace(correlationId) {
        try {
            const [decisions, explanation] = await Promise.all([
                this.fetchApi('decisions/' + encodeURIComponent(correlationId)).catch(() => null),
                this.fetchApi('decisions/' + encodeURIComponent(correlationId) + '/explain').catch(() => null),
            ]);

            if (this._currentView !== 'workflow-detail') return;

            const section = document.getElementById('decisionSection');
            const content = document.getElementById('decisionContent');
            if (!section || !content) return;

            if (!decisions && !explanation) return;

            section.style.display = 'block';

            let html = '';
            if (explanation) {
                html += `<div class="decision-explain">${this._escape(typeof explanation === 'string' ? explanation : JSON.stringify(explanation, null, 2))}</div>`;
            }
            if (decisions && decisions.nodes) {
                html += `<p class="text-muted mt-8" style="font-size:0.8rem;">${decisions.nodes.length} decision nodes traced</p>`;
            }
            content.innerHTML = html || '<div class="empty-state-small">No decision data available</div>';
        } catch (e) {
            // Non-critical, ignore
        }
    }

    /**
     * Handles click on a graph node - opens the task detail panel.
     */
    async _onGraphNodeClick(correlationId, nodeData) {
        if (!nodeData) return;

        const taskId = nodeData.id;
        if (!taskId) return;

        // Highlight in timeline
        if (this.timeline) {
            this.timeline.highlightTask(taskId);
        }

        // Open panel with loading state
        this._openTaskPanel();
        const panelContent = document.getElementById('taskPanelContent');
        panelContent.innerHTML = '<div class="view-loading"><div class="spinner"></div><p>Loading task details...</p></div>';

        try {
            const taskDetail = await this.fetchApi('workflows/' + encodeURIComponent(correlationId) + '/tasks/' + encodeURIComponent(taskId));

            if (!taskDetail) {
                panelContent.innerHTML = this._renderBasicNodeDetail(nodeData);
                return;
            }

            panelContent.innerHTML = this._renderTaskDetail(taskDetail, nodeData);
        } catch (err) {
            // Fallback to basic info from the node
            panelContent.innerHTML = this._renderBasicNodeDetail(nodeData);
        }
    }

    _renderTaskDetail(task, nodeData) {
        const events = task.events || [];
        const eventsHtml = events.length > 0
            ? events.map(evt => {
                const type = evt.eventType || evt.type || '';
                const cat = type.toUpperCase().includes('COMPLETED') ? 'completed'
                    : type.toUpperCase().includes('FAILED') ? 'failed'
                    : 'started';
                return `
                    <div class="task-event-item ${cat}">
                        <div class="event-time">${this.formatTimestamp(evt.eventInstant || evt.timestamp)} - ${this._escape(type.replace(/_/g, ' '))}</div>
                        <div>${this._escape(evt.message || '')}</div>
                    </div>
                `;
            }).join('')
            : '<div class="empty-state-small">No events for this task</div>';

        return `
            <div class="task-detail-section">
                <h4>Task Information</h4>
                <div class="task-detail-row">
                    <span class="label">Task ID</span>
                    <span class="value mono">${this._escape(this._truncate(task.taskId || nodeData.id, 24))}</span>
                </div>
                <div class="task-detail-row">
                    <span class="label">Agent</span>
                    <span class="value">${this._escape(task.agentRole || task.agentId || '-')}</span>
                </div>
                <div class="task-detail-row">
                    <span class="label">Total Duration</span>
                    <span class="value">${this.formatDuration(task.totalDurationMs)}</span>
                </div>
            </div>

            <div class="task-detail-section">
                <h4>Token Usage</h4>
                <div class="task-detail-row">
                    <span class="label">Prompt Tokens</span>
                    <span class="value">${this.formatTokens(task.promptTokens)}</span>
                </div>
                <div class="task-detail-row">
                    <span class="label">Completion Tokens</span>
                    <span class="value">${this.formatTokens(task.completionTokens)}</span>
                </div>
                <div class="task-detail-row">
                    <span class="label">Total Tokens</span>
                    <span class="value" style="font-weight:700;">${this.formatTokens((task.promptTokens || 0) + (task.completionTokens || 0))}</span>
                </div>
            </div>

            <div class="task-detail-section">
                <h4>Events (${events.length})</h4>
                <div class="task-event-list">
                    ${eventsHtml}
                </div>
            </div>
        `;
    }

    _renderBasicNodeDetail(nodeData) {
        const meta = nodeData.metadata || {};
        return `
            <div class="task-detail-section">
                <h4>Node Information</h4>
                <div class="task-detail-row">
                    <span class="label">ID</span>
                    <span class="value mono">${this._escape(this._truncate(nodeData.id, 24))}</span>
                </div>
                <div class="task-detail-row">
                    <span class="label">Label</span>
                    <span class="value">${this._escape(nodeData.label || '-')}</span>
                </div>
                <div class="task-detail-row">
                    <span class="label">Type</span>
                    <span class="value">${this._escape(nodeData.type || '-')}</span>
                </div>
                <div class="task-detail-row">
                    <span class="label">Status</span>
                    <span class="value">${this.statusBadge(nodeData.status || 'unknown')}</span>
                </div>
                ${nodeData.group ? `
                <div class="task-detail-row">
                    <span class="label">Group</span>
                    <span class="value">${this._escape(nodeData.group)}</span>
                </div>` : ''}
                ${meta.agentRole ? `
                <div class="task-detail-row">
                    <span class="label">Agent Role</span>
                    <span class="value">${this._escape(meta.agentRole)}</span>
                </div>` : ''}
                ${meta.durationMs != null ? `
                <div class="task-detail-row">
                    <span class="label">Duration</span>
                    <span class="value">${this.formatDuration(meta.durationMs)}</span>
                </div>` : ''}
            </div>
            <div class="empty-state-small mt-16">
                Detailed task data not available from the API for this node. This may be an agent or swarm-level node.
            </div>
        `;
    }

    _openTaskPanel() {
        document.getElementById('taskPanel').classList.add('open');
        document.getElementById('taskPanelOverlay').classList.add('visible');
    }

    _closeTaskPanel() {
        document.getElementById('taskPanel').classList.remove('open');
        document.getElementById('taskPanelOverlay').classList.remove('visible');
        if (this.timeline) {
            this.timeline.highlightTask(null);
        }
    }

    /* ============================================================
       VIEW: Token Dashboard
       ============================================================ */

    async renderTokenDashboard() {
        const main = document.getElementById('mainContent');
        main.innerHTML = `
            <div class="view-header">
                <h1>Token Usage</h1>
                <p>Detailed token consumption and cost analysis</p>
            </div>
            <div class="token-summary-grid" id="tokenSummary">
                <div class="token-stat-card"><div class="token-stat-value">--</div><div class="token-stat-label">Loading...</div></div>
                <div class="token-stat-card"><div class="token-stat-value">--</div><div class="token-stat-label">Loading...</div></div>
                <div class="token-stat-card"><div class="token-stat-value">--</div><div class="token-stat-label">Loading...</div></div>
                <div class="token-stat-card"><div class="token-stat-value">--</div><div class="token-stat-label">Loading...</div></div>
            </div>
            <div class="grid-2 mb-24">
                <div class="chart-card">
                    <div class="chart-title">Tokens per Workflow</div>
                    <div class="chart-wrapper" id="tokenChartWrapper"></div>
                </div>
                <div class="chart-card">
                    <div class="chart-title">Prompt vs Completion</div>
                    <div class="chart-wrapper" id="tokenBreakdownWrapper"></div>
                </div>
            </div>
            <div class="grid-2">
                <div class="chart-card">
                    <div class="chart-title">Cumulative Cost</div>
                    <div class="chart-wrapper" id="costChartWrapper"></div>
                </div>
                <div class="chart-card">
                    <div class="chart-title">Execution Latency</div>
                    <div class="chart-wrapper" id="latencyChartWrapper"></div>
                </div>
            </div>
        `;

        try {
            const [metrics, workflows] = await Promise.all([
                this.fetchApi('metrics').catch(() => null),
                this.fetchApi('workflows').catch(() => null),
            ]);

            if (this._currentView !== 'tokens') return;

            // Token summary cards
            const summaryEl = document.getElementById('tokenSummary');
            if (metrics) {
                const totalPrompt = workflows ? workflows.reduce((acc, w) => acc + ((w.summary || {}).totalPromptTokens || 0), 0) : 0;
                const totalCompletion = workflows ? workflows.reduce((acc, w) => acc + ((w.summary || {}).totalCompletionTokens || 0), 0) : 0;

                summaryEl.innerHTML = `
                    <div class="token-stat-card">
                        <div class="token-stat-value">${this.formatTokens(metrics.totalTokens || 0)}</div>
                        <div class="token-stat-label">Total Tokens</div>
                    </div>
                    <div class="token-stat-card">
                        <div class="token-stat-value">${this.formatTokens(totalPrompt)}</div>
                        <div class="token-stat-label">Prompt Tokens</div>
                    </div>
                    <div class="token-stat-card">
                        <div class="token-stat-value">${this.formatTokens(totalCompletion)}</div>
                        <div class="token-stat-label">Completion Tokens</div>
                    </div>
                    <div class="token-stat-card">
                        <div class="token-stat-value">$${(metrics.estimatedCostUsd || 0).toFixed(4)}</div>
                        <div class="token-stat-label">Estimated Cost</div>
                    </div>
                `;

                // Token breakdown pie
                this.charts.renderTokenBreakdownChart('tokenBreakdownWrapper', totalPrompt, totalCompletion);
            } else {
                summaryEl.innerHTML = '<div class="empty-state-small">No metrics data available</div>';
            }

            if (workflows && Array.isArray(workflows) && workflows.length > 0) {
                // Token bar chart
                this.charts.renderTokenChart('tokenChartWrapper', workflows);

                // Cost chart
                this.charts.renderCostChart('costChartWrapper', workflows);

                // Latency chart
                const latencyData = workflows.map(w => ({
                    label: this._truncate(w.correlationId || w.swarmId || '?', 16),
                    durationMs: w.durationMs || 0,
                }));
                this.charts.renderLatencyChart('latencyChartWrapper', latencyData);
            } else {
                document.getElementById('tokenChartWrapper').innerHTML = '<div class="empty-state-small">No workflow data for charts</div>';
                document.getElementById('costChartWrapper').innerHTML = '<div class="empty-state-small">No cost data</div>';
                document.getElementById('latencyChartWrapper').innerHTML = '<div class="empty-state-small">No latency data</div>';
            }

        } catch (err) {
            if (this._currentView !== 'tokens') return;
            this._showError(main, 'Failed to load token data', err);
        }
    }

    /* ============================================================
       Formatting Helpers
       ============================================================ */

    /**
     * Formats milliseconds into a human-readable duration.
     * @param {number|null} ms
     * @returns {string} e.g. "2m 35s"
     */
    formatDuration(ms) {
        if (ms == null || ms < 0) return '-';
        if (ms < 1000) return ms + 'ms';
        const totalSeconds = Math.floor(ms / 1000);
        const minutes = Math.floor(totalSeconds / 60);
        const seconds = totalSeconds % 60;
        if (minutes === 0) return seconds + 's';
        const hours = Math.floor(minutes / 60);
        if (hours === 0) return minutes + 'm ' + seconds + 's';
        return hours + 'h ' + (minutes % 60) + 'm';
    }

    /**
     * Formats an ISO timestamp to relative time.
     * @param {string|null} iso
     * @returns {string} e.g. "3m ago"
     */
    formatTimestamp(iso) {
        if (!iso) return '-';
        try {
            const date = new Date(iso);
            if (isNaN(date.getTime())) return iso;

            const now = new Date();
            const diffMs = now - date;

            if (diffMs < 0) return 'just now';
            if (diffMs < 5000) return 'just now';
            if (diffMs < 60000) return Math.floor(diffMs / 1000) + 's ago';
            if (diffMs < 3600000) return Math.floor(diffMs / 60000) + 'm ago';
            if (diffMs < 86400000) return Math.floor(diffMs / 3600000) + 'h ago';
            if (diffMs < 604800000) return Math.floor(diffMs / 86400000) + 'd ago';

            // Beyond a week, show date
            return date.toLocaleDateString() + ' ' + date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        } catch (e) {
            return iso;
        }
    }

    /**
     * Formats a token count with locale separators.
     * @param {number|null} n
     * @returns {string} e.g. "12,345"
     */
    formatTokens(n) {
        if (n == null) return '-';
        return Number(n).toLocaleString();
    }

    /**
     * Returns HTML for a colored status badge pill.
     * @param {string} status
     * @returns {string} HTML
     */
    statusBadge(status) {
        if (!status) return '<span class="badge badge-unknown">unknown</span>';
        const normalized = status.toLowerCase().replace(/\s+/g, '_');
        const label = status.replace(/_/g, ' ');

        // Dot color
        let dotColor = '#6b7280';
        if (normalized === 'completed' || normalized === 'success') dotColor = '#66bb6a';
        else if (normalized === 'running' || normalized === 'started' || normalized === 'in_progress') dotColor = '#ffa726';
        else if (normalized === 'failed' || normalized === 'error') dotColor = '#ef5350';
        else if (normalized === 'pending') dotColor = '#78909c';

        return `<span class="badge badge-${normalized}"><span style="display:inline-block;width:6px;height:6px;border-radius:50%;background:${dotColor};"></span> ${this._escape(label)}</span>`;
    }

    /* ============================================================
       Internal Helpers
       ============================================================ */

    _eventCategory(type) {
        const t = (type || '').toUpperCase();
        if (t.includes('COMPLETED') || t.includes('PASSED')) return 'completed';
        if (t.includes('STARTED')) return 'started';
        if (t.includes('FAILED') || t.includes('ERROR')) return 'failed';
        if (t.includes('ITERATION')) return 'iteration';
        return 'started';
    }

    _showError(container, title, err) {
        const message = err && err.message ? err.message : 'An unexpected error occurred';
        container.innerHTML = `
            <div class="error-state">
                <h3>${this._escape(title)}</h3>
                <p>${this._escape(message)}</p>
                <button onclick="window.location.reload()">Reload</button>
            </div>
        `;
    }

    _escape(text) {
        if (!text) return '';
        const div = document.createElement('div');
        div.textContent = String(text);
        return div.innerHTML;
    }

    _truncate(text, maxLen) {
        if (!text) return '';
        if (text.length <= maxLen) return text;
        return text.substring(0, maxLen - 3) + '...';
    }
}

/* ============================================================
   Bootstrap
   ============================================================ */
document.addEventListener('DOMContentLoaded', () => {
    try {
        window.studioApp = new StudioApp();
    } catch (err) {
        var el = document.getElementById('mainContent');
        if (el) el.innerHTML = '<pre style="color:#ef5350;padding:24px;font-size:14px;white-space:pre-wrap;">INIT ERROR:\n' + err.message + '\n\n' + err.stack + '</pre>';
        console.error('StudioApp init failed:', err);
    }
});
