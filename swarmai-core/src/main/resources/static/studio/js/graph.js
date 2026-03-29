/**
 * SwarmAI Studio - Workflow Graph Visualization
 * vis-network based interactive DAG renderer for agent workflows.
 */
class WorkflowGraph {

    /**
     * @param {string} containerId - DOM element ID for the graph canvas
     */
    constructor(containerId) {
        this._containerId = containerId;
        /** @type {vis.Network|null} */
        this._network = null;
        /** @type {vis.DataSet|null} */
        this._nodes = null;
        /** @type {vis.DataSet|null} */
        this._edges = null;
        /** @type {function|null} */
        this._onNodeClickCallback = null;
    }

    /* ============================================================
       Node Style Configuration
       ============================================================ */

    static get NODE_STYLES() {
        return {
            /* Agent types */
            agent: {
                shape: 'circle',
                color: {
                    background: '#1565c0',
                    border: '#42a5f5',
                    highlight: { background: '#1976d2', border: '#64b5f6' },
                    hover: { background: '#1976d2', border: '#64b5f6' },
                },
                font: { color: '#e0e0e0', size: 12, face: '-apple-system, sans-serif' },
                size: 28,
                borderWidth: 2,
                shadow: { enabled: true, color: 'rgba(66,165,245,0.3)', size: 8 },
            },

            /* Task states */
            task_completed: {
                shape: 'box',
                color: {
                    background: '#1b5e20',
                    border: '#66bb6a',
                    highlight: { background: '#2e7d32', border: '#81c784' },
                    hover: { background: '#2e7d32', border: '#81c784' },
                },
                font: { color: '#e0e0e0', size: 11, face: '-apple-system, sans-serif' },
                borderWidth: 2,
                borderRadius: 6,
                shadow: { enabled: true, color: 'rgba(102,187,106,0.2)', size: 6 },
            },
            task_running: {
                shape: 'box',
                color: {
                    background: '#e65100',
                    border: '#ffa726',
                    highlight: { background: '#ef6c00', border: '#ffb74d' },
                    hover: { background: '#ef6c00', border: '#ffb74d' },
                },
                font: { color: '#e0e0e0', size: 11, face: '-apple-system, sans-serif' },
                borderWidth: 3,
                borderRadius: 6,
                shadow: { enabled: true, color: 'rgba(255,167,38,0.4)', size: 10 },
            },
            task_failed: {
                shape: 'box',
                color: {
                    background: '#b71c1c',
                    border: '#ef5350',
                    highlight: { background: '#c62828', border: '#e57373' },
                    hover: { background: '#c62828', border: '#e57373' },
                },
                font: { color: '#e0e0e0', size: 11, face: '-apple-system, sans-serif' },
                borderWidth: 2,
                borderRadius: 6,
                shadow: { enabled: true, color: 'rgba(239,83,80,0.3)', size: 8 },
            },
            task_pending: {
                shape: 'box',
                color: {
                    background: '#37474f',
                    border: '#78909c',
                    highlight: { background: '#455a64', border: '#90a4ae' },
                    hover: { background: '#455a64', border: '#90a4ae' },
                },
                font: { color: '#9ca3af', size: 11, face: '-apple-system, sans-serif' },
                borderWidth: 1,
                borderRadius: 6,
                shadow: { enabled: false },
            },

            /* Reviewer (diamond) */
            reviewer: {
                shape: 'diamond',
                color: {
                    background: '#6a1b9a',
                    border: '#ab47bc',
                    highlight: { background: '#7b1fa2', border: '#ba68c8' },
                    hover: { background: '#7b1fa2', border: '#ba68c8' },
                },
                font: { color: '#e0e0e0', size: 11, face: '-apple-system, sans-serif' },
                size: 22,
                borderWidth: 2,
                shadow: { enabled: true, color: 'rgba(171,71,188,0.3)', size: 8 },
            },

            /* Swarm node */
            swarm: {
                shape: 'circle',
                color: {
                    background: '#0f3460',
                    border: '#4fc3f7',
                    highlight: { background: '#16213e', border: '#81d4fa' },
                    hover: { background: '#16213e', border: '#81d4fa' },
                },
                font: { color: '#4fc3f7', size: 13, bold: true, face: '-apple-system, sans-serif' },
                size: 34,
                borderWidth: 3,
                shadow: { enabled: true, color: 'rgba(79,195,247,0.3)', size: 10 },
            },

            /* Tool node */
            tool: {
                shape: 'hexagon',
                color: {
                    background: '#004d40',
                    border: '#26a69a',
                    highlight: { background: '#00695c', border: '#4db6ac' },
                    hover: { background: '#00695c', border: '#4db6ac' },
                },
                font: { color: '#e0e0e0', size: 10, face: '-apple-system, sans-serif' },
                size: 18,
                borderWidth: 2,
                shadow: { enabled: true, color: 'rgba(38,166,154,0.2)', size: 6 },
            },

            /* Default fallback */
            default: {
                shape: 'dot',
                color: {
                    background: '#37474f',
                    border: '#78909c',
                    highlight: { background: '#455a64', border: '#90a4ae' },
                    hover: { background: '#455a64', border: '#90a4ae' },
                },
                font: { color: '#9ca3af', size: 11, face: '-apple-system, sans-serif' },
                size: 16,
                borderWidth: 1,
                shadow: { enabled: false },
            },
        };
    }

    /* ============================================================
       Render
       ============================================================ */

    /**
     * Renders the workflow graph from backend graph data.
     * @param {object} graphData - {nodes: [{id, label, type, status, group, metadata}], edges: [{from, to, label, dashes}]}
     */
    render(graphData) {
        const container = document.getElementById(this._containerId);
        if (!container) {
            console.error('[WorkflowGraph] Container not found:', this._containerId);
            return;
        }

        this.destroy();

        if (!graphData || !graphData.nodes || graphData.nodes.length === 0) {
            container.innerHTML = `
                <div class="empty-state" style="height:100%">
                    <svg class="empty-state-icon" viewBox="0 0 64 64" fill="none" stroke="#6b7280" stroke-width="2">
                        <circle cx="32" cy="16" r="8"/><circle cx="16" cy="48" r="8"/><circle cx="48" cy="48" r="8"/>
                        <line x1="28" y1="24" x2="20" y2="40"/><line x1="36" y1="24" x2="44" y2="40"/>
                    </svg>
                    <h3>No graph data</h3>
                    <p>This workflow has no graph structure available yet.</p>
                </div>
            `;
            return;
        }

        // Detect layout style
        const hasIterativeEdge = graphData.edges.some(e => e.dashes === true);
        const hasParallelGroups = this._detectParallelGroups(graphData);

        // Build vis nodes
        const visNodes = graphData.nodes.map(node => {
            const nodeType = this._resolveNodeType(node);
            const style = WorkflowGraph.NODE_STYLES[nodeType] || WorkflowGraph.NODE_STYLES.default;

            return {
                id: node.id,
                label: this._formatLabel(node.label || node.id, 22),
                title: this._buildTooltip(node),
                ...style,
                // Store original data for click handlers
                originalData: node,
            };
        });

        // Build vis edges
        const visEdges = graphData.edges.map(edge => {
            const edgeConfig = {
                from: edge.from,
                to: edge.to,
                arrows: { to: { enabled: true, scaleFactor: 0.7 } },
                color: {
                    color: '#4a5568',
                    highlight: '#4fc3f7',
                    hover: '#81d4fa',
                    opacity: 0.7,
                },
                width: 1.5,
                smooth: {
                    enabled: true,
                    type: edge.dashes ? 'curvedCCW' : 'cubicBezier',
                    roundness: edge.dashes ? 0.3 : 0.15,
                },
            };

            if (edge.label) {
                edgeConfig.label = edge.label;
                edgeConfig.font = {
                    color: '#6b7280',
                    size: 9,
                    strokeWidth: 3,
                    strokeColor: '#1a1a2e',
                    face: '-apple-system, sans-serif',
                };
            }

            if (edge.dashes) {
                edgeConfig.dashes = [8, 6];
                edgeConfig.color.color = '#ab47bc';
                edgeConfig.color.opacity = 0.6;
                edgeConfig.width = 1.5;
            }

            return edgeConfig;
        });

        this._nodes = new vis.DataSet(visNodes);
        this._edges = new vis.DataSet(visEdges);

        // Layout options
        const layoutOptions = this._buildLayoutOptions(hasParallelGroups, hasIterativeEdge);

        const options = {
            ...layoutOptions,
            physics: {
                enabled: true,
                stabilization: {
                    enabled: true,
                    iterations: 200,
                    fit: true,
                },
                hierarchicalRepulsion: {
                    centralGravity: 0.0,
                    springLength: 120,
                    springConstant: 0.01,
                    nodeDistance: 150,
                    damping: 0.09,
                },
            },
            interaction: {
                hover: true,
                tooltipDelay: 200,
                zoomView: true,
                dragView: true,
                navigationButtons: false,
                keyboard: { enabled: true },
            },
            edges: {
                selectionWidth: 2,
            },
        };

        this._network = new vis.Network(container, {
            nodes: this._nodes,
            edges: this._edges,
        }, options);

        // Click handler
        this._network.on('click', (params) => {
            if (params.nodes.length > 0) {
                const nodeId = params.nodes[0];
                const node = this._nodes.get(nodeId);
                if (node && node.originalData && this._onNodeClickCallback) {
                    this._onNodeClickCallback(node.originalData);
                }
            }
        });

        // Stabilization complete -> disable physics for performance
        this._network.once('stabilizationIterationsDone', () => {
            this._network.setOptions({ physics: { enabled: false } });
        });
    }

    /* ============================================================
       Layout helpers
       ============================================================ */

    _buildLayoutOptions(hasParallelGroups, hasIterativeEdge) {
        return {
            layout: {
                hierarchical: {
                    enabled: true,
                    direction: 'UD',
                    sortMethod: 'directed',
                    levelSeparation: 120,
                    nodeSpacing: 160,
                    treeSpacing: 200,
                    blockShifting: true,
                    edgeMinimization: true,
                    parentCentralization: true,
                },
            },
        };
    }

    _detectParallelGroups(graphData) {
        // Check if multiple nodes share the same set of parents (parallel tasks)
        const parentMap = {};
        for (const edge of graphData.edges) {
            if (!parentMap[edge.to]) parentMap[edge.to] = [];
            parentMap[edge.to].push(edge.from);
        }
        const parentSets = Object.values(parentMap).map(p => p.sort().join(','));
        const uniqueSets = new Set(parentSets);
        return parentSets.length > uniqueSets.size;
    }

    /* ============================================================
       Node type resolution
       ============================================================ */

    _resolveNodeType(node) {
        const type = (node.type || '').toLowerCase();
        const status = (node.status || '').toLowerCase();

        // Direct type match
        if (type === 'reviewer' || type === 'review') return 'reviewer';
        if (type === 'swarm') return 'swarm';
        if (type === 'tool') return 'tool';
        if (type === 'agent') return 'agent';

        // Task with status
        if (type.startsWith('task') || type === '') {
            if (status === 'completed' || status === 'success') return 'task_completed';
            if (status === 'running' || status === 'in_progress' || status === 'started') return 'task_running';
            if (status === 'failed' || status === 'error') return 'task_failed';
            if (status === 'pending' || status === 'queued') return 'task_pending';
        }

        // Status fallback
        if (status === 'completed') return 'task_completed';
        if (status === 'running') return 'task_running';
        if (status === 'failed') return 'task_failed';

        return 'default';
    }

    /* ============================================================
       Label & tooltip formatting
       ============================================================ */

    _formatLabel(text, maxLen) {
        if (!text) return '';
        if (text.length <= maxLen) return text;
        // Word-wrap into multiple lines
        const words = text.split(/[\s_-]+/);
        const lines = [];
        let currentLine = '';
        for (const word of words) {
            if (currentLine.length + word.length + 1 > maxLen) {
                if (currentLine) lines.push(currentLine);
                currentLine = word;
            } else {
                currentLine = currentLine ? currentLine + ' ' + word : word;
            }
        }
        if (currentLine) lines.push(currentLine);
        return lines.slice(0, 3).join('\n');
    }

    _buildTooltip(node) {
        const parts = [];
        parts.push('<div style="font-family: -apple-system, sans-serif; font-size: 12px; max-width: 250px; padding: 4px;">');
        parts.push('<strong>' + this._escapeHtml(node.label || node.id) + '</strong>');
        if (node.type) parts.push('<br>Type: ' + this._escapeHtml(node.type));
        if (node.status) parts.push('<br>Status: ' + this._escapeHtml(node.status));
        if (node.group) parts.push('<br>Group: ' + this._escapeHtml(node.group));
        if (node.metadata) {
            const meta = node.metadata;
            if (meta.agentRole) parts.push('<br>Agent: ' + this._escapeHtml(meta.agentRole));
            if (meta.durationMs != null) parts.push('<br>Duration: ' + this._formatDuration(meta.durationMs));
            if (meta.tokens != null) parts.push('<br>Tokens: ' + Number(meta.tokens).toLocaleString());
            if (meta.iterationNumber != null) parts.push('<br>Iteration: #' + meta.iterationNumber);
        }
        parts.push('<br><em style="color: #9ca3af;">Click for details</em>');
        parts.push('</div>');
        return parts.join('');
    }

    _escapeHtml(text) {
        if (!text) return '';
        const div = document.createElement('div');
        div.textContent = String(text);
        return div.innerHTML;
    }

    _formatDuration(ms) {
        if (ms == null) return '-';
        if (ms < 1000) return ms + 'ms';
        const s = Math.floor(ms / 1000);
        const m = Math.floor(s / 60);
        if (m === 0) return s + 's';
        return m + 'm ' + (s % 60) + 's';
    }

    /* ============================================================
       Live updates
       ============================================================ */

    /**
     * Updates a node's visual status (color/shape) on the live graph.
     * @param {string} nodeId
     * @param {string} status - 'completed', 'running', 'failed', 'pending'
     */
    updateNodeStatus(nodeId, status) {
        if (!this._nodes) return;

        const node = this._nodes.get(nodeId);
        if (!node) return;

        const nodeType = 'task_' + status.toLowerCase();
        const style = WorkflowGraph.NODE_STYLES[nodeType] || WorkflowGraph.NODE_STYLES.default;

        this._nodes.update({
            id: nodeId,
            color: style.color,
            borderWidth: style.borderWidth,
            shadow: style.shadow,
        });

        // Update the stored original data too
        if (node.originalData) {
            node.originalData.status = status;
        }
    }

    /* ============================================================
       Event handlers
       ============================================================ */

    /**
     * Registers a callback for node click events.
     * @param {function} callback - receives the original node data object
     */
    onNodeClick(callback) {
        this._onNodeClickCallback = callback;
    }

    /* ============================================================
       Navigation & sizing
       ============================================================ */

    /**
     * Fits the graph to the container bounds with animation.
     */
    fitToContainer() {
        if (this._network) {
            this._network.fit({
                animation: {
                    duration: 500,
                    easingFunction: 'easeInOutQuad',
                },
            });
        }
    }

    /**
     * Focuses on a specific node.
     * @param {string} nodeId
     */
    focusNode(nodeId) {
        if (this._network) {
            this._network.focus(nodeId, {
                scale: 1.2,
                animation: {
                    duration: 500,
                    easingFunction: 'easeInOutQuad',
                },
            });
            this._network.selectNodes([nodeId]);
        }
    }

    /**
     * Zooms in by a factor.
     */
    zoomIn() {
        if (this._network) {
            const scale = this._network.getScale();
            this._network.moveTo({ scale: scale * 1.3, animation: { duration: 300 } });
        }
    }

    /**
     * Zooms out by a factor.
     */
    zoomOut() {
        if (this._network) {
            const scale = this._network.getScale();
            this._network.moveTo({ scale: scale / 1.3, animation: { duration: 300 } });
        }
    }

    /* ============================================================
       Cleanup
       ============================================================ */

    /**
     * Destroys the network instance and cleans up.
     */
    destroy() {
        if (this._network) {
            this._network.destroy();
            this._network = null;
        }
        this._nodes = null;
        this._edges = null;
    }
}
