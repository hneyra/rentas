# Extraccion mecanica de la Tabla de Valores Referenciales de Vehiculos 2026
# (anexo de la R.M. N.° 008-2026-EF/15) a CSV.
#
# Tres mecanismos, ninguno manual:
#   Metodo A: cada palabra va a su columna por coordenada X; los limites de
#             columna se leen de los rectangulos que el propio PDF dibuja.
#   Metodo B: parseo independiente del texto plano de la linea (tres valores
#             numericos al final); una fila solo vale si A y B coinciden.
#   Rescate:  las filas cuyo modelo desborda su columna y se solapa con la
#             vecina se separan por ORDEN DEL STREAM del PDF: dentro de una
#             corrida de texto los caracteres vienen en orden, y el salto de
#             X hacia atras marca donde termina la celda "modelo 2025" y
#             empieza "modelo 2026".
#
# Uso: python3 extraer_tvr.py  (espera 7623157-anexo-tvr-ipv-2026.pdf al lado).
#      El PDF no se versiona en git: esta archivado en S3. Traerlo antes con
#      aws s3 cp s3://sgtm-fuentes-normativas/fuentes-normativas/vehicular/200105/\
#        2026-08-28T17-33-56Z__7623157-anexo-tvr-ipv-2026.pdf 7623157-anexo-tvr-ipv-2026.pdf
#      Ver README.md de este directorio para el sha256 con que comprobarlo.

import csv
import re
import sys
from collections import defaultdict

import pdfplumber

ENTRADA = "7623157-anexo-tvr-ipv-2026.pdf"
SALIDA = "tvr-2026.csv"

RE_VALOR = re.compile(r"^\d{1,3}(?:,\d{3})*$")
ENCABEZADOS = (
    "ANEXO",
    "TABLA DE VALORES",
    "PARA EFECTOS DE DETERMINAR",
    "(VALORES EXPRESADOS",
    "Año de fabricación",
    "Categoría Vehicular",
)


def limites_de_columnas(page):
    xs = sorted({round(r["x0"], 1) for r in page.rects} | {round(r["x1"], 1) for r in page.rects})
    bordes = []
    for x in xs:
        if not bordes or x - bordes[-1] > 2.0:
            bordes.append(x)
        else:
            bordes[-1] = (bordes[-1] + x) / 2
    if len(bordes) != 8:
        raise RuntimeError(f"pagina {page.page_number}: {len(bordes)} bordes, esperaba 8")
    return bordes


def rescatar_por_stream(chars, bordes):
    """Fila con corridas solapadas: separar modelo 2025 / modelo 2026 por stream."""
    partes = [[]]
    prev_x1 = None
    for c in chars:  # page.chars conserva el orden del stream
        if prev_x1 is not None and c["x0"] < prev_x1 - 1.0:
            partes.append([])
        partes[-1].append(c)
        prev_x1 = c["x1"]
    if len(partes) != 2:
        return None
    antes, despues = partes
    # antes del salto: categoria, marca y modelo 2025, separables por X porque
    # el desborde solo invade hacia la derecha
    cat = "".join(c["text"] for c in antes if c["x0"] < bordes[1]).strip()
    marca = "".join(c["text"] for c in antes if bordes[1] <= c["x0"] < bordes[2]).strip()
    m25 = "".join(c["text"] for c in antes if c["x0"] >= bordes[2]).strip()
    # despues del salto: agrupar en palabras por hueco de X; las tres ultimas
    # palabras numericas son los valores, lo anterior es el modelo 2026
    palabras = [[]]
    prev = None
    for c in despues:
        if prev is not None and c["x0"] - prev > 1.0:
            palabras.append([])
        palabras[-1].append(c)
        prev = c["x1"]
    tokens = ["".join(c["text"] for c in p) for p in palabras]
    if len(tokens) < 4 or not all(RE_VALOR.match(t) for t in tokens[-3:]):
        return None
    m26 = " ".join(tokens[:-3]).strip()
    v25, v24, v23 = tokens[-3:]
    fila = (cat, marca, m25, m26, v25, v24, v23)
    if all(fila[:4]):
        return fila
    return None


def main():
    filas = []
    rescatadas = []
    irresolubles = []
    with pdfplumber.open(ENTRADA) as pdf:
        for page in pdf.pages:
            bordes = limites_de_columnas(page)
            cols = list(zip(bordes[:-1], bordes[1:]))
            lineas = defaultdict(list)
            for w in page.extract_words():
                lineas[round(w["top"])].append(w)
            chars_por_linea = defaultdict(list)
            for c in page.chars:
                chars_por_linea[round(c["top"])].append(c)
            for top in sorted(lineas):
                ws = sorted(lineas[top], key=lambda w: w["x0"])
                texto = " ".join(w["text"] for w in ws)
                if any(texto.startswith(e) for e in ENCABEZADOS):
                    continue
                celdas = [[] for _ in cols]
                fuera = solapada = False
                prev_x1 = None
                for w in ws:
                    if prev_x1 is not None and w["x0"] < prev_x1 - 0.5:
                        solapada = True
                    prev_x1 = w["x1"]
                    cx = (w["x0"] + w["x1"]) / 2
                    for i, (a, b) in enumerate(cols):
                        if a <= cx < b:
                            celdas[i].append(w["text"])
                            break
                    else:
                        fuera = True
                c = [" ".join(x) for x in celdas]
                cat, marca, m25, m26, v25, v24, v23 = c
                partes = texto.split(" ")
                ok_b = len(partes) >= 4 and all(RE_VALOR.match(p) for p in partes[-3:])
                a_texto = " ".join(x for x in (cat, marca, m25, m26) if x)
                coincide = (
                    ok_b
                    and a_texto == " ".join(partes[:-3])
                    and (v25, v24, v23) == tuple(partes[-3:])
                )
                valida = all([cat, marca, m26]) and all(
                    RE_VALOR.match(v or "") for v in (v25, v24, v23)
                )
                if coincide and valida and not fuera and not solapada:
                    filas.append((cat, marca, m25, m26, v25, v24, v23))
                    continue
                fila = rescatar_por_stream(chars_por_linea[top], bordes)
                if fila:
                    filas.append(fila)
                    rescatadas.append((page.page_number, top, fila))
                else:
                    irresolubles.append((page.page_number, top, texto))

    print(f"filas: {len(filas)} | rescatadas por stream: {len(rescatadas)} | irresolubles: {len(irresolubles)}", file=sys.stderr)
    for r in rescatadas:
        print("  RESCATADA:", r, file=sys.stderr)
    for r in irresolubles:
        print("  IRRESOLUBLE:", r, file=sys.stderr)
    if irresolubles:
        raise SystemExit("hay filas irresolubles: no se escribe el CSV")

    por_categoria = defaultdict(int)
    no_multiplo = no_decreciente = 0
    for cat, _, _, _, v25, v24, v23 in filas:
        por_categoria[cat] += 1
        n25, n24, n23 = (int(v.replace(",", "")) for v in (v25, v24, v23))
        no_multiplo += any(n % 10 for n in (n25, n24, n23))
        no_decreciente += not (n25 >= n24 >= n23)
    print("por categoria:", dict(sorted(por_categoria.items())), file=sys.stderr)
    print(f"no multiplo de 10: {no_multiplo} | no decreciente: {no_decreciente}", file=sys.stderr)

    with open(SALIDA, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["categoria", "marca", "modelo_2025", "modelo_2026", "valor_2025", "valor_2024", "valor_2023"])
        w.writerows(filas)
    print(f"escrito {SALIDA} con {len(filas)} filas", file=sys.stderr)


if __name__ == "__main__":
    main()
