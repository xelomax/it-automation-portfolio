"""
Migracion masiva de documentos de empleados: BambooHR -> HiBob

Flujo por empleado:
  1. Busca al empleado en HiBob por email (POST /v1/people/search)
  2. Obtiene catalogo de documentos desde BambooHR
  3. Filtra por CATEGORY_MAP (BambooHR category_id -> HiBob folder_id)
  4. Descarga cada archivo desde BambooHR
  5. PDFs: pipeline de sanitizacion en cascada (pikepdf -> PyPDF2 -> PyMuPDF)
     con multiples intentos de nombre/content-type hasta que HiBob lo acepta
  6. Archivos grandes (>2MB) en carpeta custom: redirige a /shared/upload
  7. Backoff exponencial en errores 429/5xx
  8. Reporte Excel: 3 hojas (resultado / errores / meta)

Variables de entorno requeridas:
  BAMBOO_SUBDOMAIN, BAMBOO_API_KEY
  HIBOB_SERVICE_ID, HIBOB_SERVICE_TOKEN
  INPUT_XLSX   (Excel con columnas: bamboo_employee_id, hibob_email)
  OUTPUT_XLSX  (ruta de salida del reporte)
"""

import os, re, json, base64, logging, mimetypes, traceback, time, random, io, unicodedata
from datetime import datetime
import pandas as pd
import requests

logging.basicConfig(level=logging.INFO, format="%(message)s")

# --- CONFIGURACION ---
INPUT_XLSX  = os.environ.get("INPUT_XLSX",  "input/usuarios.xlsx")
OUTPUT_XLSX = os.environ.get("OUTPUT_XLSX", "output/resultado_migracion.xlsx")

BAMBOO_SUBDOMAIN = os.environ["BAMBOO_SUBDOMAIN"]
BAMBOO_API_KEY   = os.environ["BAMBOO_API_KEY"]

HIBOB_BASE_URL      = "https://api.hibob.com"
HIBOB_SERVICE_ID    = os.environ["HIBOB_SERVICE_ID"]
HIBOB_SERVICE_TOKEN = os.environ["HIBOB_SERVICE_TOKEN"]

MAX_MB              = 100
GET_RETRIES         = 5
POST_RETRIES        = 5
BACKOFF_BASE        = 1.5
HIBOB_CUSTOM_MAX_MB = 2

# Mapeo: BambooHR category_id -> HiBob folder_id o "confidential"
# Obtener los IDs de HiBob con: GET /v1/docs/people/{id}/shared
CATEGORY_MAP = {
    141: "folder:YOUR_FOLDER_ID_1",
    18:  "folder:YOUR_FOLDER_ID_2",
    135: "folder:YOUR_FOLDER_ID_3",
    16:  "folder:YOUR_FOLDER_ID_4",
    22:  "folder:YOUR_FOLDER_ID_5",
    131: "folder:YOUR_FOLDER_ID_6",
    130: "folder:YOUR_FOLDER_ID_7",
    21:  "folder:YOUR_FOLDER_ID_8",
    20:  "confidential",
    17:  "folder:YOUR_FOLDER_ID_9",
    132: "folder:YOUR_FOLDER_ID_10",
    19:  "folder:YOUR_FOLDER_ID_11",
}


# --- UTILIDADES ---
def guess_content_type(filename):
    ct, _ = mimetypes.guess_type(filename)
    if ct:
        return ct
    return {
        ".pdf":  "application/pdf",
        ".doc":  "application/msword",
        ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        ".png":  "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg",
        ".zip":  "application/zip", ".txt": "text/plain",
        ".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    }.get(os.path.splitext(filename)[1].lower(), "application/octet-stream")


_illegal_re = re.compile(r'[<>:"/\\|?*\x00-\x1F]')

def safe_filename(name, limit=160):
    base, ext = os.path.splitext(name)
    base = unicodedata.normalize("NFKD", base)
    base = "".join(ch for ch in base if not unicodedata.combining(ch))
    base = _illegal_re.sub("_", base)
    base = re.sub(r"\s+", " ", base).strip() or "documento"
    return base[:max(8, limit - len(ext))].rstrip(". ") + ext


def hibob_auth_header():
    raw = f"{HIBOB_SERVICE_ID}:{HIBOB_SERVICE_TOKEN}".encode()
    return {"Authorization": "Basic " + base64.b64encode(raw).decode(), "Accept": "application/json"}


def _sleep_backoff(i):
    time.sleep((BACKOFF_BASE ** i) + random.uniform(0, 0.4))


def request_with_retry(method, url, retries=3, acceptable=(200,), **kwargs):
    """HTTP con reintentos exponenciales para 429/5xx."""
    timeout = kwargs.pop("timeout", 60)
    for i in range(retries):
        try:
            r = requests.request(method, url, timeout=timeout, **kwargs)
            if r.status_code in acceptable:
                return r
            if r.status_code in (429, 500, 502, 503, 504):
                _sleep_backoff(i)
                continue
            return r
        except requests.RequestException:
            _sleep_backoff(i)
    return requests.request(method, url, timeout=timeout, **kwargs)


# --- PIPELINE DE SANITIZACION PDF ---
# HiBob rechaza PDFs con formularios, anotaciones o metadata corrupta.
# Se prueba en cascada con hasta 4 librerias hasta que una produce un binario aceptado.

def sanitize_pdf_pikepdf(pdf_bytes):
    try:
        import pikepdf
        with pikepdf.open(io.BytesIO(pdf_bytes), allow_overwriting_input=True) as pdf:
            for k in ["/Metadata", "/AcroForm"]:
                if k in pdf.root:
                    try: del pdf.root[k]
                    except: pass
            for page in pdf.pages:
                if "/Annots" in page:
                    try: del page["/Annots"]
                    except: pass
            opts = dict(min_version=pikepdf.PdfVersion.v1_4,
                        object_stream_mode=pikepdf.ObjectStreamMode.disable,
                        compress_streams=True)
            out_n, out_l = io.BytesIO(), io.BytesIO()
            pdf.save(out_n, **opts, linearize=False)
            pdf.save(out_l, **opts, linearize=True, fix_metadata_version=True)
            return out_n.getvalue(), out_l.getvalue()
    except Exception:
        return None, None


def sanitize_pdf_pypdf2(pdf_bytes):
    try:
        from PyPDF2 import PdfReader, PdfWriter
        w = PdfWriter()
        for page in PdfReader(io.BytesIO(pdf_bytes), strict=False).pages:
            w.add_page(page)
        w.add_metadata({})
        out = io.BytesIO()
        w.write(out)
        return out.getvalue()
    except Exception:
        return None


def sanitize_pdf_pymupdf(pdf_bytes):
    try:
        import fitz
        src, out = fitz.open(stream=pdf_bytes, filetype="pdf"), fitz.open()
        out.insert_pdf(src)
        buf = out.tobytes(deflate=True, garbage=4)
        src.close(); out.close()
        return buf
    except Exception:
        return None


def build_pdf_attempts(fname, fb):
    """Construye la lista de intentos para PDFs: original + 4 variantes sanitizadas
    x nombre original/seguro x content-type pdf/octet-stream."""
    safe_fn  = safe_filename(fname)
    variants = [("orig", fname, fb)]
    pn, pl   = sanitize_pdf_pikepdf(fb)
    if pn and pn != fb:       variants.append(("pike-norm", fname, pn))
    if pl and pl not in (fb, pn): variants.append(("pike-lin",  fname, pl))
    p2 = sanitize_pdf_pypdf2(fb)
    if p2 and p2 not in (fb, pn, pl): variants.append(("pypdf2", fname, p2))
    pm = sanitize_pdf_pymupdf(fb)
    if pm and pm not in (fb, pn, pl, p2): variants.append(("pymupdf", fname, pm))

    attempts = []
    for tag, fn, data in variants:
        for ct in ("application/pdf", "application/octet-stream"):
            short = ct.split("/")[-1]
            attempts.append((f"{tag}+{short}+ORIG", fn,      data, ct))
            if safe_fn != fn:
                attempts.append((f"{tag}+{short}+SAFE", safe_fn, data, ct))
    return attempts


# --- HIBOB API ---
def hibob_find_person_id(auth, email):
    """Busca el ID interno de HiBob de un empleado por email."""
    r = request_with_retry(
        "POST", f"{HIBOB_BASE_URL}/v1/people/search",
        headers={**auth, "Content-Type": "application/json"},
        data=json.dumps({"query": {"operator": "or", "conditions": [
            {"field_path": "root.email", "operator": "equals", "value": email},
            {"field_path": "work.email", "operator": "equals", "value": email},
        ]}, "fields": ["root.id", "root.email", "work.email"], "limit": 25}),
        retries=GET_RETRIES, acceptable=(200,))
    if r.status_code != 200:
        return None
    for p in (r.json() or {}).get("people", []):
        mail = p.get("root", {}).get("email") or p.get("work", {}).get("email") or ""
        if mail.lower() == email.lower():
            return p.get("root", {}).get("id")
    return None


def hibob_upload(auth, person_id, endpoint, filename, data, ct):
    r = request_with_retry(
        "POST", f"{HIBOB_BASE_URL}/v1/docs/people/{person_id}/{endpoint}",
        headers=auth, files={"file": (filename, data, ct)},
        retries=POST_RETRIES, acceptable=(200, 201, 202), timeout=180)
    return (r.status_code in (200, 201, 202), r.status_code, r.text,
            r.headers.get("X-Request-Id", ""))


# --- BAMBOOHR API ---
def bamboo_get_file_list(emp_id):
    r = request_with_retry(
        "GET",
        f"https://api.bamboohr.com/api/gateway.php/{BAMBOO_SUBDOMAIN}/v1/employees/{emp_id}/files/view",
        headers={"Accept": "application/json"}, auth=(BAMBOO_API_KEY, "x"),
        retries=GET_RETRIES, acceptable=(200,))
    r.raise_for_status()
    return r.json()


def bamboo_download_file(emp_id, file_id):
    r = request_with_retry(
        "GET",
        f"https://api.bamboohr.com/api/gateway.php/{BAMBOO_SUBDOMAIN}/v1/employees/{emp_id}/files/{file_id}",
        headers={"Accept": "application/octet-stream"}, auth=(BAMBOO_API_KEY, "x"),
        retries=GET_RETRIES, acceptable=(200,), timeout=90)
    r.raise_for_status()
    return r.content


# --- PROCESO POR EMPLEADO ---
def process_employee(bamboo_id, hibob_email, auth):
    results = []

    def log(action, status, detail, file=""):
        results.append({"hibob_email": hibob_email, "bamboo_employee_id": bamboo_id,
                         "action": action, "status": status, "detail": detail, "file": file})

    print(f"\n=== {hibob_email} | BambooID: {bamboo_id} ===")

    person_id = hibob_find_person_id(auth, hibob_email)
    if not person_id:
        log("lookup", "ERROR", "No encontrado en HiBob")
        return results

    try:
        docs = bamboo_get_file_list(bamboo_id)
    except Exception as e:
        log("list", "ERROR", str(e))
        return results

    df = pd.json_normalize(docs, record_path=["categories", "files"],
                            meta=[["categories", "id"], ["categories", "name"]], errors="ignore")
    df["categories.id"] = pd.to_numeric(df.get("categories.id"), errors="coerce").astype("Int64")
    df = df[df["categories.id"].apply(lambda x: int(x) in CATEGORY_MAP if pd.notnull(x) else False)]

    if df.empty:
        log("filter", "OK", "Sin documentos en CATEGORY_MAP")
        return results

    for _, row in df.iterrows():
        file_id = int(row["id"])
        fname   = str(row["name"])
        target  = CATEGORY_MAP.get(int(row["categories.id"]))
        ct      = guess_content_type(fname)
        print(f"  [FILE] {fname} -> {target}")

        try:
            fb = bamboo_download_file(bamboo_id, file_id)
        except Exception as e:
            log("download", "ERROR", str(e), fname)
            continue

        if not fb or len(fb) > MAX_MB * 1024 * 1024:
            log("size_check", "SKIP", f">{MAX_MB}MB", fname)
            continue

        # Archivos grandes en carpeta custom -> redirigir a shared
        if target and target.startswith("folder:") and len(fb) > HIBOB_CUSTOM_MAX_MB * 1024 * 1024:
            ok, sc, _, _ = hibob_upload(auth, person_id, "shared/upload", safe_filename(fname), fb, ct)
            if ok:
                log("upload", "OK", "Subido a shared (redireccion por tamano)", fname)
                continue

        # Construir intentos de subida
        if ct == "application/pdf":
            attempts = build_pdf_attempts(fname, fb)
        else:
            safe_fn  = safe_filename(fname)
            attempts = [
                ("orig+ct",    fname,   fb, ct),
                ("orig+octet", fname,   fb, "application/octet-stream"),
                ("safe+ct",    safe_fn, fb, ct),
                ("safe+octet", safe_fn, fb, "application/octet-stream"),
            ]

        if target == "confidential":
            endpoint = "confidential/upload"
        elif target and target.startswith("folder:"):
            endpoint = f"folders/{target.split(':')[1]}/upload"
        else:
            log("route", "SKIP", f"Destino no mapeado: {target}", fname)
            continue

        ok_final, sc_final, req_id = False, None, ""
        for tag, up_name, up_bytes, up_ct in attempts:
            print(f"    [TRY {tag}] {up_name!r} {up_ct}")
            ok, sc, body, req_id = hibob_upload(auth, person_id, endpoint, up_name, up_bytes, up_ct)
            ok_final, sc_final = ok, sc
            if ok:
                print(f"    -> OK (HTTP {sc})")
                break
            print(f"    -> ERROR (HTTP {sc}) {body[:120]}")
            time.sleep(0.15)

        detail = "Subido" if ok_final else f"Fallo HTTP {sc_final}" + (f" ReqId {req_id}" if req_id else "")
        log("upload", "OK" if ok_final else "ERROR", detail, fname)
        time.sleep(0.2)

    return results


# --- MAIN ---
if __name__ == "__main__":
    auth  = hibob_auth_header()
    df_in = pd.read_excel(INPUT_XLSX).dropna(subset=["hibob_email", "bamboo_employee_id"])
    results, errors = [], []

    for _, row in df_in.iterrows():
        email = str(row["hibob_email"]).strip()
        bid   = int(row["bamboo_employee_id"])
        try:
            results.extend(process_employee(bid, email, auth))
        except Exception as e:
            errors.append({"hibob_email": email, "bamboo_employee_id": bid,
                            "error": str(e), "trace": traceback.format_exc()})
        time.sleep(0.5)

    df_out = pd.DataFrame(results, columns=["hibob_email", "bamboo_employee_id",
                                             "action", "status", "detail", "file"])
    meta   = pd.DataFrame([{"generated_at": datetime.now().isoformat(timespec="seconds"),
                             "input_rows": len(df_in), "output_rows": len(df_out)}])

    os.makedirs(os.path.dirname(os.path.abspath(OUTPUT_XLSX)), exist_ok=True)
    with pd.ExcelWriter(OUTPUT_XLSX, engine="openpyxl") as w:
        df_out.to_excel(w, index=False, sheet_name="resultado")
        if errors:
            pd.DataFrame(errors).to_excel(w, index=False, sheet_name="errores")
        meta.to_excel(w, index=False, sheet_name="meta")

    print(f"\nReporte generado: {OUTPUT_XLSX}")
    print(f"Total: {len(df_in)} empleados | {len(df_out)} archivos procesados")
