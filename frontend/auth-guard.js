/**
 * DevAssist AI — Supabase Auth Guard & Utilities
 * ================================================
 * Shared auth module used by all pages.
 * Include AFTER the Supabase CDN script:
 *   <script src="https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2"></script>
 *   <script src="auth-guard.js"></script>
 */

// ==================== CONFIGURATION ====================
// Get these from: Supabase Dashboard > Settings > API
const SUPABASE_URL = 'https://xovfufudevsqdmqxmyge.supabase.co';
const SUPABASE_ANON_KEY = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InhvdmZ1ZnVkZXZzcWRtcXhteWdlIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzUzMTU3MzksImV4cCI6MjA5MDg5MTczOX0.LolsSVM-X6FgLpKeksjvrhMxltdXinc-K-zyN197QH0';

// Global Backend URL
// Replace with your Render URL (e.g., https://devassist-api.onrender.com)
const BACKEND_URL = window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1' 
    ? 'http://localhost:8080' 
    : 'https://devassist-backend.onrender.com';

// Log current config for debugging
console.log('🌐 Networking Config:', {
    BACKEND_URL,
    SUPABASE_URL,
    origin: window.location.origin
});
window.BACKEND_URL = BACKEND_URL;

// ==================== INITIALIZE CLIENT ====================
const _supabase = supabase.createClient(SUPABASE_URL, SUPABASE_ANON_KEY);

// ==================== SESSION HELPERS ====================

/**
 * Get the current Supabase session (access token + user info)
 * @returns {Object|null} Session object or null
 */
async function getSession() {
    const { data: { session }, error } = await _supabase.auth.getSession();
    if (error) console.warn('⚠ getSession error:', error.message);
    return session;
}

/**
 * Get the current authenticated user
 * @returns {Object|null} User object or null
 */
async function getUser() {
    const session = await getSession();
    return session?.user || null;
}

/**
 * Get the JWT access token for API calls
 * @returns {string|null} Bearer token string
 */
async function getAccessToken() {
    const session = await getSession();
    return session?.access_token || null;
}

/**
 * Sign out and redirect to login
 */
async function signOut() {
    await _supabase.auth.signOut();
    window.location.href = '/login.html';
}

// ==================== AUTH GUARD ====================

/**
 * Require authentication. Call on protected pages.
 * Redirects to login if no valid session found.
 * Handles OAuth callback tokens in URL.
 * @returns {Object|null} Session if authenticated
 */
async function requireAuth() {
    // 1. Check for existing stored session
    let session = await getSession();
    if (session) return session;

    // 2. Check if we're processing an OAuth callback (URL has code or tokens)
    const url = new URL(window.location.href);
    const hasCode = url.searchParams.has('code');
    const hasToken = window.location.hash.includes('access_token');

    if (hasCode || hasToken) {
        // OAuth callback in progress — wait for SDK to process
        return new Promise((resolve) => {
            const timeout = setTimeout(() => {
                console.warn('⚠ OAuth callback timeout — redirecting to login');
                window.location.href = '/login.html';
            }, 6000);

            const { data: { subscription } } = _supabase.auth.onAuthStateChange((event, newSession) => {
                if ((event === 'SIGNED_IN' || event === 'TOKEN_REFRESHED') && newSession) {
                    clearTimeout(timeout);
                    subscription.unsubscribe();
                    // Clean the URL
                    window.history.replaceState(null, '', window.location.pathname);
                    resolve(newSession);
                }
            });
        });
    }

    // 3. No session and no callback — redirect to login
    window.location.href = '/login.html';
    return null;
}

/**
 * Redirect to dashboard if user is already logged in.
 * Call on login/signup pages.
 * @param {string} targetUrl - Where to redirect (default: /dashboard.html)
 * @returns {boolean} true if redirecting
 */
async function redirectIfAuthenticated(targetUrl = '/dashboard.html') {
    const session = await getSession();
    if (session) {
        window.location.href = targetUrl;
        return true;
    }

    // Also check for OAuth callback on login page
    const url = new URL(window.location.href);
    const hasCode = url.searchParams.has('code');
    const hasToken = window.location.hash.includes('access_token');

    if (hasCode || hasToken) {
        return new Promise((resolve) => {
            const timeout = setTimeout(() => resolve(false), 5000);
            const { data: { subscription } } = _supabase.auth.onAuthStateChange((event, newSession) => {
                if ((event === 'SIGNED_IN') && newSession) {
                    clearTimeout(timeout);
                    subscription.unsubscribe();
                    window.location.href = targetUrl;
                    resolve(true);
                }
            });
        });
    }

    return false;
}

// ==================== USER INFO HELPERS ====================

/**
 * Get display name from user metadata
 */
function getUserDisplayName(user) {
    if (!user) return 'User';
    return user.user_metadata?.full_name
        || user.user_metadata?.name
        || user.email?.split('@')[0]
        || 'User';
}

/**
 * Get avatar URL from user metadata (Google profile picture)
 */
function getUserAvatar(user) {
    if (!user) return null;
    return user.user_metadata?.avatar_url
        || user.user_metadata?.picture
        || null;
}

/**
 * Get user's email
 */
function getUserEmail(user) {
    return user?.email || '';
}

/**
 * Get auth provider (google, email, etc.)
 */
function getUserProvider(user) {
    return user?.app_metadata?.provider || 'email';
}

// ==================== API FETCH HELPER ====================

/**
 * Make an authenticated API fetch call with the Supabase JWT
 * @param {string} url - API endpoint
 * @param {Object} options - fetch options
 * @returns {Response}
 */
async function authFetch(url, options = {}) {
    const token = await getAccessToken();
    if (!token) {
        console.error('❌ Auth Error: No session token found. Redirecting to login.');
        window.location.href = '/login.html';
        throw new Error('Not authenticated');
    }

    const headers = {
        ...options.headers,
        'Authorization': `Bearer ${token}`,
    };

    // Add Content-Type for JSON payloads
    if (options.body && typeof options.body === 'string') {
        headers['Content-Type'] = headers['Content-Type'] || 'application/json';
    }

    // Prefix with BACKEND_URL if it's a relative path
    const fullUrl = url.startsWith('http') ? url : `${BACKEND_URL}${url.startsWith('/') ? '' : '/'}${url}`;

    return fetch(fullUrl, { ...options, headers });
}

// ==================== TOAST NOTIFICATIONS ====================

/**
 * Show a toast notification
 * @param {string} message - Toast message
 * @param {string} type - 'success' | 'error' | 'info'
 * @param {number} duration - Display time in ms
 */
function showToast(message, type = 'info', duration = 4000) {
    let container = document.getElementById('toastContainer');
    if (!container) {
        container = document.createElement('div');
        container.id = 'toastContainer';
        container.style.cssText = 'position:fixed;top:24px;right:24px;z-index:10000;display:flex;flex-direction:column;gap:8px;';
        document.body.appendChild(container);
    }

    const toast = document.createElement('div');
    const colors = {
        success: { bg: 'rgba(34,197,94,0.15)', border: 'rgba(34,197,94,0.3)', icon: '✓', color: '#22c55e' },
        error:   { bg: 'rgba(239,68,68,0.15)',  border: 'rgba(239,68,68,0.3)',  icon: '✕', color: '#ef4444' },
        info:    { bg: 'rgba(99,102,241,0.15)', border: 'rgba(99,102,241,0.3)', icon: 'ℹ', color: '#818cf8' },
    };
    const c = colors[type] || colors.info;

    toast.style.cssText = `
        display:flex;align-items:center;gap:10px;padding:14px 20px;
        background:${c.bg};border:1px solid ${c.border};border-radius:12px;
        backdrop-filter:blur(20px);-webkit-backdrop-filter:blur(20px);
        font-family:'Inter',sans-serif;font-size:13px;color:#fff;
        box-shadow:0 8px 32px rgba(0,0,0,0.3);
        animation:toastIn 0.4s cubic-bezier(0.19,1,0.22,1);
        max-width:380px;
    `;
    toast.innerHTML = `
        <span style="width:24px;height:24px;border-radius:50%;background:${c.bg};border:1px solid ${c.border};
        display:flex;align-items:center;justify-content:center;font-size:12px;color:${c.color};flex-shrink:0;">${c.icon}</span>
        <span style="flex:1;line-height:1.4;">${message}</span>
    `;

    container.appendChild(toast);

    // Auto-remove
    setTimeout(() => {
        toast.style.animation = 'toastOut 0.3s ease forwards';
        setTimeout(() => toast.remove(), 300);
    }, duration);
}

// Add toast animation styles
(function injectToastStyles() {
    if (document.getElementById('toast-anim-styles')) return;
    const style = document.createElement('style');
    style.id = 'toast-anim-styles';
    style.textContent = `
        @keyframes toastIn { from { opacity:0; transform:translateX(40px) scale(0.95); } to { opacity:1; transform:translateX(0) scale(1); } }
        @keyframes toastOut { from { opacity:1; transform:translateX(0); } to { opacity:0; transform:translateX(40px); } }
    `;
    document.head.appendChild(style);
})();

// ==================== GLOBAL AUTH LISTENER ====================
_supabase.auth.onAuthStateChange((event, session) => {
    if (event === 'SIGNED_OUT') {
        const publicPages = ['/login.html', '/signup.html'];
        const path = window.location.pathname;
        if (!publicPages.some(p => path.endsWith(p))) {
            window.location.href = '/login.html';
        }
    }
});

console.log('🔐 DevAssist Auth Guard loaded');
