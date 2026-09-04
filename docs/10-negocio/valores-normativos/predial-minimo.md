# Impuesto mínimo del predial

| Campo | Valor |
|---|---|
| Norma | TUO de la Ley de Tributación Municipal (D.S. 156-2004-EF) |
| Artículo | 13, último párrafo |
| Publicada | 2004-11-15, El Peruano |
| Ejercicios que rige | 2004– |
| Filas de NEG-02 §2 | 5 |
| Transcribió | JNA, 2026-08-24 |
| Verificó | HNA, 2026-08-25 |
| Estado | VERIFICADO |

## 1. La tabla tal como está en la norma

**Sin reordenar, sin convertir unidades, sin corregir un encabezado.**

| Concepto | Valor |
|---|---|
| Monto mínimo del impuesto | 0.6% de la UIT vigente al 1 de enero del año al que corresponde el impuesto |

> Texto literal: «Las Municipalidades están facultadas para establecer un monto mínimo a pagar por
> concepto del impuesto equivalente a 0.6% de la UIT vigente al 1 de enero del año al que
> corresponde el impuesto.» Es una facultad municipal ("están facultadas"), no un piso obligatorio
> que la ley imponga por sí sola — la ordenanza de cada municipalidad es la que efectivamente lo
> fija; este archivo transcribe el tope/base nacional que la ley habilita.

## 2. Cómo entra al sistema

| Qué | Dónde |
|---|---|
| Tipo | `parametro_tributario` (tipo `PREDIAL_MINIMO`) |
| Clave | `PREDIAL_MINIMO`, con el ejercicio como parte de la clave compuesta (el monto en soles depende de la UIT de cada año) |
| Ámbito | nacional |
| Vigencia | 2004–, sin modificación conocida a la fecha de esta transcripción |

**Se carga desde `publicacion/parametros-2026.csv`**, el derivado publicable de este
archivo, con `infra/carga-de-datos/publicar-parametros.sh` (#188, #247 §4). Las dos firmas
de la cabecera de arriba son las que llegan a `usuario_carga` y `usuario_aprueba`: la doble
verificación de ADR-0007 ocurrió aquí, y la herramienta la transporta.

## 3. Qué no cabe hoy

Nada.
