#!/usr/bin/env python3
"""
Canoniza la representación textual de un retrato de esquema, para que el diff hable de
esquema y no de cómo PostgreSQL vuelve a imprimir una expresión.

POR QUÉ HACE FALTA, Y POR QUÉ NO OCULTA NADA
--------------------------------------------
`pg_get_constraintdef` NO es idempotente para un `CHECK ... IN (...)`. La misma restricción,
escrita una vez y reimpresa, sale así en el esquema original:

    (tipo)::text = ANY ((ARRAY['A'::character varying, 'B'::character varying])::text[])

y así cuando ese texto se vuelve a ejecutar y a reimprimir:

    (tipo)::text = ANY (ARRAY[('A'::character varying)::text, ('B'::character varying)::text])

Son la misma restricción, y no es una opinión: se comprobó EJECUTANDO. `Equiv.java` evalúa la
expresión que cada base tiene en su catálogo contra los cinco valores válidos de
`acta_fiscalizacion.hallazgo` y contra cuatro que no lo son, en las dos bases, y las dos
aceptan y rechazan exactamente lo mismo.

La canonización se aplica **a los dos lados por igual** y sólo toca casts redundantes sobre
literales. Nunca elimina un literal, un nombre ni un operador: si a un CHECK le faltara un
valor, el diff lo seguiría enseñando.
"""
import re
import sys

REGLAS = [
    # ('A'::character varying)::text  ->  'A'
    (re.compile(r"\('([^']*)'::character varying\)::text"), r"'\1'"),
    # 'A'::character varying  ->  'A'
    (re.compile(r"'([^']*)'::character varying"), r"'\1'"),
    # (ARRAY[...])::text[]  ->  ARRAY[...]
    (re.compile(r"\(ARRAY\[([^\]]*)\]\)::text\[\]"), r"ARRAY[\1]"),
]


def canonizar(texto: str) -> str:
    for patron, reemplazo in REGLAS:
        texto = patron.sub(reemplazo, texto)
    return texto


if __name__ == "__main__":
    entrada, salida = sys.argv[1], sys.argv[2]
    original = open(entrada, encoding="utf-8").read()
    resultado = canonizar(original)
    open(salida, "w", encoding="utf-8").write(resultado)
    tocadas = sum(1 for a, b in zip(original.splitlines(), resultado.splitlines()) if a != b)
    print(f"canonizadas {tocadas} lineas de {len(original.splitlines())}")
