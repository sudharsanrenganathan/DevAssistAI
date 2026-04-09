// Supabase Configuration
const SUPABASE_URL = "https://xovfufudevsqdmqxmyge.supabase.co";
const SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InhvdmZ1ZnVkZXZzcWRtcXhteWdlIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzUzMTU3MzksImV4cCI6MjA5MDg5MTczOX0.LolsSVM-X6FgLpKeksjvrhMxltdXinc-K-zyN197QH0";

// Initialize Supabase Client
const supabaseClient = supabase.createClient(SUPABASE_URL, SUPABASE_ANON_KEY);
window.supabaseClient = supabaseClient;
