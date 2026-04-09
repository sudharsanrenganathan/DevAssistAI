from rag.document_loader import load_pdf

def split_document(file_path):
    from langchain_text_splitters import RecursiveCharacterTextSplitter

    # Load document
    text = load_pdf(file_path)

    # Create text splitter
    splitter = RecursiveCharacterTextSplitter(
        chunk_size=500,
        chunk_overlap=100
    )

    # Split text
    chunks = splitter.split_text(text)

    return chunks


if __name__ == "__main__":

    file = "sample.pdf"

    chunks = split_document(file)

    print("\nTotal Chunks Created:", len(chunks))

    print("\nFirst Chunk:\n")
    print(chunks[0])