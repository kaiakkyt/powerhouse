let consecutiveFailures = 0;
const MAX_FAILURES_BEFORE_OFFLINE = 3;
let isOfflineOverlayVisible = false;

const chartConfig = {
    responsive: true,
    maintainAspectRatio: true,
    animation: { duration: 0 },
    scales: {
        y: { beginAtZero: true }
    }
};

const tpsCtx = document.getElementById('tpsChart').getContext('2d');
const memoryCtx = document.getElementById('memoryChart').getContext('2d');
const playersCtx = document.getElementById('playersChart').getContext('2d');

const tpsChart = new Chart(tpsCtx, {
    type: 'line',
    data: {
        labels: [],
        datasets: [
            {
                label: 'TPS',
                data: [],
                borderColor: 'rgb(75, 192, 192)',
                backgroundColor: 'rgba(75, 192, 192, 0.2)',
                tension: 0.3,
                yAxisID: 'y'
            },
            {
                label: 'MSPT',
                data: [],
                borderColor: 'rgb(255, 99, 132)',
                backgroundColor: 'rgba(255, 99, 132, 0.2)',
                tension: 0.3,
                yAxisID: 'y1'
            }
        ]
    },
    options: {
        ...chartConfig,
        scales: {
            y: {
                type: 'linear',
                display: true,
                position: 'left',
                min: 0,
                max: 20,
                title: { display: true, text: 'TPS' }
            },
            y1: {
                type: 'linear',
                display: true,
                position: 'right',
                min: 0,
                max: 100,
                title: { display: true, text: 'MSPT (ms)' },
                grid: { drawOnChartArea: false }
            }
        }
    }
});

const memoryChart = new Chart(memoryCtx, {
    type: 'line',
    data: {
        labels: [],
        datasets: [
            {
                label: 'Used Memory (MB)',
                data: [],
                borderColor: 'rgb(153, 102, 255)',
                backgroundColor: 'rgba(153, 102, 255, 0.2)',
                fill: true,
                tension: 0.3
            }
        ]
    },
    options: chartConfig
});

const playersChart = new Chart(playersCtx, {
    type: 'line',
    data: {
        labels: [],
        datasets: [
            {
                label: 'Players Online',
                data: [],
                borderColor: 'rgb(54, 162, 235)',
                backgroundColor: 'rgba(54, 162, 235, 0.2)',
                fill: true,
                tension: 0.3
            }
        ]
    },
    options: chartConfig
});

const maxDataPoints = 60;

let refreshIntervalMs = 2000;
let statsIntervalId = null;
let regionsIntervalId = null;

function updateChart(chart, label, ...values) {
    chart.data.labels.push(label);
    values.forEach((value, index) => {
        chart.data.datasets[index].data.push(value);
    });
    
    if (chart.data.labels.length > maxDataPoints) {
        chart.data.labels.shift();
        chart.data.datasets.forEach(dataset => dataset.data.shift());
    }
    
    chart.update('none');
}

function getTPSColor(tps) {
    if (!isFinite(tps)) return '#888888';
    if (tps >= 19.0) return '#00ff00';
    return '#ff0000';
}

function getMSPTStatus(mspt) {
    if (mspt < 10) return { text: 'Excellent', class: 'status-excellent' };
    if (mspt < 30) return { text: 'Good', class: 'status-good' };
    if (mspt < 50) return { text: 'Warning', class: 'status-warning' };
    return { text: 'Critical', class: 'status-critical' };
}

function getBadgeForTPS(tps) {
    if (!isFinite(tps)) return { text: 'N/A', badgeClass: 'badge-neutral' };
    if (tps >= 19.0) return { text: 'Excellent', badgeClass: 'badge-excellent' };
    return { text: 'Critical', badgeClass: 'badge-critical' };
}

function getBadgeForMSPT(mspt) {
    if (mspt < 10) return { text: 'Excellent', badgeClass: 'badge-excellent' };
    if (mspt < 30) return { text: 'Good', badgeClass: 'badge-good' };
    if (mspt < 50) return { text: 'Warning', badgeClass: 'badge-warning' };
    return { text: 'Critical', badgeClass: 'badge-critical' };
}

function showOfflineOverlay() {
    if (isOfflineOverlayVisible) return;
    isOfflineOverlayVisible = true;
    
    const overlay = document.createElement('div');
    overlay.id = 'offlineOverlay';
    overlay.innerHTML = `
        <div style="background: rgba(0, 0, 0, 0.95); position: fixed; top: 0; left: 0; width: 100%; height: 100%; z-index: 9999; display: flex; align-items: center; justify-content: center; backdrop-filter: blur(10px);">
            <div style="text-align: center; color: white;">
                <div style="font-size: 72px; margin-bottom: 20px;">🚨</div>
                <h1 style="font-size: 48px; margin-bottom: 16px; color: #ef4444;">Server Currently Down!</h1>
                <p style="font-size: 20px; color: #94a3b8; margin-bottom: 8px;">Unable to connect to the server.</p>
                <p style="font-size: 16px; color: #64748b;">Waiting for a server to report back...</p>
                <div style="margin-top: 24px;">
                    <div class="spinner" style="border: 4px solid #334155; border-top: 4px solid #60a5fa; border-radius: 50%; width: 40px; height: 40px; animation: spin 1s linear infinite; margin: 0 auto;"></div>
                </div>
            </div>
        </div>
    `;
    document.body.appendChild(overlay);
    
    const style = document.createElement('style');
    style.textContent = '@keyframes spin { 0% { transform: rotate(0deg); } 100% { transform: rotate(360deg); } }';
    document.head.appendChild(style);
}

function hideOfflineOverlay() {
    if (!isOfflineOverlayVisible) return;
    isOfflineOverlayVisible = false;
    
    const overlay = document.getElementById('offlineOverlay');
    if (overlay) {
        overlay.remove();
    }
}

function showFoliaDisabledOverlay() {
    if (document.getElementById('foliaDisabledOverlay')) return;
    const overlay = document.createElement('div');
    overlay.id = 'foliaDisabledOverlay';
    overlay.innerHTML = `
        <div style="background: rgba(0,0,0,0.95); position: fixed; top:0; left:0; width:100%; height:100%; z-index:9999; display:flex; align-items:center; justify-content:center;">
            <div style="text-align:center; color:#fff; padding:24px; max-width:900px;">
                <div style="font-size:96px; margin-bottom:16px;">⚠️</div>
                <h1 style="font-size:48px; color:#ffcc00; margin-bottom:12px;">This feature is disabled on Folia servers!</h1>
                <p style="font-size:20px; color:#cbd5e1;">Powerhouse dashboard is not supported on Folia, due to multithreading problems and universal stats being impossible. Sorry :(</p>
            </div>
        </div>
    `;
    document.body.appendChild(overlay);
}

function hideFoliaDisabledOverlay() {
    const overlay = document.getElementById('foliaDisabledOverlay');
    if (overlay) overlay.remove();
}

async function updateStats() {
    try {
        const response = await fetch('/api/stats');
        if (!response.ok) throw new Error('HTTP ' + response.status);
        const data = await response.json();
        
        consecutiveFailures = 0;
        hideOfflineOverlay();
        
        const rawMspt = (typeof data.mspt === 'number') ? data.mspt : NaN;
        const effectiveTps = (Number.isFinite(rawMspt) && rawMspt > 0) ? Math.min(20.0, 1000.0 / Math.max(0.0001, rawMspt)) : (typeof data.tps === 'number' ? data.tps : NaN);
        const tpsText = Number.isFinite(effectiveTps) ? effectiveTps.toFixed(2) : 'n/a';
        const msptText = (Number.isFinite(rawMspt) && rawMspt >= 0) ? rawMspt.toFixed(2) + 'ms' : 'n/a';

        document.getElementById('tps').textContent = tpsText;
        document.getElementById('tps').style.color = getTPSColor(effectiveTps);
        document.getElementById('mspt').textContent = msptText;
        document.getElementById('players').textContent = data.players;
        try {
            const playersList = document.getElementById('players-list');
            if (playersList) {
                playersList.innerHTML = '';
                if (Array.isArray(data.playerList) && data.playerList.length > 0) {
                    data.playerList.forEach(p => {
                        const entry = document.createElement('div');
                        entry.className = 'player-entry';
                        const nameSpan = document.createElement('span');
                        nameSpan.className = 'player-name';
                        nameSpan.textContent = p.name || 'Unknown';
                        const pingSpan = document.createElement('span');
                        pingSpan.className = 'player-ping';
                        pingSpan.textContent = (typeof p.ping === 'number' ? p.ping : '-') + 'ms';
                        if (typeof p.ping === 'number') {
                            if (p.ping > 300) pingSpan.style.color = '#ff0000';
                            else if (p.ping > 150) pingSpan.style.color = '#ff9900';
                            else pingSpan.style.color = '#00cc66';
                        }
                        entry.appendChild(nameSpan);
                        entry.appendChild(document.createTextNode(' '));
                        entry.appendChild(pingSpan);
                        playersList.appendChild(entry);
                    });
                } else {
                    const none = document.createElement('div');
                    none.className = 'no-players';
                    none.textContent = 'No players online';
                    playersList.appendChild(none);
                }
            }
        } catch (e) { console.warn('Failed to render players list', e); }
        document.getElementById('entities').textContent = data.entities;
        document.getElementById('chunks').textContent = data.chunks;
        document.getElementById('memory').textContent = data.memory.used + '/' + data.memory.max + ' MB';
        
        try {
            const tpsBadge = document.getElementById('tps-badge');
            const msptBadge = document.getElementById('mspt-badge');
            const tpsStatus = getBadgeForTPS(effectiveTps);
            const msptStatus = getBadgeForMSPT(rawMspt);

            if (tpsBadge) {
                const prev = tpsBadge.textContent;
                tpsBadge.textContent = tpsStatus.text;
                tpsBadge.className = 'status-badge ' + tpsStatus.badgeClass;
                if (prev !== tpsStatus.text) {
                    tpsBadge.classList.add('pulse');
                    setTimeout(() => tpsBadge.classList.remove('pulse'), 700);
                }
            }

            if (msptBadge) {
                const prev = msptBadge.textContent;
                msptBadge.textContent = msptStatus.text;
                msptBadge.className = 'status-badge ' + msptStatus.badgeClass;
                if (prev !== msptStatus.text) {
                    msptBadge.classList.add('pulse');
                    setTimeout(() => msptBadge.classList.remove('pulse'), 700);
                }
            }
        } catch (e) {
            console.warn('Failed to update badges', e);
        }
        
        const timestamp = new Date().toLocaleTimeString();
        const chartTps = Number.isFinite(effectiveTps) ? effectiveTps : null;
        const chartMspt = (Number.isFinite(rawMspt) && rawMspt >= 0) ? rawMspt : null;
        updateChart(tpsChart, timestamp, chartTps, chartMspt);
        updateChart(memoryChart, timestamp, data.memory.used);
        try {
            updateChart(playersChart, timestamp, data.players);
        } catch (e) {
            console.warn('Failed to update players chart', e);
        }
        
    } catch (error) {
        console.error('Failed to fetch stats:', error);
        consecutiveFailures++;
        if (consecutiveFailures >= MAX_FAILURES_BEFORE_OFFLINE) {
            showOfflineOverlay();
        }
    }
}

async function updateRegions() {
    try {
        const response = await fetch('/api/regions');
        if (!response.ok) return;
        const data = await response.json();

        if (data.isFolia) {
            showFoliaDisabledOverlay();
        } else {
            hideFoliaDisabledOverlay();
        }
    } catch (error) {
        console.error('Failed to fetch regions:', error);
    }
}

async function updateLagSources() {
    const container = document.getElementById('lag-sources');
    if (!container) return;
    try {
        const res = await fetch('/api/lag-sources');
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const json = await res.json();
        const list = Array.isArray(json && json.lagSources) ? json.lagSources : [];
        const simulatedFlag = Boolean(json && json.simulated);
        const breakdown = Array.isArray(json && json.pluginBreakdown) ? json.pluginBreakdown : null;
        if (list.length === 0) throw new Error('no lag sources');
        renderLagSources(container, list, simulatedFlag, breakdown);
    } catch (e) {
        console.warn('Failed to fetch lag sources, using simulated data', e);
        const simulated = [
            { region: 'world_spawn', tps: 12.4, mspt: 83.2, cause: 'Redstone & Tickload', entities: 312, tileEntities: 54, simulated: true },
            { region: 'village_1', tps: 18.2, mspt: 22.6, cause: 'Entities (mobs)', entities: 120, tileEntities: 12, simulated: true },
            { region: 'market', tps: 9.8, mspt: 110.1, cause: 'Chunks + TileEntities', entities: 48, tileEntities: 220, simulated: false }
        ];
        renderLagSources(container, simulated, true, null);
    }
}

function renderLagSources(container, list, simulated, breakdown) {
    container.innerHTML = '';
    if (!Array.isArray(list) || list.length === 0) {
        container.innerHTML = '<div class="info">No lag sources detected.</div>';
        return;
    }
    list.forEach((item, idx) => {
        const el = document.createElement('div');
        el.className = 'region-item';
        el.innerHTML = `
            <div class="region-info">
                <div class="region-rank">#${idx + 1}</div>
                <div>
                    <div class="region-name">${escapeHtml(item.region || 'unknown')}</div>
                    <div class="region-stats">
                        <div class="mspt">${(typeof item.mspt === 'number') ? item.mspt.toFixed(1) + 'ms' : 'n/a'}</div>
                        <div class="region-tps">${(typeof item.tps === 'number') ? item.tps.toFixed(2) + ' TPS' : ''}</div>
                    </div>
                </div>
            </div>
            <div>
                <div class="region-status" title="Primary cause">${escapeHtml(item.cause || 'Unknown')}</div>
                <div style="font-size:12px;color:var(--muted);margin-top:6px;text-align:right;">${simulated ? '<em>simulated</em>' : ''}</div>
            </div>
        `;
        container.appendChild(el);
    });
    // render plugin breakdown if present
    // remove previous breakdown card if present
    const prev = document.getElementById('plugin-breakdown-card');
    if (prev) prev.remove();

    if (Array.isArray(breakdown) && breakdown.length > 0) {
        const wrap = document.createElement('div');
        wrap.id = 'plugin-breakdown-card';
        wrap.className = 'card';
        wrap.style.marginTop = '12px';
        wrap.innerHTML = '<h2>Top CPU Consumers (sampling)</h2>';
        const listEl = document.createElement('div');
        listEl.className = 'region-list';
        breakdown.forEach(d => {
            try {
                const row = document.createElement('div');
                row.className = 'region-item';
                const pct = (typeof d.percent === 'number') ? d.percent.toFixed(1) + '%' : String(d.percent);
                row.innerHTML = `<div class="region-info"><div class="region-name">${escapeHtml(d.plugin)}</div></div><div style="font-weight:700">${pct}</div>`;
                listEl.appendChild(row);
            } catch (e) {}
        });
        wrap.appendChild(listEl);
        // insert after the lag-sources card
        container.parentNode.insertBefore(wrap, container.nextSibling);
    }
}

function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":"&#39;"}[c]));
}

function startAutoRefresh() {
    updateStats();
    updateRegions();
    updateLagSources();

    if (statsIntervalId) clearInterval(statsIntervalId);
    if (regionsIntervalId) clearInterval(regionsIntervalId);

    statsIntervalId = setInterval(updateStats, refreshIntervalMs);
    regionsIntervalId = setInterval(updateRegions, 2000);
    // lag sources refresh every 2s
    setInterval(updateLagSources, 2000);
}

startAutoRefresh();

async function fetchSettings() {
    try {
        const res = await fetch('/api/settings');
        if (!res.ok) return;
        const json = await res.json();
        applySettings(json);
        const autoCheckbox = document.getElementById('setting-auto-refresh');
        const intervalInput = document.getElementById('setting-refresh-interval');
        if (autoCheckbox && typeof json.autoRefresh !== 'undefined') {
            autoCheckbox.checked = (json.autoRefresh === true || String(json.autoRefresh) === 'true');
        }
        if (intervalInput && typeof json.refreshInterval !== 'undefined') {
            intervalInput.value = Number(json.refreshInterval) || (refreshIntervalMs/1000);
        }
            const adminRow = document.getElementById('setting-admin-token-row');
            if (adminRow) {
                if (json.requiresAdminToken === true || String(json.requiresAdminToken) === 'true') adminRow.style.display = 'block';
                else adminRow.style.display = 'none';
            }
    } catch (e) {
        console.warn('Failed to fetch settings', e);
    }
}

function applySettings(settings) {
    try {
        if (!settings) return;
        if (typeof settings.refreshInterval !== 'undefined') {
            const sec = Number(settings.refreshInterval);
            if (!isNaN(sec) && sec >= 1) {
                refreshIntervalMs = sec * 1000;
                if (statsIntervalId) {
                    clearInterval(statsIntervalId);
                    statsIntervalId = setInterval(updateStats, refreshIntervalMs);
                }
            }
        }
        if (typeof settings.autoRefresh !== 'undefined') {
            const enabled = (settings.autoRefresh === true || String(settings.autoRefresh) === 'true');
            if (!enabled && statsIntervalId) {
                clearInterval(statsIntervalId);
                statsIntervalId = null;
            }
            if (enabled && !statsIntervalId) {
                statsIntervalId = setInterval(updateStats, refreshIntervalMs);
            }
        }
    } catch (e) { console.warn('applySettings failed', e); }
}

async function postSettings(data) {
    try {
        const body = new URLSearchParams();
        for (const k in data) body.append(k, data[k]);
        const res = await fetch('/api/settings', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: body.toString() });
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const json = await res.json().catch(()=>({}));
        return json;
    } catch (e) {
        console.warn('Failed to post settings', e);
        return null;
    }
}

function setupSettingsUI() {
    const btn = document.getElementById('settings-btn');
    const modal = document.getElementById('settings-modal');
    const save = document.getElementById('settings-save');
    const cancel = document.getElementById('settings-cancel');
    if (!btn || !modal) return;

    btn.addEventListener('click', () => { modal.setAttribute('aria-hidden', 'false'); });
    cancel.addEventListener('click', () => { modal.setAttribute('aria-hidden', 'true'); });

    save.addEventListener('click', async () => {
        const auto = document.getElementById('setting-auto-refresh').checked;
        const interval = Number(document.getElementById('setting-refresh-interval').value) || 2;
        const tokenInput = document.getElementById('setting-admin-token');
        const token = tokenInput ? tokenInput.value : null;
        const payload = { autoRefresh: auto, refreshInterval: interval };
        if (token) payload.adminToken = token;
        await postSettings(payload);
        applySettings({ autoRefresh: auto, refreshInterval: interval });
        modal.setAttribute('aria-hidden', 'true');
    });

    modal.addEventListener('click', (e) => { if (e.target === modal) modal.setAttribute('aria-hidden', 'true'); });

    fetchSettings();
}

setupSettingsUI();

function setupMsptHelp() {
    const btn = document.getElementById('mspt-help-btn');
    const tip = document.getElementById('mspt-help-tooltip');
    if (!btn || !tip) return;
    // associate tooltip with its button for repositioning
    try { tip.dataset.for = btn.id || 'mspt-help-btn'; } catch (e) {}

    btn.addEventListener('click', (e) => {
        e.stopPropagation();
        const isVisible = tip.classList.contains('visible');
        if (isVisible) {
            tip.classList.remove('visible');
            tip.setAttribute('aria-hidden', 'true');
            tip.style.left = '';
            tip.style.top = '';
            tip.style.display = '';
        } else {
            positionTooltipBesideBtn(tip, btn);
        }
    });


    tip.addEventListener('click', (e) => e.stopPropagation());

    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') {
            tip.classList.remove('visible');
            tip.setAttribute('aria-hidden', 'true');
        }
    });
}

setupMsptHelp();
function positionTooltipBesideBtn(tip, btn) {
    if (!btn || !tip) return;
    tip.style.display = 'block';
    tip.classList.add('visible');
    tip.setAttribute('aria-hidden', 'false');

    // ensure layout has applied (force reflow) so measurements are accurate
    // (fixes cases where tip size was 0 or stale)
    void tip.offsetWidth;

    const tipRect = tip.getBoundingClientRect();
    const btnRect = btn.getBoundingClientRect();

    let left = Math.round(btnRect.right + 8);
    let top = Math.round(btnRect.top + (btnRect.height / 2) - (tipRect.height / 2));

    if (left + tipRect.width > window.innerWidth - 8) {
        left = Math.round(btnRect.left - tipRect.width - 8);
    }

    if (left < 8) left = 8;
    if (top < 8) top = 8;
    if (top + tipRect.height > window.innerHeight - 8) top = Math.max(8, window.innerHeight - tipRect.height - 8);

    tip.style.left = left + 'px';
    tip.style.top = top + 'px';
}

function repositionVisibleTooltips() {
    document.querySelectorAll('.mspt-tooltip.visible').forEach(tip => {
        try {
            const forId = tip.dataset.for;
            if (!forId) return;
            const btn = document.getElementById(forId);
            if (!btn) return;
            // reposition
            positionTooltipBesideBtn(tip, btn);
        } catch (e) { /* ignore individual failures */ }
    });
}

// reposition tooltips on resize and on scroll (capture to handle nested scrolls)
window.addEventListener('resize', repositionVisibleTooltips);
window.addEventListener('scroll', repositionVisibleTooltips, true);

function closeAllTooltips() {
    document.querySelectorAll('.mspt-tooltip.visible').forEach(tip => {
        tip.classList.remove('visible');
        tip.setAttribute('aria-hidden', 'true');
        tip.style.left = '';
        tip.style.top = '';
        tip.style.display = '';
    });
}

function globalOutsideClickHandler(e) {
    const path = e.composedPath ? e.composedPath() : (e.path || []);
    for (const node of path) {
        if (!node || !node.classList) continue;
        if (node.classList.contains('help-icon')) return;
        if (node.classList.contains('mspt-tooltip')) return;
    }
    closeAllTooltips();
}

document.addEventListener('click', globalOutsideClickHandler, true);
document.addEventListener('touchstart', globalOutsideClickHandler, true);

function setupMemoryHelp() {
    const btn = document.getElementById('memory-help-btn');
    const tip = document.getElementById('memory-help-tooltip');
    if (!btn || !tip) return;
    // associate tooltip with its button for repositioning
    try { tip.dataset.for = btn.id || 'memory-help-btn'; } catch (e) {}
    btn.addEventListener('click', (e) => {
        e.stopPropagation();
        const isVisible = tip.classList.contains('visible');
        if (isVisible) {
            tip.classList.remove('visible');
            tip.setAttribute('aria-hidden', 'true');
            tip.style.left = '';
            tip.style.top = '';
            tip.style.display = '';
        } else {
            positionTooltipBesideBtn(tip, btn);
        }
    });


    tip.addEventListener('click', (e) => e.stopPropagation());

    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') {
            tip.classList.remove('visible');
            tip.setAttribute('aria-hidden', 'true');
        }
    });
}

setupMemoryHelp();