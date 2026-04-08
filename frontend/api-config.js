/**
 * DevAssist AI — API Configuration
 * ===============================
 * Centralized configuration for backend and Supabase.
 */

const API_CONFIG = {
    // Replace with your production Render URL (e.g., https://devassist-api.onrender.com)
    // Keep as http://localhost:8080 for local development
    BACKEND_URL: 'http://localhost:8080',
    
    // Supabase credentials (same as in auth-guard.js)
    SUPABASE_URL: 'https://xovfufudevsqdmqxmyge.supabase.co',
    SUPABASE_ANON_KEY: 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InhvdmZ1ZnVkZXZzcWRtcXhteWdlIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzUzMTU3MzksImV4cCI6MjA5MDg5MTczOX0.LolsSVM-X6FgLpKeksjvrhMxltdXinc-K-zyN197QH0'
};

// Export the config
window.API_CONFIG = API_CONFIG;
