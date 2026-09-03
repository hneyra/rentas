# ADR-0026 — El camino del dinero: dos transacciones, un outbox, y la imputación en rentas

| Campo | Valor |
|---|---|
| Estado | Propuesto |
| Fecha | 2026-09-03 |
| Decide | Dirección del proyecto |
| Depende de | [ADR-0029](https://github.com/hneyra/infrastructure/blob/main/docs/30-arquitectura/adr/ADR-0029-cuatro-sistemas-separados.md) |
| Conserva | [ADR-0006](ADR-0006-cuenta-corriente-libro-de-asientos.md): el libro sigue siendo inmutable |
| Abre | D-20 (que dice el recibo) |

## Contexto

Hoy un cobro es una transacción local: se emite el recibo, se asientan los abonos, se actualiza el
saldo proyectado y se cierra el convenio si toca, todo en un `COMMIT`. Si algo falla, no paso nada.

ADR-0003 nombró esto como su mejor argumentó —«transacciones distribuidas en el camino del dinero»— y
tenía razón. Partir la caja convierte ese `COMMIT` en dos, en dos bases distintas. **No hay forma de
que sigan siendo atómicas**, y fingir que si —con una transacción distribuida o un 2PC— es exactamente
el problema que un sistema tributario no debería tener.

La razón para hacerlo igual es de negocio y está escrita en ADR-0029: la municipalidad tiene que
cobrar lo que no es tributo, y eso no cabe en un contexto que se llama «caja tributaria y de tasas» y
que cuelga del padrón de contribuyentes.

## Decisión

### 1. Caja no sabe qué es un tributo

Recibe **órdenes de cobro** —`sistemaOrigen`, `referenciaExterna`, concepto, importe, fecha de
exigibilidad— y devuelve **pagos** con su recibo. Nada más. Es lo que la hace reutilizable para
mercados, cementerio o estacionamiento sin arrastrar el Código Tributario.

El alta es idempotente por `(sistemaOrigen, referenciaExterna)`: reintentar no duplica la orden.

### 2. La imputación es de rentas

Caja cobra un importe contra una orden. **El orden de imputación del Código Tributario** —interés
antes que insoluto, deuda más antigua primero, y lo que el contribuyente pueda elegir— lo aplica
`rentas` al recibir el `PagoRegistrado`.

Si Caja imputara, la regla tributaria estaria escrita dos veces, y la que decide de verdad acabaría
siendo la que nadie recuerda que existe. Es el mismo argumentó por el que
[ARQ-01 §4 regla 2](https://github.com/hneyra/sgtm/blob/migracion-a-microservicios/docs/30-arquitectura/contextos-acotados.md) mantiene que `cuentacorriente` no conoce a nadie.

**Consecuencia visible en ventanilla, y hay que decidirla**: hoy el recibo y el detalle de imputación
salen juntos. Con la imputación asíncrona, el recibo dice cuanto se pago y contra que orden, y el
detalle aparece en la consulta de cuenta corriente. Si eso no es aceptable para el contribuyente hay
salidas —imputación previsualizada por `rentas` al emitir la orden— pero cuestan una llamada
síncrona en ventanilla. Queda como **D-20**.

### 3. Dos transacciones y una garantía

```
ventanilla ──► CAJA: recibo + arqueo   [COMMIT 1]
                 └─ outbox ──► inbox ──► RENTAS: imputa + asienta   [COMMIT 2]
                                            └─ conciliación diaria = 0
```

Lo qué se compra: **la ventanilla cobra aunque `rentas` este caido**, que es exactamente lo que hace
falta el último día de vencimiento.

Lo que se paga: la conciliación diaria deja de ser buena práctica y pasa a ser obligación operativa.
Si el cierre de turno de Caja no coincide con los abonos aplicados en `rentas`, **el día no cierra**.

### 4. Lo que hay que construir antes de encenderlo

| Pieza | Por qué |
|---|---|
| Estado **pago en transito** visible, con su hora | Entre los dos `COMMIT` el saldo está desactualizado. Tiene que verse así, no como si no hubiera pagado |
| Cola de mensajes muertos con alerta a una persona con nombre | Un pago que no se pudo imputar es dinero cobrado sin registrar. No se queda en un log |
| Cierre de turno bloqueante | El turno no cierra hasta que todos sus pagos están aplicados o explicados uno por uno |
| `PagoAnulado` con asiento de reversión | La anulación del día no borra: reversa. El libro es inmutable (ADR-0006) y `recibo` está en `TABLAS_PROTEGIDAS` |
| Treinta días en paralelo | El camino viejo vive detras de una bandera hasta que la conciliación de cero treinta días seguidos |

### 5. El convenio de fraccionamiento se queda en rentas

Aunque hoy viva en `sgtm-tesoreria` y aunque se firme en la ventanilla de caja. Un convenio es
**deuda reprogramada**: tiene interés, tiene quiebre y tiene consecuencias coactivas. Si viaja a Caja,
Caja adquiere reglas tributarias y deja de ser reutilizable para cobrar un puesto de mercado.

La ventanilla no cambia: Caja cobra la cuota del convenio como cualquier otra orden.

## Consecuencias

- **`tesoreria` se parte con nombre**: recibo, movimiento, turno, cierre y medios de pago a `caja`;
  convenio y fraccionamiento coactivo se quedan.
- **El `REVOKE` que no se puede hacer sobre `cierre_caja`** (DAT-01 §6) se replantea en el sistema
  nuevo, donde el cierre ya no comparte base con el libro.
- **Aparece una ventana de inconsistencia visible al ciudadano**, medida en segundos y acotada por la
  alerta. Es nueva y no existía; es el costo directo de esta decisión.
- **La conciliación se vuelve una operación de negocio**, con su pantalla, su responsable y su hora.
  No es un *job* silencioso.

## Lo descartado, y por qué

- **Transacción distribuida (2PC) entre caja y rentas.** Es el problema que ADR-0003 nombró. Anade un
  coordinador que puede caerse dejando transacciones en duda, en el sitio donde una transacción en
  duda es dinero en duda.
- **Qué Caja escriba directamente en la cuenta corriente de rentas.** Es una base compartida
  disfrazada, y rompe el invariante de que el libro sólo recibe asientos por `GeneradorDeCargos` y
  `RegistroDeAbonos`.
- **Qué Caja imputa y `rentas` sólo registra.** Duplica la regla del Código Tributario y hace que un
  cambio normativo haya que desplegarlo en dos sitios.
- **Confirmar el recibo sólo después de que `rentas` aplique** (reserva → cobro → confirmación). Es
  más consistente y hace que la ventanilla dependa de `rentas` para entregar un papel, que es
  justamente lo que esta separación venía a evitar. Se descarta a favor de la conciliación diaria.
