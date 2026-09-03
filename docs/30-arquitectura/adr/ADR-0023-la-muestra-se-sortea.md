# ADR-0023 — La muestra de fiscalización se sortea; la detección aporta sus filtros

| Campo | Valor |
|---|---|
| Estado | Aceptado |
| Fecha | 2026-09-02 |
| Decide | Dirección del proyecto |
| Confirma | `V60` §1 y §2, que ya lo escribieron en el esquema sin que nadie lo hubiera decidido por escrito |
| Implementa | issue [#550](https://github.com/hneyra/sgtm/issues/550) |

## Contexto

«Omisos y subvaluadores» (`fisc_omisos`, RF-055) dibuja una casilla por fila y dos botones:
«Programar fiscalización (N)» y «Notificar esquela». Ninguno de los dos tiene hoy una operación del
backend que lo reciba, y #550 midió que el primero **no la puede tener sin decidir antes qué es una
muestra**.

Lo que existe:

| Operación | Qué recibe | Por qué no sirve para una selección |
|---|---|---|
| `POST /fiscalizacion/programas` | `codigo`, `descripcion`, `tipo`, `fechaInicio` obligatorios, más `ejercicio`, `sector`, `criterio`, `fiscalizador` y la observación | No recibe predios: recibe **los parámetros con los que el programa sorteará su muestra** |
| `POST /fiscalizacion/programas/{id}/muestra` | sólo la observación | El cuerpo no admite predios por decisión explícita, escrita en el javadoc de `PeticionDeMuestra` |

El issue planteó dos salidas y dijo, con razón, que **la decisión no es de la interfaz**:

- **(a)** La muestra se sortea y punto. Entonces lo que la detección aporta al programa son sus
  **filtros** —sector y condición—, que ya son dos de los cuatro parámetros del sorteo.
- **(b)** La muestra admite una selección manual. Entonces hace falta una operación nueva que reciba
  la lista de predios con su observación, una clave de idempotencia, y una decisión escrita sobre
  qué pasa con la exclusión que `GenerarMuestra` garantiza hoy.

## Decisión

### 1. Gana la salida (a): la muestra se sortea

Un programa de fiscalización declara **a quién busca** —ejercicio, sector, condición— y la muestra
es el resultado de aplicar esa declaración al padrón en un día concreto. No hay ninguna ruta por la
que entre una lista de predios elegidos a mano.

Los motivos, de más a menos estructural:

1. **«¿Por qué me tocó a mí?» tiene que tener respuesta, y la respuesta es el criterio.** La fila de
   `programa_muestra` (`V60`) **copia** la condición del día del sorteo, sus dos áreas y la fecha:
   contesta esa pregunta sola. Una selección a mano la contesta «porque alguien te marcó», que en un
   procedimiento de fiscalización de oficio no es una respuesta — es exactamente la discrecionalidad
   que un programa por criterio existe para evitar.
2. **La exclusión entre programas quedaría dicha de dos maneras.** Que un predio que otro programa
   abierto ya se llevó no vuelva a sortearse lo sostiene hoy **sólo** `GenerarMuestra.repartir`:
   `programa_muestra_uq` impide el duplicado *dentro* del mismo programa y nada más. Una segunda
   puerta a la misma tabla obligaría a reimplementar esa exclusión ahí, y dos copias de la misma
   regla divergen — el defecto que #397 se negó a introducir con el «Estado» de la infracción
   administrativa y que #545 volvió a medir con la condición.
3. **El reparto ya está en los requisitos.** RF-055 es *identificar* omisos y subvaluadores y su
   opción del catálogo publica un `GET`; RF-050 es *programar por criterio y periodo* y su opción es
   `fisc_programa`. El prototipo del manual declara para `fisc_omisos` el endpoint
   `GET /api/v1/fiscalizacion/omisos` y nada más.
4. **(b) obligaría a reescribir lo que ya está decidido en el esquema.** `V60` §1 explica por qué la
   muestra se guarda y §2 por qué su *tamaño* no se declara —«un tope exigiría un orden por riesgo,
   y `CondicionFiscalizada` es una etiqueta, no una escala; inventar ese orden es inventar a quién
   se fiscaliza»—. Una selección manual es ese tope, puesto a mano. Y la fila copia `fecha_sorteo`
   como la foto de un sorteo que en ese camino no existiría.

**Lo que (a) cuesta, dicho:** el funcionario que marca dos filas en la detección no puede
llevárselas. Lo que se lleva son sus filtros. Eso sólo es aceptable si los mismos filtros producen
los mismos predios, y por eso esta decisión **no se quedó en el papel**: `ProgramarDesdeLaDeteccionFronteraTest`
lo recorre de HTTP a PostgreSQL —pide la detección con los tres filtros, registra el programa con
esos mismos tres, sortea y compara los códigos de referencia catastral de las dos respuestas—.

### 2. Y para que (a) sea cierta, los dos filtros se leen en un solo sitio

Medirlo destapó que **no llegaban**. Los dos desplegables de la pantalla escriben «Todos» y «Todas»
para decir «sin filtro», `OmisosController` los leía así desde siempre, y `ProgramasController` no:

- **`sector`**: se guardaba **literal** en `programa_fiscalizacion.sector_codigo`, y el sorteo filtra
  `s.codigo = 'Todos'`. Un programa que no puede encontrar nunca ningún predio, y cuyo único
  síntoma es una muestra de cero — indistinguible de «en ese sector no hay omisos».
- **`criterio`**: se leía con `CondicionFiscalizada.valueOf`, sin la normalización de
  `porNombre`, así que «USO DISTINTO» se aceptaba en una pantalla y se rechazaba en la otra; y
  «Todas» contestaba «Criterio de riesgo desconocido», que no es lo que es.

La lectura pasa a vivir en **`FiltroDeLaDeteccion`**, una sola vez para las dos pantallas. Y las dos
lecturas de la condición **no son la misma, a propósito**: «Todas» en la detección es «sin filtro» y
trae el padrón entero; en el programa **no existe**, porque un programa sin criterio no puede
sortear (`ProgramaSinParametros`) y admitirlo aplazaría el fallo hasta el sorteo. El 422 dice qué es
—la ausencia de criterio— en vez de decir que la palabra no se conoce.

### 3. La esquela no existe, y no se inventa aquí

«Notificar esquela» no tiene operación **y no se le construye una en este issue**. No es una
operación pendiente: es un rótulo del prototipo sin acto detrás.

- **Ningún requisito la pide.** RF-050…RF-057 no la mencionan, el catálogo de opciones no la nombra
  y el contrato no declara ninguna ruta con esa palabra. En `src/main` del backend aparece **dos veces**, y
  las dos en un javadoc que la cita como consecuencia de un defecto que se evitó: «una cifra
  supuesta produciría una esquela de cobranza sobre un número inventado».
- **El sistema no modela el acto.** Una esquela es una notificación inductiva: tiene destinatario,
  domicilio, contenido y plazo. No hay tipo de documento para ella, no hay plazo suyo transcrito en
  el corpus normativo —y este proyecto no inventa plazos (regla 5, #192)—, y desde #545 la detección
  enseña predios **sin titular vigente** —el 34,5 % del padrón de Catacaos—, que son justamente los
  que más hay que fiscalizar y a los que no hay a quién notificar.
- **Sus cifras están bloqueadas.** Las cuatro columnas de importe de la detección —valor catastral,
  valor declarado, diferencia e impuesto omitido— viajan en `null` hasta D-02a. Una esquela de
  omisos sobre un subvaluador tendría que decir cuánto, y hoy no se puede decir sin inventarlo.

Mientras eso siga así, la acción **se retira de la pantalla o se apaga diciendo por qué**; lo que no
puede quedarse es un botón cuyo motivo sólo se lee pasando el ratón (RNF-082).

### 4. La decisión se sujeta con una prueba, no con este documento

`LaMuestraSeSorteaTest` comprueba tres cosas que hoy son ciertas y que (b) volvería falsas: que
`PeticionDeMuestra` tiene **exactamente un componente** y se llama `observacion`; que la única
operación del contrato bajo `/fiscalizacion/omisos` es el `GET`; y que ninguna ruta contiene
«esquela». Es el patrón que #430 dejó escrito para el catálogo del TUPA: una prueba que se pone roja
el día que alguien construye lo que se decidió no construir es lo único que garantiza que ese día
alguien vuelva a leer este ADR.

## Consecuencias

- **La interfaz de `fisc_omisos` no gana ninguna escritura.** La selección se queda en la pantalla
  —marcar sigue siendo útil para revisar— y lo que sale de ella son los filtros.
- **`fisc_programa` es la pantalla que programa**, y `POST /fiscalizacion/programas` ya la sirve: no
  hace falta backend nuevo para el camino (a), sólo que la pantalla pida los cuatro campos
  obligatorios con su observación (regla 10).
- **El sorteo sigue dependiendo del orden**, y hay que decirlo donde se opera: el primer programa que
  se genere se lleva los predios y el segundo sale más corto. La respuesta del sorteo lo publica
  desde #586 (`detectados`, `excluidosPorOtroPrograma`, `excluidosPorActaDelEjercicio`).
- **Si algún día gana (b)**, hay que reescribir este ADR, el javadoc de `PeticionDeMuestra` y §1/§2
  de `V60`, añadir una migración con la clave de idempotencia y una marca de procedencia —la fila
  copia condición, áreas y `fecha_sorteo` como la foto de un sorteo que una selección a mano no
  tiene—, y decidir qué pasa con la exclusión del punto 2. Las tres pruebas de
  `LaMuestraSeSorteaTest` son la lista de lo que hay que tocar.
