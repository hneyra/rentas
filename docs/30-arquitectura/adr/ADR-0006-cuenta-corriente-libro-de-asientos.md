# ADR-0006 — La cuenta corriente es un libro de asientos inmutable

**Estado:** Aceptado
**Fecha:** 2026-08-17

## Contexto

El manual describe la deuda como algo que se consulta, se cobra, se fracciona, se da de alta con
una **nota de abono**, se da de baja con una **nota de cargo**, se congela en un valor, se pasa a
coactiva y se puede anular. También describe la anulación de un recibo, que devuelve a pendiente
una deuda ya cancelada.

Un modelo donde «la deuda» es una fila con un saldo que se va actualizando no sobrevive a eso:
cada operación pisa el estado anterior y, cuando alguien pregunta por qué un contribuyente debe
lo que debe, no hay respuesta.

## Decisión

**La cuenta corriente es un libro de asientos: solo se agrega.**

- Cada movimiento es un asiento **CARGO** o **ABONO**, con tributo, periodo, unidad, concepto
  (insoluto, reajuste, interés, gasto, pago, condonación, anulación, ajuste), importe, fecha
  valor, documento de origen y usuario.
- **Sin `UPDATE` y sin `DELETE`.** La aplicación no tiene esos privilegios sobre la tabla. Un
  asiento equivocado se corrige con un asiento de reversión que lo referencia.
- El **saldo** no se almacena como verdad: se calcula. `saldo_proyectado` existe como caché
  reconstruible a partir del libro, no como origen.
- La deuda es **función de la fecha**: `deudaActualizadaA(fecha)`. Toda cifra mostrada indica su
  fecha.

## Consecuencias

- La pregunta «¿por qué debe esto?» siempre tiene respuesta: la lista de asientos.
- La anulación de un recibo, el quiebre de un convenio y la baja de deuda se implementan igual
  —un asiento más— en vez de como tres correcciones distintas del mismo campo.
- Las notas de cargo y abono del manual dejan de ser un informe y pasan a ser el mecanismo.
- El libro crece rápido, lo que obliga al particionado por ejercicio de
  [ADR-0004](https://github.com/hneyra/infrastructure/blob/main/docs/30-arquitectura/adr/ADR-0004-almacenamiento-de-datos.md).
- Consultar deuda cuesta más que leer un campo. Se mitiga con el saldo proyectado, **que hay que
  poder reconstruir**: si diverge, manda el libro.

## Alternativas consideradas

- **Saldo mutable por deuda.** Más simple de consultar y de escribir; imposible de auditar. Es lo
  que obliga a que exista una tabla de histórico paralela, que siempre acaba desincronizada.
- **Libro de asientos con actualización del último asiento** («ajustar en vez de reversar»).
  Ahorra filas y pierde la propiedad que justifica todo esto: que nada de lo escrito cambie.
