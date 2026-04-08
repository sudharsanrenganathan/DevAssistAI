try:
    import pytesseract
    from pypdf import PdfReader
    from pdf2image import convert_from_path
    OCR_AVAILABLE = True
except ImportError:
    OCR_AVAILABLE = False
    from pypdf import PdfReader
    print("⚠ OCR libraries not fully installed. Falling back to plain text extraction.")

# Tell Python where Tesseract OCR is installed (Only relevant for local dev)
if OCR_AVAILABLE:
    pytesseract.pytesseract.tesseract_cmd = r"C:\Program Files\Tesseract-OCR\tesseract.exe"


def load_pdf(file_path):

    text = ""

    # ---- Step 1: Try extracting normal text ----
    try:
        reader = PdfReader(file_path)

        for page in reader.pages:
            extracted = page.extract_text()
            if extracted:
                text += extracted + "\n"

    except Exception as e:
        print("PDF text extraction failed:", e)

    # ---- Step 2: OCR fallback for scanned PDFs ----
    if text.strip() == "":
        print("No text found, running OCR...")

        images = convert_from_path(file_path, poppler_path=r"C:\poppler\Library\bin")

        for img in images:
            ocr_text = pytesseract.image_to_string(img)
            text += ocr_text + "\n"

    return text


if __name__ == "__main__":

    file = "sample.pdf"  # test file

    content = load_pdf(file)

    print("\nExtracted Document Content:\n")
    print(content[:1000])  # print first 1000 characters