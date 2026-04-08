// Supabase Configuration
// Replace these placeholders with your actual Supabase project credentials
const SUPABASE_URL = "YOUR_SUPABASE_URL";
const SUPABASE_ANON_KEY = "YOUR_SUPABASE_ANON_KEY";

// Initialize Supabase Client
const supabase = supabase.createClient(SUPABASE_URL, SUPABASE_ANON_KEY);

// Export for use in other scripts (if using modules)
// If using plain scripts, it will be globally available via the 'supabase' variable
