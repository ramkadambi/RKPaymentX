// API Base URL
const API_BASE = '/api/errors';

// Global state
let currentErrorId = null;
let allErrors = [];

// Initialize
document.addEventListener('DOMContentLoaded', () => {
    loadStatistics();
    loadErrors();
    setupEventListeners();
    
    // Auto-refresh every 30 seconds
    setInterval(() => {
        loadStatistics();
        loadErrors();
    }, 30000);
});

// Setup event listeners
function setupEventListeners() {
    // Refresh button
    document.getElementById('refreshBtn').addEventListener('click', () => {
        loadStatistics();
        loadErrors();
    });
    
    // Apply filters
    document.getElementById('applyFiltersBtn').addEventListener('click', () => {
        loadErrors();
    });
    
    // Search input
    document.getElementById('searchInput').addEventListener('keypress', (e) => {
        if (e.key === 'Enter') {
            loadErrors();
        }
    });
    
    // Modal close buttons
    document.querySelectorAll('.close, .close-modal').forEach(btn => {
        btn.addEventListener('click', (e) => {
            const modal = e.target.closest('.modal');
            if (modal) {
                modal.style.display = 'none';
            }
        });
    });
    
    // Close modal on outside click
    window.addEventListener('click', (e) => {
        if (e.target.classList.contains('modal')) {
            e.target.style.display = 'none';
        }
    });
    
    // Confirm buttons
    document.getElementById('confirmFixBtn').addEventListener('click', handleFixAndResume);
    document.getElementById('confirmRestartBtn').addEventListener('click', handleRestart);
    document.getElementById('confirmCancelBtn').addEventListener('click', handleCancel);
}

// Load statistics
async function loadStatistics() {
    try {
        const response = await fetch(`${API_BASE}/statistics`);
        const stats = await response.json();
        
        document.getElementById('totalErrors').textContent = stats.total || 0;
        document.getElementById('todayErrors').textContent = stats.today || 0;
        document.getElementById('highErrors').textContent = stats.high || 0;
        document.getElementById('newErrors').textContent = stats.new || 0;
    } catch (error) {
        console.error('Error loading statistics:', error);
    }
}

// Load errors
async function loadErrors() {
    try {
        const service = document.getElementById('serviceFilter').value;
        const status = document.getElementById('statusFilter').value;
        const search = document.getElementById('searchInput').value;
        
        let url = API_BASE;
        const params = new URLSearchParams();
        if (service) params.append('service', service);
        if (status) params.append('status', status);
        if (search) params.append('endToEndId', search);
        if (params.toString()) url += '?' + params.toString();
        
        const response = await fetch(url);
        allErrors = await response.json();
        
        renderErrors(allErrors);
    } catch (error) {
        console.error('Error loading errors:', error);
        document.getElementById('errorTableBody').innerHTML = 
            '<tr><td colspan="7" class="loading">Error loading errors. Please try again.</td></tr>';
    }
}

// Render errors table
function renderErrors(errors) {
    const tbody = document.getElementById('errorTableBody');
    
    if (errors.length === 0) {
        tbody.innerHTML = '<tr><td colspan="7" class="empty-state"><h3>No errors found</h3><p>All payments are processing successfully!</p></td></tr>';
        return;
    }
    
    tbody.innerHTML = errors.map(error => {
        const time = new Date(error.errorTimestamp || error.createdAt).toLocaleString();
        const severityClass = error.severity ? `badge-${error.severity.toLowerCase()}` : 'badge-low';
        const statusClass = error.status ? `badge-${error.status.toLowerCase().replace('_', '-')}` : 'badge-new';
        
        return `
            <tr>
                <td>${time}</td>
                <td>${error.endToEndId || 'N/A'}</td>
                <td>${formatServiceName(error.serviceName)}</td>
                <td>${error.serviceResult?.reason || 'Unknown error'}</td>
                <td><span class="badge ${severityClass}">${error.severity || 'LOW'}</span></td>
                <td><span class="badge ${statusClass}">${error.status || 'NEW'}</span></td>
                <td>
                    <button class="btn btn-primary btn-small" onclick="viewError('${error.errorId}')">View</button>
                </td>
            </tr>
        `;
    }).join('');
}

// Format service name
function formatServiceName(serviceName) {
    if (!serviceName) return 'Unknown';
    return serviceName.split('_').map(word => 
        word.charAt(0).toUpperCase() + word.slice(1)
    ).join(' ');
}

// View error details
async function viewError(errorId) {
    try {
        const response = await fetch(`${API_BASE}/${errorId}`);
        const error = await response.json();
        
        currentErrorId = errorId;
        showErrorDetails(error);
    } catch (error) {
        console.error('Error loading error details:', error);
        alert('Error loading error details');
    }
}

// Show error details modal
function showErrorDetails(error) {
    const modal = document.getElementById('errorModal');
    const modalBody = document.getElementById('modalBody');
    
    const paymentEvent = error.paymentEvent || {};
    const serviceResult = error.serviceResult || {};
    
    // Build processing history
    const history = buildProcessingHistory(error);
    
    modalBody.innerHTML = `
        <div class="error-detail">
            <div class="detail-section">
                <h3>Payment Information</h3>
                <div class="detail-row">
                    <div class="detail-label">End-to-End ID:</div>
                    <div class="detail-value">${error.endToEndId || 'N/A'}</div>
                </div>
                <div class="detail-row">
                    <div class="detail-label">Amount:</div>
                    <div class="detail-value">${paymentEvent.amount || 'N/A'} ${paymentEvent.currency || ''}</div>
                </div>
                <div class="detail-row">
                    <div class="detail-label">Debtor:</div>
                    <div class="detail-value">${paymentEvent.debtor?.name || 'N/A'}</div>
                </div>
                <div class="detail-row">
                    <div class="detail-label">Creditor:</div>
                    <div class="detail-value">${paymentEvent.creditor?.name || 'N/A'}</div>
                </div>
                <div class="detail-row">
                    <div class="detail-label">Original Message:</div>
                    <div class="detail-value">${paymentEvent.sourceMessageType?.value || 'N/A'}</div>
                </div>
            </div>
            
            <div class="detail-section">
                <h3>Error Information</h3>
                <div class="detail-row">
                    <div class="detail-label">Failed Step:</div>
                    <div class="detail-value">${formatServiceName(error.serviceName)}</div>
                </div>
                <div class="detail-row">
                    <div class="detail-label">Error Type:</div>
                    <div class="detail-value">${error.category || 'N/A'}</div>
                </div>
                <div class="detail-row">
                    <div class="detail-label">Error Code:</div>
                    <div class="detail-value">${serviceResult.reason || 'N/A'}</div>
                </div>
                <div class="detail-row">
                    <div class="detail-label">Error Message:</div>
                    <div class="detail-value">${serviceResult.message || serviceResult.reason || 'N/A'}</div>
                </div>
                <div class="detail-row">
                    <div class="detail-label">Failed At:</div>
                    <div class="detail-value">${new Date(error.errorTimestamp || error.createdAt).toLocaleString()}</div>
                </div>
                <div class="detail-row">
                    <div class="detail-label">Last Successful Step:</div>
                    <div class="detail-value">${formatServiceName(error.lastSuccessfulStep)}</div>
                </div>
            </div>
            
            <div class="detail-section">
                <h3>Processing History</h3>
                <ul class="processing-history">
                    ${history}
                </ul>
            </div>
            
            <div class="detail-section">
                <h3>Error Context</h3>
                <div class="detail-row">
                    <div class="detail-label">Service:</div>
                    <div class="detail-value">${error.serviceName || 'N/A'}</div>
                </div>
                <div class="detail-row">
                    <div class="detail-label">Error Topic:</div>
                    <div class="detail-value">${error.errorTopic || 'N/A'}</div>
                </div>
                <div class="detail-row">
                    <div class="detail-label">Severity:</div>
                    <div class="detail-value"><span class="badge badge-${error.severity?.toLowerCase() || 'low'}">${error.severity || 'LOW'}</span></div>
                </div>
            </div>
            
            <div class="modal-actions">
                ${error.status === 'NEW' || error.status === 'IN_PROGRESS' ? `
                    <button class="btn btn-primary" onclick="showFixModal()">Fix & Resume</button>
                    <button class="btn btn-primary" onclick="showRestartModal()">Restart from Beginning</button>
                    <button class="btn btn-danger" onclick="showCancelModal()">Cancel & Return</button>
                ` : ''}
                <button class="btn btn-secondary close-modal">Close</button>
            </div>
        </div>
    `;
    
    modal.style.display = 'block';
}

// Build processing history
function buildProcessingHistory(error) {
    const steps = [
        { name: 'Account Validation', key: 'account_validation' },
        { name: 'Routing Validation', key: 'routing_validation' },
        { name: 'Sanctions Check', key: 'sanctions_check' },
        { name: 'Balance Check', key: 'balance_check' },
        { name: 'Payment Posting', key: 'payment_posting' }
    ];
    
    const failedStep = error.serviceName;
    const lastSuccessful = error.lastSuccessfulStep;
    
    return steps.map(step => {
        if (step.key === failedStep) {
            return `<li class="failed">${step.name} - FAILED</li>`;
        } else if (step.key === lastSuccessful || 
                   (lastSuccessful === 'ingress' && step.key === 'account_validation')) {
            return `<li class="passed">${step.name} - PASSED</li>`;
        } else {
            return `<li class="not-started">${step.name} - NOT STARTED</li>`;
        }
    }).join('');
}

// Show fix modal
function showFixModal() {
    document.getElementById('fixModal').style.display = 'block';
}

// Show restart modal
function showRestartModal() {
    document.getElementById('restartModal').style.display = 'block';
}

// Show cancel modal
function showCancelModal() {
    document.getElementById('cancelModal').style.display = 'block';
}

// Handle fix and resume
async function handleFixAndResume() {
    if (!currentErrorId) return;
    
    const fixType = document.getElementById('fixType').value;
    const fixDetails = document.getElementById('fixDetails').value;
    const comments = document.getElementById('fixComments').value;
    
    try {
        const response = await fetch(`${API_BASE}/${currentErrorId}/fix-and-resume`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                performedBy: 'user', // In production, get from auth
                comments: comments,
                fixDetails: {
                    fixType: fixType,
                    fixMetadata: fixDetails
                }
            })
        });
        
        if (response.ok) {
            alert('Payment fixed and resumed successfully!');
            document.getElementById('fixModal').style.display = 'none';
            loadErrors();
            loadStatistics();
        } else {
            const error = await response.json();
            alert('Error: ' + (error.error || 'Failed to fix and resume'));
        }
    } catch (error) {
        console.error('Error fixing and resuming:', error);
        alert('Error fixing and resuming payment');
    }
}

// Handle restart
async function handleRestart() {
    if (!currentErrorId) return;
    
    const comments = document.getElementById('restartComments').value;
    
    try {
        const response = await fetch(`${API_BASE}/${currentErrorId}/restart`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                performedBy: 'user',
                comments: comments
            })
        });
        
        if (response.ok) {
            alert('Payment restarted from beginning!');
            document.getElementById('restartModal').style.display = 'none';
            loadErrors();
            loadStatistics();
        } else {
            const error = await response.json();
            alert('Error: ' + (error.error || 'Failed to restart'));
        }
    } catch (error) {
        console.error('Error restarting:', error);
        alert('Error restarting payment');
    }
}

// Handle cancel
async function handleCancel() {
    if (!currentErrorId) return;
    
    const reason = document.getElementById('cancelReason').value;
    const comments = document.getElementById('cancelComments').value;
    
    try {
        const response = await fetch(`${API_BASE}/${currentErrorId}/cancel`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                performedBy: 'user',
                cancellationReason: reason,
                comments: comments
            })
        });
        
        if (response.ok) {
            alert('Payment cancelled and returned!');
            document.getElementById('cancelModal').style.display = 'none';
            loadErrors();
            loadStatistics();
        } else {
            const error = await response.json();
            alert('Error: ' + (error.error || 'Failed to cancel'));
        }
    } catch (error) {
        console.error('Error cancelling:', error);
        alert('Error cancelling payment');
    }
}

