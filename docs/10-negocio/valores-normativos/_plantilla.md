# ‹Dato que fija esta norma›

| Campo | Valor |
|---|---|
| Norma | ‹D.S. 156-2004-EF, TUO de la Ley de Tributación Municipal› |
| Artículo | ‹13› |
| Publicada | ‹AAAA-MM-DD›, ‹El Peruano› |
| Ejercicios que rige | ‹2004›–‹› |
| Filas de NEG-02 §2 | ‹2› |
| Transcribió | ‹Nombre›, ‹AAAA-MM-DD› |
| Verificó | — |
| Estado | TRANSCRITO |

## 1. La tabla tal como está en la norma

**Sin reordenar, sin convertir unidades, sin corregir un encabezado.** Si la norma expresa el tramo
en UIT, aquí va en UIT; si numera las categorías con letras, aquí van con letras.

| ‹columna tal como se llama en la norma› | ‹otra› |
|---|---|
| ‹valor› | ‹valor› |

> Cita literal del artículo, entre comillas, si el texto añade una condición que la tabla no
> muestra.

## 2. Cómo entra al sistema

| Qué | Dónde |
|---|---|
| Tipo | ‹`parametro_tributario.tipo`, o el nombre de la tabla específica› |
| Clave | ‹cómo se forma la clave› |
| Ámbito | ‹nacional / de la municipalidad› |
| Vigencia | ‹desde qué ejercicio, y hasta cuál› |

**No se carga con este archivo.** Se carga con el derivado de [`publicacion/`](publicacion/), que es lo que `PublicarParametros` lee (#188); este archivo es su fuente, no su entrada. D-13 se cerró el 2026-08-28 y ya no bloquea nada.

## 3. Qué no cabe hoy

‹Lo que el esquema todavía no puede guardar, o «Nada».›
