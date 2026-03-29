/**
 * SwarmAI Studio - Event Timeline Component
 * Vertical scrollable timeline with event cards, live append, and task filtering.
 */
class EventTimeline {

    /**
     * @param {string} containerId - DOM element ID for the timeline container
     */
    constructor(containerId) {
        this._containerId = containerId;
        /** @type {Array<object>} */
        this._events = [];
        /** @type {string|null} */
        this._highlightedTaskId = null;
        /** @type {number} Maximum events to render (for performance) */
        this._maxRendered = 500;
    }

    /* ============================================================
       Event Type Classification
       ============================================================ */

    static get EVENT_CATEGORIES() {
        return {
            // Completed events
            SWARM_COMPLETED: 'completed',
            PROCESS_COMPLETED: 'completed',
            TASK_COMPLETED: 'completed',
            AGENT_COMPLETED: 'completed',
            TOOL_COMPLETED: 'completed',
            ITERATION_COMPLETED: 'completed',
            ITERATION_REVIEW_PASSED: 'completed',

            // Started events
            SWARM_STARTED: 'started',
            PROCESS_STARTED: 'started',
            TASK_STARTED: 'started',
            AGENT_STARTED: 'started',
            TOOL_STARTED: 'started',
            ITERATION_STARTED: 'started',

            // Failed events
            SWARM_FAILED: 'failed',
            PROCESS_FAILED: 'failed',
            TASK_FAILED: 'failed',
            AGENT_FAILED: 'failed',
            TOOL_FAILED: 'failed',
            ITERATION_REVIEW_FAILED: 'failed',

            // Iteration / review
            ITERATION_STARTED: 'iteration',

            // Memory & Knowledge
            MEMORY_SAVED: 'memory',
            MEMORY_SEARCHED: 'memory',
            KNOWLEDGE_QUERIED: 'memory',
            KNOWLEDGE_SOURCE_ADDED: 'memory',
            MEMORY_RESET: 'memory',

            // Task skipped
            TASK_SKIPPED: 'default',
        };
    }

    /* ============================================================
       Render full timeline
       ============================================================ */

    /**
     * Renders the complete event list.
     * @param {Array<object>} events - Array of event objects
     */
    render(events) {
        this._events = events || [];
        const container = document.getElementById(this._containerId);
        if (!container) return;

        if (this._events.length === 0) {
            container.innerHTML = `
                <div class="empty-state-small">
                    No events recorded for this workflow
                </div>
            `;
            return;
        }

        // Sort newest first for display
        const sorted = [...this._events].sort((a, b) => {
            const tA = a.eventInstant || a.timestamp || '';
            const tB = b.eventInstant || b.timestamp || '';
            return tB.localeCompare(tA);
        });

        const displayEvents = sorted.slice(0, this._maxRendered);

        container.innerHTML = `
            <div class="timeline">
                ${displayEvents.map(evt => this._renderEventCard(evt, false)).join('')}
            </div>
        `;

        if (sorted.length > this._maxRendered) {
            container.insertAdjacentHTML('beforeend',
                `<div class="empty-state-small">Showing first ${this._maxRendered} of ${sorted.length} events</div>`
            );
        }
    }

    /* ============================================================
       Append single event (SSE live)
       ============================================================ */

    /**
     * Appends a new event at the top with a fade-in animation.
     * @param {object} event - Single event object
     */
    appendEvent(event) {
        if (!event) return;
        this._events.push(event);

        const container = document.getElementById(this._containerId);
        if (!container) return;

        let timeline = container.querySelector('.timeline');

        // If timeline does not exist yet (was showing empty state), create it
        if (!timeline) {
            container.innerHTML = '<div class="timeline"></div>';
            timeline = container.querySelector('.timeline');
        }

        const html = this._renderEventCard(event, true);
        timeline.insertAdjacentHTML('afterbegin', html);

        // Trim excess events from DOM
        const items = timeline.querySelectorAll('.timeline-event');
        if (items.length > this._maxRendered) {
            for (let i = this._maxRendered; i < items.length; i++) {
                items[i].remove();
            }
        }
    }

    /* ============================================================
       Event card renderer
       ============================================================ */

    /**
     * @param {object} evt
     * @param {boolean} isNew - if true, adds the animation class
     * @returns {string} HTML string
     */
    _renderEventCard(evt, isNew) {
        const type = evt.eventType || evt.type || 'UNKNOWN';
        const category = this._getCategory(type);
        const dotClass = 'dot-' + category;
        const newClass = isNew ? ' new-event' : '';
        const highlightClass = (this._highlightedTaskId && evt.taskId === this._highlightedTaskId) ? ' highlighted' : '';
        const taskIdAttr = evt.taskId ? ` data-task-id="${this._escapeAttr(evt.taskId)}"` : '';

        const timestamp = evt.eventInstant || evt.timestamp || '';
        const relTime = this._formatRelativeTime(timestamp);

        // Build meta tags
        const metaTags = [];
        if (evt.agentRole) {
            metaTags.push(`<span class="timeline-meta-tag"><span class="tag-label">Agent:</span> ${this._escape(evt.agentRole)}</span>`);
        } else if (evt.agentId) {
            metaTags.push(`<span class="timeline-meta-tag"><span class="tag-label">Agent:</span> ${this._escape(this._truncate(evt.agentId, 16))}</span>`);
        }

        if (evt.durationMs != null) {
            metaTags.push(`<span class="timeline-meta-tag"><span class="tag-label">Duration:</span> ${this._formatDuration(evt.durationMs)}</span>`);
        }

        if (evt.toolName) {
            metaTags.push(`<span class="timeline-meta-tag"><span class="tag-label">Tool:</span> ${this._escape(evt.toolName)}</span>`);
        }

        if (evt.taskId) {
            metaTags.push(`<span class="timeline-meta-tag"><span class="tag-label">Task:</span> ${this._escape(this._truncate(evt.taskId, 12))}</span>`);
        }

        // Iteration badge
        let iterationBadge = '';
        if (type.startsWith('ITERATION')) {
            const iterNum = this._extractIterationNumber(evt);
            if (iterNum) {
                iterationBadge = `<span class="iteration-badge">${iterNum}</span>`;
            }
        }

        // Error info
        let errorHtml = '';
        if (evt.errorType || evt.errorMessage) {
            const errMsg = evt.errorMessage || evt.errorType || 'Unknown error';
            errorHtml = `
                <div class="timeline-meta-tag" style="color: var(--color-error); background: var(--color-error-dim); margin-top: 4px;">
                    ${this._escape(this._truncate(errMsg, 80))}
                </div>
            `;
        }

        return `
            <div class="timeline-event${newClass}${highlightClass}"${taskIdAttr}>
                <div class="timeline-dot ${dotClass}"></div>
                <div class="timeline-event-body">
                    <div class="timeline-event-header">
                        <span class="timeline-event-type">${this._formatEventType(type)}</span>
                        ${iterationBadge}
                        <span class="timeline-event-time">${relTime}</span>
                    </div>
                    <div class="timeline-event-message">${this._escape(evt.message || '')}</div>
                    ${errorHtml}
                    ${metaTags.length > 0 ? `<div class="timeline-event-meta">${metaTags.join('')}</div>` : ''}
                </div>
            </div>
        `;
    }

    /* ============================================================
       Task highlight & scroll
       ============================================================ */

    /**
     * Highlights all events belonging to a task and scrolls to the first match.
     * @param {string} taskId
     */
    highlightTask(taskId) {
        this._highlightedTaskId = taskId;
        const container = document.getElementById(this._containerId);
        if (!container) return;

        // Remove existing highlights
        container.querySelectorAll('.timeline-event.highlighted').forEach(el => {
            el.classList.remove('highlighted');
        });

        if (!taskId) return;

        // Add highlights
        const events = container.querySelectorAll(`[data-task-id="${CSS.escape(taskId)}"]`);
        events.forEach(el => el.classList.add('highlighted'));

        // Scroll to first match
        if (events.length > 0) {
            events[0].scrollIntoView({ behavior: 'smooth', block: 'center' });
        }
    }

    /* ============================================================
       Clear
       ============================================================ */

    /**
     * Clears the timeline.
     */
    clear() {
        this._events = [];
        this._highlightedTaskId = null;
        const container = document.getElementById(this._containerId);
        if (container) {
            container.innerHTML = '<div class="empty-state-small">Timeline cleared</div>';
        }
    }

    /* ============================================================
       Helpers
       ============================================================ */

    _getCategory(type) {
        // Check explicit mapping first
        const mapped = EventTimeline.EVENT_CATEGORIES[type];
        if (mapped) return mapped;

        // Fallback pattern matching
        const t = (type || '').toUpperCase();
        if (t.includes('COMPLETED') || t.includes('PASSED')) return 'completed';
        if (t.includes('STARTED')) return 'started';
        if (t.includes('FAILED') || t.includes('ERROR')) return 'failed';
        if (t.includes('ITERATION') || t.includes('REVIEW')) return 'iteration';
        if (t.includes('TOOL')) return 'tool';
        if (t.includes('MEMORY') || t.includes('KNOWLEDGE')) return 'memory';
        return 'default';
    }

    _formatEventType(type) {
        if (!type) return 'EVENT';
        return type.replace(/_/g, ' ');
    }

    _formatRelativeTime(isoString) {
        if (!isoString) return '';

        try {
            const date = new Date(isoString);
            const now = new Date();
            const diffMs = now - date;

            if (diffMs < 0) return 'just now';
            if (diffMs < 5000) return 'just now';
            if (diffMs < 60000) return Math.floor(diffMs / 1000) + 's ago';
            if (diffMs < 3600000) return Math.floor(diffMs / 60000) + 'm ago';
            if (diffMs < 86400000) return Math.floor(diffMs / 3600000) + 'h ago';
            return Math.floor(diffMs / 86400000) + 'd ago';
        } catch (e) {
            return '';
        }
    }

    _formatDuration(ms) {
        if (ms == null) return '-';
        if (ms < 1000) return ms + 'ms';
        const s = Math.floor(ms / 1000);
        const m = Math.floor(s / 60);
        if (m === 0) return s + 's';
        return m + 'm ' + (s % 60) + 's';
    }

    _extractIterationNumber(evt) {
        // Try metadata / attributes first
        if (evt.attributes && evt.attributes.iterationNumber != null) {
            return evt.attributes.iterationNumber;
        }
        if (evt.metadata && evt.metadata.iterationNumber != null) {
            return evt.metadata.iterationNumber;
        }
        // Try to parse from message
        const match = (evt.message || '').match(/iteration\s*#?(\d+)/i);
        if (match) return parseInt(match[1], 10);
        return null;
    }

    _truncate(text, maxLen) {
        if (!text) return '';
        if (text.length <= maxLen) return text;
        return text.substring(0, maxLen - 3) + '...';
    }

    _escape(text) {
        if (!text) return '';
        const div = document.createElement('div');
        div.textContent = String(text);
        return div.innerHTML;
    }

    _escapeAttr(text) {
        if (!text) return '';
        return String(text).replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    /* ============================================================
       Accessors
       ============================================================ */

    /** Returns the current event count. */
    get eventCount() {
        return this._events.length;
    }
}
