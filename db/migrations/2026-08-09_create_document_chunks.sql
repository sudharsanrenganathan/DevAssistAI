-- Secret AI RAG — pgvector chunk store
-- ====================================
-- Replaces the "stuff first 30K chars into the prompt" approach with real
-- semantic retrieval. Embeddings are 384-dim (Supabase gte-small edge function),
-- so the AI engine needs no local ML libraries.
--
-- Apply in the Supabase SQL editor (or psql against DATABASE_URL).

create extension if not exists vector;

create table if not exists document_chunks (
    id            bigserial   primary key,
    document_key  text        not null,          -- stable per-document key (Supabase Storage URL)
    user_id       text,                           -- owner, for isolation + cleanup on delete
    chunk_index   int         not null,           -- position of the chunk within the document
    content       text        not null,
    embedding     vector(384) not null,           -- gte-small dimension; must match EMBEDDING_DIM
    created_at    timestamptz not null default now(),
    unique (document_key, chunk_index)            -- idempotent re-indexing
);

-- Fast lookup / existence check / delete by document.
create index if not exists document_chunks_key_idx
    on document_chunks (document_key);

-- Approximate nearest-neighbour search over cosine distance (the <=> operator).
create index if not exists document_chunks_embedding_idx
    on document_chunks using hnsw (embedding vector_cosine_ops);

-- Cleanup helper (run when a document is deleted):
--   delete from document_chunks where document_key = :url;
