from sentence_transformers import SentenceTransformer
import faiss
import numpy as np
from rag.text_splitter import split_document


def create_vector_store(file_path):

    # Load chunks from document
    chunks = split_document(file_path)

    # Safety check: ensure text exists
    if not chunks or len(chunks) == 0:
        raise Exception("No readable text found in the document")

    # Load embedding model
    model = SentenceTransformer('all-MiniLM-L6-v2')

    # Convert chunks into embeddings
    embeddings = model.encode(chunks)

    # Convert to numpy
    embeddings = np.array(embeddings).astype("float32")

    # Safety check: ensure embeddings exist
    if embeddings.shape[0] == 0:
        raise Exception("Embedding generation failed")

    # Create FAISS index
    dimension = embeddings.shape[1]
    index = faiss.IndexFlatL2(dimension)

    # Add embeddings to FAISS
    index.add(embeddings)

    return index, chunks, model


if __name__ == "__main__":

    file = "sample.pdf"

    index, chunks, model = create_vector_store(file)

    print("Vector database created!")
    print("Total vectors stored:", index.ntotal)