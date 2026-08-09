// Secret AI — gte-small embedding endpoint (Supabase Edge Function)
// ==================================================================
// Generates 384-dimensional embeddings with the model built into the Supabase
// edge runtime. This keeps all embedding compute inside Supabase (free tier),
// so the Python AI engine needs no torch / sentence-transformers and stays
// well under the 512MB limit.
//
// Deploy:
//   supabase functions deploy embed --project-ref <your-project-ref>
// Invoke (from the AI engine, core/embeddings.py):
//   POST https://<project>.supabase.co/functions/v1/embed
//   Headers: Authorization: Bearer <anon-or-service-key>, Content-Type: application/json
//   Body:    { "input": "some text" }  OR  { "input": ["text a", "text b"] }
//   Returns: { "embeddings": [[...384 floats...], ...] }

const model = new Supabase.ai.Session("gte-small");

Deno.serve(async (req) => {
  try {
    const body = await req.json();
    const input = body?.input;

    if (input === undefined || input === null) {
      return json({ error: "Missing 'input' field" }, 400);
    }

    const texts: string[] = Array.isArray(input) ? input : [input];
    if (texts.some((t) => typeof t !== "string")) {
      return json({ error: "'input' must be a string or array of strings" }, 400);
    }

    const embeddings: number[][] = [];
    for (const text of texts) {
      // mean_pool + normalize yields a unit-length vector suitable for cosine
      // similarity (the <=> operator in pgvector).
      const embedding = (await model.run(text, {
        mean_pool: true,
        normalize: true,
      })) as number[];
      embeddings.push(embedding);
    }

    return json({ embeddings });
  } catch (err) {
    return json({ error: String(err) }, 500);
  }
});

function json(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}
