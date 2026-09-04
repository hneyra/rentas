# ARQ-01 — Contextos acotados

## 1. Por qué doce y no doce módulos de menú

El menú del sistema tiene 12 módulos, pero el menú organiza **el trabajo del usuario**, no el
modelo. Dos ejemplos del propio manual:

- «Tránsito» e «Infracciones administrativas» son dos módulos del menú con el **mismo modelo**:
  acta, catálogo de infracciones, cálculo de multa, resolución, pase a coactiva. Son **un solo
  contexto** (`sanciones`) con dos catálogos y dos bases legales.
- «Consultas» es un módulo del menú entero, pero no tiene modelo propio: consulta la **cuenta
  corriente**, que sí lo es.

De ahí salen doce contextos. La correspondencia con el menú está en
[NEG-03](../10-negocio/catalogo-de-opciones.md).

## 2. Mapa

**La fuente de verdad del grafo son los `build.gradle.kts` de cada módulo**: la tabla de abajo
recoge solo las dependencias `implementation(project(":sgtm-…"))` de `src/main` —no las de
prueba ni los `testFixtures`— y se cotejó contra ellos el 2026-08-29. Ante cualquier duda,
manda el archivo, no esta tabla.

| Módulo | Depende de |
|---|---|
| `contribuyentes` | — (la base del grafo: no depende de ningún contexto) |
| `parametros` | — |
| `cuentacorriente` | — |
| `seguridad` | — |
| `catastro` | `contribuyentes`, `parametros` |
| `tesoreria` | `cuentacorriente`, `contribuyentes`, `parametros` |
| `valores` | `cuentacorriente`, `contribuyentes`, `parametros` |
| `sanciones` | `cuentacorriente`, `contribuyentes`, `parametros`, `tesoreria`, `valores` |
| `licencias` | `tesoreria`, `contribuyentes`, `catastro`, `cuentacorriente`, `parametros` |
| `rentas` | `parametros`, `catastro`, `cuentacorriente`, `contribuyentes`, `tesoreria`, `valores` |
| `fiscalizacion` | `catastro`, `rentas`, `parametros`, `cuentacorriente`, `contribuyentes` |
| `coactiva` | `valores`, `cuentacorriente`, `contribuyentes`, `parametros`, `tesoreria`, `rentas` |
| `indicadores` (no es contexto, §3.13) | `cuentacorriente`, `tesoreria` |

`sgtm-aplicacion` depende de todos —es quien ensambla— y es el **único** que declara
`sgtm-seguridad` (ver §3.12). Todo contexto ve `sgtm-dominio-compartido` por el plugin de
convenciones `sgtm.modulo`, y esa arista no se lista porque no distingue a nadie.

## 3. Los contextos

### 3.1 `contribuyentes`
El **código único** del contribuyente y sus datos: identificación, domicilios con vigencia,
documentos, contactos, gestores, observaciones. Todos los demás contextos lo referencian; él no
referencia a ninguno.
*Módulo Gradle:* `sgtm-contribuyentes`.

### 3.2 `catastro`
Predios y sus fichas —única, económica, bienes comunes, rural—, titularidad, construcciones por
piso, otras instalaciones, inquilinos, y los catálogos de vías, sectores y manzanas. Incluye las
**tablas de valuación** (aranceles, valores unitarios, depreciación) porque describen el predio,
no la obligación.
**Invariante:** una ficha nunca se sobrescribe; se versiona.
*Módulo Gradle:* `sgtm-catastro`.

### 3.3 `rentas`
La **determinación**: predial, arbitrios, patrimonio vehicular, alcabala, espectáculos. Vehículos,
declaraciones juradas, transferencias y beneficios. Es el único contexto que decide **cuánto se
debe**.
**Regla:** toda determinación guarda el conjunto de parámetros con que se calculó y las reglas
aplicadas. Sin eso no es reproducible.
*Módulo Gradle:* `sgtm-rentas`.

### 3.4 `parametros`
Los valores normativos versionados y su sellado por ejercicio. **Solo se lee** desde los demás
contextos. Escribir aquí es un acto administrativo con doble verificación, no una operación de
negocio.
*Módulo Gradle:* `sgtm-parametros`.

### 3.5 `fiscalizacion`
Programación, actas prediales y vehiculares, resultados, omisos y subvaluadores, liquidación y
reliquidación. Trabaja sobre **copias** de las fichas: hasta la transferencia, nada de lo que
registra es el dato oficial.
**Frontera delicada:** la transferencia a rentas. Es el único camino de escritura hacia
`catastro` y `rentas`, y va con sustento y versión.
*Módulo Gradle:* `sgtm-fiscalizacion`.

Desde [#52](https://github.com/hneyra/sgtm/issues/52) esa frontera existe y es **una regla, no una
intención**: `TransferirARentas` versiona la ficha por `catastro.TransferenciaDeFiscalizacion` —el
único puerto de escritura que `catastro` publica para este contexto, con **un** método—, asienta los
cargos de la diferencia por `cuentacorriente.GeneradorDeCargos` y emite la resolución de
determinación, los tres en una transacción. Que ninguna otra clase de `fiscalizacion` pueda hacerlo
lo verifica `SOLO_LA_TRANSFERENCIA_ESCRIBE_FUERA_DE_FISCALIZACION`, con dos clases de muestra que la
violan: una que usa el puerto sin ser la transferencia, y otra que cruza el límite con un tipo que
nadie clasificó —esta segunda es la que sostiene a la primera, porque sin ella bastaría con publicar
un puerto nuevo para rodearla—.

**Y desde [#545](https://github.com/hneyra/sgtm/issues/545) hay una consulta que cruza la frontera
por SQL, y sólo una.** `fisc_omisos` es el cruce del padrón de predios con las declaraciones
juradas de un ejercicio, y su columna «Condición» es un **derivado** de ese cruce que la pantalla
filtra. Un derivado filtrable se escribe una sola vez y en SQL —es lo que #397 decidió para el
«Estado» de la infracción administrativa—, y esa consulta no puede vivir en `catastro` (tendría
que leer `declaracion_jurada`, y `rentas` ya depende de él: es el ciclo que `ConsultaDeConciliacion`
descartó por escrito) ni en `rentas` (la condición es vocabulario de fiscalización, y traducirla
sería una segunda copia de la regla). Vive en `DeteccionRepositoryJdbc`, lee cuatro tablas ajenas
—`predio`, `sector`, `ficha_catastral` y `declaracion_jurada`—, **sólo lee**, y que su transcripción
no se separe de `ComparacionHalladoDeclarado` lo sostiene una prueba que las compara caso por caso.
Los titulares no entran ahí: salen del puerto público `catastro.TitularesDelPredio`.

**Y escribe en `catastro` y en el libro, no en `rentas`.** El nombre es el de RF-054 y el del manual.
Lo que `rentas` guarda de un ejercicio es la **declaración jurada**, que es el acto del contribuyente
y la administración no reescribe; lo que la sustituye es la determinación de oficio, cuya cifra
sigue esperando —`D-02a` se cerró el 2026-08-25, pero falta la tabla de valores unitarios de
[GOB-03](../00-gobierno/plan-de-desbloqueo-D-02.md) H-14 —la de depreciación se carga desde
`V57`— y el `% actualización` de `D-11` (#198)—. Que `rentas` no tenga hoy puerto de escritura no es un olvido: es lo que la regla
garantiza, y abrirlo costaría una línea visible en el diff.

### 3.6 `sanciones`
Papeletas de tránsito y administrativas, catálogos de infracciones (tránsito y CUIS),
notificaciones previas, descargos, internamiento y resoluciones de gerencia. Un solo modelo, dos
familias.
*Módulo Gradle:* `sgtm-sanciones`.

### 3.7 `cuentacorriente`
El **libro de asientos**: cargos y abonos por contribuyente, tributo, periodo y unidad. Altas
(nota de abono) y bajas (nota de cargo). Saldo proyectado como caché reconstruible.
**Invariante:** inmutable. Sin `UPDATE`, sin `DELETE`; se reversa con otro asiento.
**No existe «la deuda»**: existe `deudaActualizadaA(fecha)`.
*Módulo Gradle:* `sgtm-cuentacorriente`.

**El vocabulario de tributos del libro es API pública de este módulo** desde
[#553](https://github.com/hneyra/sgtm/issues/553): `cuentacorriente.TributoDelLibro`, en el
paquete raíz junto a `GeneradorDeCargos` y `ConsultaDeDeudaPublica`. No es una excepción a la
regla 2 de §4 —el libro sigue sin conocer a nadie—, es lo contrario: los siete contextos que
asientan declaraban cada uno su propio literal, y dos grafías del mismo tributo son **dos
obligaciones distintas** porque `ClaveDeSaldo` compara ese texto por igualdad exacta. Un
vocabulario que no se puede importar no es «un solo sitio»; en `.dominio` no serviría, porque
ningún módulo importa ese subpaquete y hacerlo pondría a Spring Modulith en rojo, que es lo que
el javadoc de `GeneradorDeCargos` explica de `Fase` y `Concepto`.

### 3.8 `tesoreria`
Caja tributaria y de tasas, recibos, anulación del día, convenios de fraccionamiento con su
preconvenio y su quiebre, cierre de caja y recaudación por área y partida presupuestal.
Asienta **abonos**; nunca determina.
*Módulo Gradle:* `sgtm-tesoreria`.

### 3.9 `valores`
Orden de pago, resolución de determinación y resolución de multa: la deuda formalizada en un
documento notificable, con su base legal, su numeración correlativa y su notificación.
*Módulo Gradle:* `sgtm-valores`.

### 3.10 `coactiva`
Expedientes, importación de valores, REC-1 y REC-2, actos coactivos, notificaciones, costas
procesales y fraccionamiento coactivo.
*Módulo Gradle:* `sgtm-coactiva`.

### 3.11 `licencias`
Licencias de funcionamiento (con giros CIIU, duplicados, cancelación), licencias de edificación
(FUE) y autorizaciones de anuncios. Genera deuda por la tasa correspondiente **pidiéndoselo a
`cuentacorriente`**; no asienta por su cuenta.
*Módulo Gradle:* `sgtm-licencias`.

Desde [#44](https://github.com/hneyra/sgtm/issues/44) tiene código: la licencia de funcionamiento
completa —emisión con sus giros, cancelación con su resolución, duplicado que conserva el número, y
el catálogo CIIU extensible—. Consume **cinco** APIs públicas y ninguna tabla ajena:
`tesoreria.RecibosDeTramite` (comprobar que el derecho de trámite está pagado, RF-110),
`contribuyentes.DirectorioDeContribuyentes`, `catastro.LectorDeFichasEconomicas` (la ficha económica
del predio donde está el establecimiento, #19), `parametros.LectorDeParametros` y
`cuentacorriente.GeneradorDeCargos`.

**La dependencia de `cuentacorriente` llegó con [#51](https://github.com/hneyra/sgtm/issues/51),
cuando hubo un cargo real que asentar, y no antes.** Emitir una licencia de funcionamiento no
genera deuda: el derecho de trámite se paga *antes*, en caja de tasas, y un derecho de trámite no es
deuda tributaria —no se determina, no devenga interés y no prescribe—. Lo que sí genera deuda es la
**autorización de anuncios**: la tasa anual del anuncio se asienta al autorizar y al renovar, y se
le **pide** al libro por `cuentacorriente.GeneradorDeCargos`, como todo cargo de otro contexto
(§4, regla 2); la deuda de una tasa sin ordenanza cargada no se inventa —el borde responde 422
nombrando la llave `TASA_ANUNCIO:<CLASE>` (D-02b)—. Declarar la dependencia «por si acaso» habría
sido abrir el límite sin usarlo; se declaró el día que se usó.

### 3.12 `seguridad`
Módulos, accesos y políticas, grupos, usuarios, miembros, permisos, sesiones y **auditoría**.
Él no depende de ningún contexto, y **ningún contexto depende de él**: solo `sgtm-aplicacion` lo
declara, para ensamblarlo. La transversalidad del control de acceso no la da este módulo sino
`sgtm-plataforma` —el guardia de `pe.gob.sgtm.autorizacion` (`@RequiereAcceso`), que todo
controlador usa sin conocer a `seguridad`—; este contexto es quien administra los datos que ese
guardia consulta.
*Módulo Gradle:* `sgtm-seguridad`.

### 3.13 No es un contexto: `indicadores`

El **panel de recaudación** ([#56](https://github.com/hneyra/sgtm/issues/56), RF-130), la pantalla
de inicio. No es el contexto número trece y no lo será: no tiene modelo, no tiene tablas, no
determina y no asienta. Lo único que hace es **agregar** lo que otros ya publican —
`cuentacorriente.RecaudacionDelLibro`, `cuentacorriente.CarteraDelLibro` y
`tesoreria.AvanceDeCaja`— y redactarlo para una pantalla.

Vive en su propio módulo Gradle, `sgtm-indicadores`, junto a `sgtm-esquema` y `sgtm-plataforma` en
la lista de los que **no** son contextos acotados. El motivo de que sea un módulo y no un paquete
de `sgtm-aplicacion` es la frontera: su `build.gradle.kts` declara que solo ve esos dos contextos,
así que añadir un tercero al panel cuesta una línea y se ve en el diff. Spring Modulith vigila que
no cruce el límite por dentro; una regla de ArchUnit —`EL_PANEL_NO_HABLA_CON_LA_BASE`, con su
muestra— vigila que no se lo salte por debajo escribiendo su propio SQL.

**Invariante:** ninguna cifra del panel se calcula en el panel. Si sumara por su cuenta lo que la
caja ya suma, la pantalla de inicio y la de recaudación podrían decir cifras distintas del mismo
día, y no habría forma de saber cuál está mal.

## 4. Reglas de dependencia

1. **Un contexto se importa solo por su API pública.** En Java, el paquete raíz del contexto;
   nunca `…​.dominio.…` ni `…​.infraestructura.…` de otro. Lo verifica Spring Modulith.
2. **`cuentacorriente` no conoce a nadie.** Recibe asientos; no sabe si vienen de un predial, de
   una papeleta o de una licencia. Si tuviera que saberlo, el modelo estaría mal.

   **El matiz que #635 obligó a escribir: puede *preguntar*, sin conocer a nadie.** La regla
   prohíbe que este contexto **importe un tipo** de otro, y eso sigue siendo cierto al pie de la
   letra. Lo que #635 necesitaba —que el `predioId` o el `vehiculoId` de un alta de deuda sea del
   contribuyente del movimiento— no se podía resolver ni copiando el predicado de vigencia de la
   titularidad en SQL de `cuentacorriente` (sería una segunda definición de una regla de
   `catastro`, y la que decidiera quién es el titular acabaría siendo la que nadie recuerda que
   existe) ni moviendo el acto a `rentas` (el libro es quien asienta). Se resuelve con un **puerto
   de salida** escrito en el vocabulario del libro —`TitularesDeLaUnidad`: dos `long` y una fecha,
   sin un solo tipo ajeno— que **implementa `rentas`**, el único contexto que ve predio, vehículo
   y padrón de personas a la vez. Es la misma forma que `pe.gob.sgtm.autorizacion.ComprobadorDeAcceso`
   —declarado en `plataforma`, implementado en `seguridad`—, y **no crea ninguna arista nueva** en
   el mapa de §2: `rentas ──► cuentacorriente` ya existía.

   El precedente que **no** vale aquí, y conviene tenerlo dicho:
   `AsientoRepository.contribuyentePorCodigo` resuelve una tabla ajena en SQL y se justifica en su
   javadoc «contra una tabla con la que ya hay clave foránea». Con `titularidad` y `vehiculo` **no
   la hay** —`cuenta_corriente_asiento` y `saldo_proyectado` sólo referencian `contribuyente`—, así
   que copiar ese patrón sería apoyarse en una justificación que no aplica.

   **Y lo que el puerto contesta son dos cosas, no una lista** ([#680](https://github.com/hneyra/sgtm/issues/680)).
   Hasta ahí devolvía los titulares y su vacío significaba a la vez «ese identificador no apunta a
   nada» y «la unidad existe y a esa fecha no la reclama nadie», que se arreglan de forma distinta
   y acababan en el mismo 422 con el mismo texto — con lo cual **no se podía dar de alta deuda
   sobre un predio sin titularidad vigente**, que es el 34,5 % del padrón de Catacaos (#586) y
   justo el predio que la fiscalización detecta y visita. La distinción vive en el tipo que el
   puerto devuelve, `TitularidadDeLaUnidad`, y su compacto rechaza la respuesta incoherente —una
   unidad fuera del padrón con titulares dentro—, que es lo que impide volver a confundirlas.
3. **`parametros` es de solo lectura** para todos los demás.
4. **Nadie escribe en `catastro` salvo `catastro` y las dos transferencias.** La de `rentas`
   va por el puerto público `GestorDeTitularidad` del paquete raíz de catastro
   ([#29](https://github.com/hneyra/sgtm/issues/29)): `RegistrarTransferencia` vive en
   `sgtm-rentas/…/aplicacion/` e inyecta ese puerto —cuyo javadoc nombra la arista
   `catastro ──► rentas`— para cerrar una titularidad y abrir otra. Y la de `fiscalizacion`
   ([#52](https://github.com/hneyra/sgtm/issues/52), §3.5) va por `TransferenciaDeFiscalizacion`:
   es la única escritura del contexto hacia fuera, y está garantizada **mecánicamente** por una
   regla de ArchUnit con sus dos muestras. Fuera de ese puerto, `fiscalizacion` solo lee
   (`LectorDeFichas`), y por eso `fisc_predial` avisa de que trabaja sobre una copia y el padrón
   no cambia hasta que alguien transfiere.
5. **Ningún método público de un contexto recibe `municipalidadId`.** Sale del token.
   Lo verifica ArchUnit.
6. Lo compartido entre contextos —`MunicipalidadId`, `Ejercicio`, `Dinero`, `TenantContext`— vive
   en `sgtm-dominio-compartido`, y ese módulo **no depende de ninguno**.

## 5. Estado actual

Los doce módulos Gradle existen, y la estructura se escribió antes que el código para fijar los
límites antes de que hubiera algo que los cruzara. Hoy **los doce tienen código de negocio**:
`tesoreria` lo recibió con la caja (#33), `coactiva` con el expediente (#40) y `licencias` con la
licencia de funcionamiento (#44), que eran los tres que quedaban. El estado fino de cada uno es
su propio `src/main`, no esta lista.

El de más recorrido es `catastro`: desde [#290](https://github.com/hneyra/sgtm/issues/290)
publica también la **escritura** —vías y sectores, el alta de manzanas, el alta de las cuatro
fichas y su actualización versionada—, con observación obligatoria y auditoría. Lo que sigue sin
implementarse es el **cálculo**: ninguna regla tributaria se escribe contra cifras inventadas
mientras D-11 no cierre ([GOB-02](../00-gobierno/decisiones-abiertas.md); el redondeo ya se
decidió, ADR-0018).
