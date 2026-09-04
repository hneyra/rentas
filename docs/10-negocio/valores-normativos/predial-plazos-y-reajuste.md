# Vencimientos del predial y reajuste por IPM

| Campo | Valor |
|---|---|
| Norma | TUO de la Ley de Tributación Municipal (D.S. 156-2004-EF) |
| Artículo | 15, incisos a) y b) (vencimientos); 15, inciso b) (reajuste) |
| Publicada | 2004-11-15, El Peruano |
| Ejercicios que rige | 2004– |
| Filas de NEG-02 §2 | 6, 20 |
| Transcribió | JNA, 2026-08-24 |
| Verificó | HNA, 2026-08-25 |
| Estado | VERIFICADO |

## 1. La tabla tal como está en la norma

**Sin reordenar, sin convertir unidades, sin corregir un encabezado.**

| Alternativa de pago | Vencimiento |
|---|---|
| a) Al contado | Hasta el último día hábil del mes de febrero de cada año |
| b) Fraccionado, primera cuota (de cuatro trimestrales, un cuarto del impuesto total cada una) | Hasta el último día hábil del mes de febrero |
| b) Segunda cuota | Hasta el último día hábil del mes de mayo |
| b) Tercera cuota | Hasta el último día hábil del mes de agosto |
| b) Cuarta cuota | Hasta el último día hábil del mes de noviembre |

> Texto literal del artículo 15: «El impuesto podrá cancelarse de acuerdo a las siguientes
> alternativas: a) Al contado, hasta el último día hábil del mes de febrero de cada año. b) En
> forma fraccionada, hasta en cuatro cuotas trimestrales. En este caso, la primera cuota será
> equivalente a un cuarto del impuesto total resultante y deberá pagarse hasta el último día hábil
> del mes de febrero. Las cuotas restantes serán pagadas hasta el último día hábil de los meses de
> mayo, agosto y noviembre, debiendo ser reajustadas de acuerdo a la variación acumulada del Índice
> de Precios al Por Mayor (IPM) que publica el Instituto Nacional de Estadística e Informática
> (INEI), por el período comprendido desde el mes de vencimiento de pago de la primera cuota y el
> mes precedente al pago.»
>
> Reajuste (inciso b, segundo párrafo): la segunda, tercera y cuarta cuota trimestral se reajustan
> según la variación acumulada del IPM que publica el INEI, por el período comprendido entre el mes
> de vencimiento de la primera cuota y el mes precedente al pago. La primera cuota, y el pago al
> contado, no llevan reajuste.
>
> La **prórroga** que una ordenanza local pueda dar sobre estos vencimientos es otro dato, y es
> D-02c — no se transcribe en este archivo.

## 2. Cómo entra al sistema

| Qué | Dónde |
|---|---|
| Tipo | `parametro_tributario` (tipo `PREDIAL_VENCIMIENTO`, una fila por cuota; `PREDIAL_REAJUSTE_IPM` para el índice) |
| Clave | `PREDIAL_VENCIMIENTO-CONTADO`, `PREDIAL_VENCIMIENTO-1`..`PREDIAL_VENCIMIENTO-4`, con el ejercicio como parte de la clave compuesta (el día exacto —"último día hábil"— depende del calendario de cada año) |
| Ámbito | nacional |
| Vigencia | 2004–, sin modificación conocida a la fecha de esta transcripción |

**No se carga con este archivo.** Se carga con el derivado de [`publicacion/`](publicacion/), que es lo que `PublicarParametros` lee (#188); este archivo es su fuente, no su entrada. D-13 se cerró el 2026-08-28 y ya no bloquea nada.

## 3. Qué no cabe hoy

La norma no da una fecha fija: da una **regla** ("último día hábil de febrero/mayo/agosto/
noviembre"). Ese "último día hábil" depende del calendario de feriados de cada año y no está
transcrito aquí —transcribir la regla es lo que corresponde a este archivo, no resolverla en una
fecha AAAA-MM-DD por ejercicio—. Si `parametro_tributario` solo puede guardar una fecha resuelta y
no una regla, hace falta decidir dónde vive el cálculo del "último día hábil" de cada mes; eso
queda fuera de esta transcripción.
