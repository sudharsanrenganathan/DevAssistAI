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
        if file_path.startswith("http://") or file_path.startswith("https://"):
            import urllib.request
            import io
            req = urllib.request.Request(file_path, headers={'User-Agent': 'Mozilla/5.0'})
            with urllib.request.urlopen(req, timeout=10) as response:
                pdf_bytes = response.read()
            reader = PdfReader(io.BytesIO(pdf_bytes))
        else:
            reader = PdfReader(file_path)

        for page in reader.pages:
            extracted = page.extract_text()
            if extracted:
                text += extracted + "\n"

    except Exception as e:
        print("PDF text extraction failed:", e)

    # ---- Step 2: OCR fallback for scanned PDFs ----
    if text.strip() == "" and OCR_AVAILABLE:
        print("No text found, running OCR...")
        try:
            if file_path.startswith("http://") or file_path.startswith("https://"):
                from pdf2image import convert_from_bytes
                images = convert_from_bytes(pdf_bytes, poppler_path=r"C:\poppler\Library\bin")
            else:
                images = convert_from_path(file_path, poppler_path=r"C:\poppler\Library\bin")

            for img in images:
                ocr_text = pytesseract.image_to_string(img)
                text += ocr_text + "\n"
        except Exception as e:
            print("OCR extraction failed:", e)

    return text


if __name__ == "__main__":

    file = "sample.pdf"  # test file

    content = load_pdf(file)

    print("\nExtracted Document Content:\n")
    print(content[:1000])  # print first 1000 characters