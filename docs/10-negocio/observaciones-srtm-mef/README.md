# Campaña de observación del SRTM del MEF — el prerrequisito de una migración

> **D-03c se cerró el 2026-08-28 sin esta campaña**
> ([ADR-0018](../../30-arquitectura/adr/ADR-0018-el-redondeo-decidido.md)): el piloto arranca con
> padrón nuevo y no tiene determinaciones del SRTM que reproducir, así que sus puntos de redondeo
> los fija el propio ADR. **Esta carpeta no se borra: revive con la primera municipalidad que
> migre saldos del SRTM** (fila D-04 de GOB-02) — conciliar lo migrado exige reproducir sus
> cifras céntimo a céntimo, y para eso todo lo de abajo vuelve a ser el procedimiento.

**Qué se responde aquí:** en qué puntos del cálculo redondea el SRTM del MEF. No es una decisión
—nadie puede decidir cómo redondea un sistema ajeno—: es **ingeniería inversa contra
determinaciones reales**, una ficha por cada una.

El tipo que guarda la respuesta ya existe: `PuntoDeRedondeo` y `PoliticasDeRedondeo`, en
`sgtm-dominio-compartido`. Tiene **catorce puntos candidatos**, uno por cada paso que las secuencias
de `../srtm` NEG-05 revelan, y **ninguno tiene política todavía**. Cada ficha de esta carpeta
convierte un candidato en un hecho observado.

## Por qué hay una campaña y no una reunión

`CAL-02` —el contraste del cálculo contra el SRTM sobre predios reales— es la validación de las
reglas. Si se usara **el mismo** contraste para descubrir los puntos de redondeo, toda diferencia de
céntimos sería indistinguible de un error de regla y el contraste no concluiría nada.
[GOB-02](../../00-gobierno/decisiones-abiertas.md) §«Cómo se cierra D-03c» lo parte en dos pasos:

1. **Observar** determinaciones con su **desarrollo intermedio visible** —el manual M02 muestra que
   la pantalla de determinación lo trae— y fijar los puntos contra ese desarrollo. Eso es esta
   carpeta.
2. **Validar** con `CAL-02`, **sobre un juego de predios distinto**.

## Cómo se llena una ficha

Se copia [`_plantilla.md`](_plantilla.md) a `‹ejercicio›-‹referencia del predio›.md` y se completa
con lo que **la pantalla muestra**, no con lo que se deduzca de ella. Reglas:

- **Se transcribe el desarrollo intermedio tal como aparece**, con todos sus decimales. Un valor
  «redondeado a dos» que en la pantalla trae cuatro es exactamente el dato que la ficha busca.
- **Un punto se declara observado cuando dos fichas independientes coinciden.** Una sola puede estar
  explicada por el azar de las cifras de ese predio: si el metrado era entero, no dice nada.
- **Lo que no se ve, no se rellena.** Una casilla en blanco es información; una casilla inventada
  contamina el paso 2.
- **Ningún dato personal del contribuyente.** El predio se identifica por su referencia catastral y
  su ejercicio; el nombre no aporta nada al redondeo.

## Del hecho observado al sistema

El resultado **no entra como código**: entra como parámetro `REDONDEO:‹punto›` con su escala y su
modo, igual que cualquier otro dato normativo (ADR-0007), y `PoliticasDeRedondeo` lo lee. Una
política escrita a mano en el Java ya la detecta el escáner de fuentes.

Mientras un punto no esté parametrizado, el cálculo que lo pida **falla**. Es deliberado: no
redondear también produce un importe, y ese importe es plausible y equivocado.

## Estado

| Punto | Fichas | Estado |
|---|---|---|
| `VALOR_UNITARIO_INCREMENTADO` | — | Sin observar |
| `VALOR_UNITARIO_DEPRECIADO` | — | Sin observar |
| `VALOR_POR_NIVEL` | — | Sin observar |
| `METRADO_DE_OBRA` | — | Sin observar. **M02 ya lo revela como punto de redondeo**; falta escala y modo |
| `VALOR_DE_OBRA` | — | Sin observar |
| `AUTOVALUO_DEL_PREDIO` | — | Sin observar |
| `AUTOVALUO_ACTUALIZADO` | — | Sin observar |
| `BASE_IMPONIBLE_DEL_PREDIO` | — | Sin observar |
| `BASE_DEL_CONTRIBUYENTE` | — | Sin observar |
| `IMPUESTO_POR_TRAMO` | — | Sin observar |
| `IMPUESTO_ANUAL` | — | Sin observar |
| `CUOTA` | — | Sin observar |
| `REAJUSTE` | — | Sin observar |
| `INTERES` | — | Sin observar |

Esta tabla es el progreso de la campaña. Bajar el número de «sin observar» es lo único que la
completa — y solo hace falta completarla para migrar (ADR-0018).

## Dónde acaba una ficha cerrada

**En una fila, no en el código.** Cada punto observado entra en `parametro_tributario` con
`tipo = 'REDONDEO'` y `clave` igual al nombre del punto, y lleva las dos mitades a la vez:

| Columna | Qué lleva |
|---|---|
| `valor_numerico` | La escala: número de decimales |
| `valor_texto` | El modo: un `RoundingMode` (`HALF_UP`, `DOWN`, `HALF_EVEN`…) |
| `documento_fuente` | La ficha de observación que lo revela |

Las dos en la misma fila **a propósito**: con una fila por mitad, un conjunto sellado podría tener
la escala de un punto sin su modo —cada fila válida por separado— y ese punto quedaría *medio
configurado*, que es peor que ausente porque aparenta estar resuelto.
`PoliticasDeRedondeoSelladas` lo rechaza nombrando la mitad que falta.

Quien calcula **lee**: `RegistrarDeterminacionPredial` ya no recibe las políticas, las resuelve del
conjunto sellado del ejercicio. Escribir una a mano no compila el build: el escáner de fuentes la
detecta (regla 5, D-03).

Issue: [#203](https://github.com/hneyra/sgtm/issues/203). Depende de **acceso al SRTM del MEF** y
de que exista una municipalidad que migre (D-04); no de D-01 ni de D-02.
