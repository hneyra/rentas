# ADR-0015 — La conciliación catastro↔rentas: un derivado que publica rentas, no un estado que guarda catastro

**Estado:** Aceptado · 2026-08-28
**Decide:** el contrato de la conciliación que #322 exige diseñar antes de cablear pantalla alguna.

## Contexto

El prototipo dibuja la conciliación como si hubiera dos padrones: la consulta de fichas trae las
columnas «Cod. Predial Rentas» y «Conciliada», un filtro `conciliadaConRentas`, la acción masiva
«Conciliar seleccionadas», y la ficha urbana un campo capturable «Código Predial de Rentas». Su
nota lo justifica: *las fichas no conciliadas no generan deuda predial*.

El esquema dice otra cosa, y lo dice desde V1:

- **Hay un solo padrón de predios.** `predio` vive en la sección de catastro
  (`V1__nucleo_y_catastro.sql`) y **todo** rentas cuelga de él: `declaracion_jurada.predio_id`,
  `determinacion.predio_id`, `cuenta_corriente_asiento.predio_id`. No existe un «predio de
  rentas» aparte al que asignarle nada.
- **La ficha nace con su predio.** `ficha_catastral.predio_id` es `NOT NULL`, e `InscribirFicha`
  crea predio y ficha en el mismo acto (#290). Una ficha sin predio es irrepresentable.
- **El «código predial de rentas» ya es el código de referencia catastral.** `sgtm-rentas` los
  trata como sinónimos por escrito: `CriterioDeArbitrio` documenta `codigoPredial` como «el
  código de referencia catastral del predio» y su repositorio lo traduce a
  `p.codigo_ref_catastral`.
- **«Conciliada» no existe como columna ni concepto en el DDL.** No hay estado, ni fecha, ni
  quien concilió.

Lo que sí existe es el enlace en la otra dirección, y son **dos** columnas distintas:
`declaracion_jurada.predio_id` (V2) —de qué predio es la declaración— y
`declaracion_jurada.ficha_catastral_id` (V19) —qué **versión** de ficha se declaró, para que
reimprimir una DJ de 2024 en 2030 no lea la ficha actual (RNF-075)—. La segunda es detalle de la
primera, y confundirlas es el defecto que este ADR corrige más abajo.

Y el propio código ya definió qué significa el filtro: `ConsultaController` rechaza
`conciliadaConRentas` con 422, con la justificación que `FiltroDeFichas` escribe en su javadoc —
«compara el catastro con la declaración jurada del padrón de rentas. Ese contexto todavía no
existe, y responderlo leyendo su tabla desde aquí sería el acoplamiento que ARQ-01 §4 evita, con
el agravante de ser invisible para Spring Modulith».

La restricción decisiva es estructural: `sgtm-rentas` depende de `sgtm-catastro`; catastro **no**
depende de rentas. Cualquier operación alojada en catastro que necesite mirar rentas cierra un
ciclo que `verificarArquitectura` rechaza.

## Decisión

1. **«Conciliada» es un derivado, no un estado, y lleva su ejercicio.** No se agrega ninguna
   columna — ni a `ficha_catastral`, cuyo invariante es versionarse y no sobrescribirse
   (ARQ-01 §3.2), ni a `predio`. No había columna que modelar porque no hay estado que guardar.

   El predicado, escrito entero:

   > un predio está **conciliado a un ejercicio** cuando existe una `declaracion_jurada` de ese
   > ejercicio, con `predio_id` igual al del predio, en estado `PRESENTADA` u `OBSERVADA`.

   **La pertenencia al padrón afecto se deriva de `declaracion_jurada.predio_id`, no de
   `ficha_catastral_id`.** Este ADR decía lo segundo, y producía falsos omisos: la columna de V19
   es *nullable* y su propio javadoc lo dice —«nulo si el predio no tiene ficha registrada
   todavía, o si el tipo no es predial», `DeclaracionJurada:30-32`—, su clave foránea va
   `NOT VALID` (DAT-01 §0, cuarto hallazgo) y **toda fila anterior a V19 la tiene nula**. Una DJ
   válida presentada antes de que el predio tuviera ficha —el caso corriente en ventanilla, y
   todas las filas migradas— saldría «no conciliada», que es acusar de omiso a quien declaró.
   `predio_id` es el ligado directo, existe desde V2 y su FK contra `predio` sí está en la tabla
   original. `ficha_catastral_id` contesta otra pregunta —«qué versión declaró»—, que es detalle
   de la declaración y no predicado de pertenencia.

   **Qué estados cuentan**, sobre los cuatro que admite `declaracion_jurada_estado_check` (V2) y
   enumera `EstadoDeDeclaracion`. «Vigente» no es una noción del dominio, así que se enumera:

   | Estado | ¿Concilia? | Por qué |
   |---|---|---|
   | `PRESENTADA` | **sí** | el contribuyente declaró; el predio está en el padrón afecto |
   | `OBSERVADA` | **sí** | la administración objetó **el contenido** de una declaración que existe y fue presentada. Observarla no la retira: negarle la conciliación diría «este predio no genera deuda predial» de un predio que sí la genera, y es el falso omiso otra vez |
   | `SUSTITUIDA` | **no por sí sola** | cuenta **a través de su sustituta**. `rectificadaPor` deja dos filas —la anterior `SUSTITUIDA`, la nueva `PRESENTADA`— sobre el mismo `predio_id`, así que el predicado ya la recoge por la segunda. Contar también la primera duplicaría la misma declaración |
   | `ANULADA` | **no** | la declaración dejó de sustentar nada |

   **Y lleva su dimensión temporal** (regla 9, RNF-075): no existe «conciliado», existe
   `conciliadoA(ejercicio)`. La DJ de 2024 no concilia 2026 —el padrón afecto se rehace cada
   ejercicio—, y `declaracion_jurada.ejercicio` es `NOT NULL` desde V2, así que el dato para
   decirlo ya está. La columna de la pantalla se rotula con el ejercicio al que responde, como
   toda cifra que se muestra indica su fecha.

2. **La lectura la publica rentas.** La columna «Conciliada», el filtro `conciliadaConRentas` y
   el «desde cuándo» los sirve una operación del contexto `rentas` que compone catastro y
   rentas por sus APIs públicas — el patrón exacto de `ConsultaPrediosController`, que ya
   combina `catastro` y `cuentacorriente` en una lectura y explica por qué vive donde vive.
   Cuando esa operación exista, el 422 deliberado de `ConsultaController` se retira; hasta
   entonces la interfaz muestra «—», que es la verdad.

   **La frontera de datos y de permiso se fija aquí, y no cuando se escriba el controlador.**
   La conciliación es un cruce entre dos padrones, y ese cruce es exactamente el punto donde
   una lectura de catastro empieza a publicar información de rentas sin que nadie lo decida:

   1. **El `acceso` es el de la pantalla que se está mirando.** La operación se declara
      `@RequiereAcceso(acceso = "consulta_fichas", privilegio = Privilegio.LECTURA)`, aunque
      viva en `sgtm-rentas`: es el precedente de `ConsultaPrediosController`, que sirve
      `consulta_predios` desde rentas y declara ese acceso y no otro. El permiso sigue a la
      opción del catálogo, no al módulo Gradle donde acabó el código; si siguiera al módulo,
      ver la consulta de fichas exigiría permiso de rentas y quien atiende catastro se
      quedaría fuera de su propia pantalla.

   2. **Lo que cruza la frontera es el derivado y su ejercicio. Nada de la declaración
      jurada.** La respuesta lleva, por predio, si concilia y a qué ejercicio (y, si se
      publica el «desde cuándo», la fecha de presentación de la declaración que lo sostiene).
      **No** viajan el número de la DJ, ni su tipo, ni sus importes, ni el contribuyente que
      la presentó, ni su identificador. Quien tiene permiso de mirar el catastro no adquiere
      con eso permiso de mirar las declaraciones de nadie: para eso está `declaracion_jurada`,
      con su propio acceso. Es la misma línea que `ConsultaDeDeudaPublica` traza para la deuda
      —el importe, no los asientos—.

   3. **`conciliadaConRentas=No` va detrás de un permiso de fiscalización, y deja rastro.**
      Ese filtro no es un filtro más: **es la lista de los predios que no generan deuda
      predial**, ordenada y paginada. Es el producto de trabajo de la fiscalización de omisos
      (`fisc_omisos`), y en manos equivocadas es también el mapa de a quién no le va a llegar
      recibo. Se sirve solo a quien tenga privilegio de lectura sobre la fiscalización, y cada
      consulta que lo use se registra en la bitácora con operación `ACCESO` —el valor ya
      existe en `auditoria_operacion_check` (V5) y la pantalla `auditoria` ya lo muestra—.
      `Sí` y «Todas» no lo necesitan: dicen quién está dentro, no quién falta.

   4. **El código del contribuyente se resuelve al clic, nunca en un listado**
      ([#366](https://github.com/hneyra/sgtm/issues/366)). Este punto decía que publicar el
      `titularId` o el código en una respuesta de catastro era «una decisión aparte, y hoy no
      está tomada». Ya está tomada, y es la opción (b) del issue.

      El hueco es real y legítimo: `FichaEncontradaResource` publica el **nombre** del titular
      y no su código, así que la fila de la consulta no puede enlazar con su ficha de
      contribuyente (#322) y el operador salta al padrón a buscar por nombre, con la homonimia
      que eso invita. Lo que no se hace es taparlo añadiendo el código a la grilla: eso
      convertiría «quien puede listar fichas» —cualquiera que opere catastro— en «quien puede
      cosechar la correlación predio→persona de toda la municipalidad», paginada y ordenable.
      El dato agregado es más sensible que cada dato suelto, que es la misma línea de §2.3.

      **La decisión, entera:**

      > El código del contribuyente titular se obtiene de una operación **puntual** —un predio
      > cada vez—, `GET /api/v1/catastro/predios/{predioId}/titulares`, detrás del privilegio
      > de **lectura sobre `contribuyentes`** —el acceso del padrón— y con **fila de `ACCESO`
      > en la bitácora por resolución, en la misma transacción que la lectura**. El listado no
      > cambia ni un byte.

      Tres cosas que esa frase decide, y conviene que se lean separadas:

      1. **El permiso es el del padrón, y aquí el acceso no sigue a la pantalla.** Es la
         excepción deliberada al criterio de §2.1: allí el acceso sigue a la opción del
         catálogo que se está mirando —`consulta_fichas`, aunque la sirva rentas— porque lo
         que se devuelve es catastro. Aquí lo que se devuelve **no es catastro**: es el
         identificador de una persona en el padrón, con su propio acceso. Exigir el de la
         pantalla dejaría el cruce al alcance de todo el que pueda listar fichas, que es
         exactamente lo que este punto evita.

         El acceso se llama **`contribuyentes`** —la opción «Contribuyentes» del módulo
         Registro (NEG-03)—, no `consulta_contribuyentes`, que es como lo nombró el issue:
         esa opción no existe entre las 134, y un acceso inventado no lo tiene nadie, así que
         el endpoint respondería 403 a todo el mundo y parecería bien cerrado.

      2. **La respuesta es la lista de cuotas vigentes, no «el» titular.** La titularidad
         tiene cuotas: dos cónyuges al 50 %, una sucesión, un condominio. Y **lleva su
         fecha**: no existe «el titular», existe el titular vigente a una fecha (regla 9,
         RNF-075), así que `vigenteA` entra como parámetro —hoy por omisión— y **sale siempre
         en la respuesta**. Resolver «el último» en vez del vigente es el defecto que la ficha
         del contribuyente (#24) ya pagó con los domicilios.

      3. **Vive en `rentas`, y no donde el issue lo pedía.** El issue proponía alojarlo en
         `contribuyentes` —«el contribuyente titular del predio X»— resolviendo la titularidad
         por un puerto público de catastro. **No se puede:** `catastro` ya depende de
         `contribuyentes` desde que la grilla resuelve el nombre de sus titulares, y
         `contribuyentes` no depende de nadie (ARQ-01 §2, §3.1); la dependencia inversa cierra
         un ciclo que Gradle y `verificarArquitectura` rechazan. Y alojarlo en `catastro` —que
         sí podría— publicaría el código del contribuyente en una respuesta de catastro, que
         es justo la frontera que este punto separa. `rentas` es el único que ve los dos sin
         cerrar nada, por el mismo motivo por el que aloja `ConsultaPrediosController` y
         `ConsultaDeConciliacion`. La **ruta**, en cambio, sí es la de la pantalla desde la que
         se hace clic: quién la sirve es un detalle de dónde vive el código (§2.2).

         La mitad de catastro es un puerto nuevo y mínimo, `TitularesDelPredio` →
         `TitularDelPredio` (identificador del titular, condición y porcentaje). Que ahí viaje
         un `contribuyenteId` no contradice a `FichaDelPadron`: `PredioDelPadron` ya lo hacía
         para la detección de omisos (#49) —y desde #545, que lo retiró, ese puerto **es** el
         que la detección usa, con su método por lote—. Lo que no cruza es la frontera **HTTP**
         de un listado.

      **Lo que queda prohibido, y esta decisión no abre:**

      - **El identificador del contribuyente no aparece en ningún listado de catastro.**
        `FichaDelPadron` sigue llevando el nombre y no el código, y su prueba de frontera no se
        toca. Si mañana hace falta, vuelve a pasar por aquí.
      - **No hay forma de pedir varios predios en una petición.** Un endpoint que acepte una
        lista de identificadores es otra vez el extractor masivo, con un viaje en lugar de
        muchos.
      - **Del padrón no viaja nada más que el código y el nombre**: ni el identificador
        interno, ni el documento —con el código se llega a la persona exacta sin compararla por
        DNI, que es el problema que esto resuelve—. Y de la titularidad, ni sus fechas ni el
        documento que la sustenta.
      - **La resolución que no devuelve nada también deja rastro.** Quien va probando
        identificadores de predio para levantar el mapa del padrón deja su nombre en cada
        intento, y los que vuelven vacíos son precisamente los que un auditor querría contar.
        Por lo mismo, un predio sin titular y un predio de otra municipalidad se responden
        igual —200 con lista vacía—: contestar distinto convertiría la lectura en un detector
        de predios ajenos.

3. **El acto que concilia es registrar la declaración jurada, y desde #365 el sistema lo publica.**
   Conciliar no es escribir un código en la ficha —el código ya lo tiene— sino incorporar el
   predio al padrón afecto. Este párrafo ha dicho tres cosas distintas y conviene que se vea por
   qué: primero afirmó que el acto «ya tiene opción propia» y mandaba a buscar una puerta que no
   existía; después, con #344, dijo el estado real —opción `declaracion_jurada` **solo `GET`**,
   caso de uso `RegistrarDeclaracionJurada` escrito y sin controlador que lo expusiera, y el acto
   haciéndose fuera del sistema—; y ahora dice lo que hay:

   - la opción `declaracion_jurada` publica **su lectura y cuatro actos**, cada uno con verbo
     propio y su observación obligatoria (regla 10): `POST /rentas/declaraciones` la presenta
     —**es el acto que concilia**—, `POST /rentas/declaraciones/{djNro}/rectificacion` la
     rectifica, y `…/observacion` y `…/anulacion` son los dos actos de la administración, que
     hasta #365 producían estados que solo la siembra podía fabricar;
   - el **número lo pone el sistema**: correlativo propio (`dj_correlativo`, V54) compuesto con
     `PlantillaDeNumeroDeDeclaracion` mientras D-09 siga abierta, como el valor, el convenio, el
     expediente, la licencia y el certificado. Un número de mesa de partes, si el trámite lo
     tiene, es una referencia del expediente y no la identidad de la declaración;
   - **el `estado` es lo único que un acto mueve**, y eso lo sostiene el motor y no la disciplina
     del repositorio: V54 le retira a `sgtm_app` el `UPDATE` sobre la tabla y le concede el de esa
     columna y solo esa, y un disparador rechaza cualquier acto sobre un estado terminal.
     `declaracion_jurada` entra por eso en `TABLAS_PROTEGIDAS` —borrarla sacaría al predio del
     padrón afecto sin acto que lo explique, o sea un omiso fabricado— y **no** en
     `TABLAS_INMUTABLES`: observar, anular y sustituir no llevan más contenido que quién, cuándo y
     por qué, que es exactamente una fila de `auditoria`, y derivar el estado de una tabla de
     movimientos convertiría esta lectura y la detección de omisos en un *join* por página sin
     ganar nada que el privilegio de columna no dé ya.

   «Conciliar seleccionadas», la acción masiva a ciegas del prototipo, sigue sin implementarse. El
   patrón `acto` de la composición —la acción primaria lleva a la otra opción **con el predio
   puesto**, como «Actualizar catastro»— **ya tiene a dónde llevar**, y cablearlo es la mitad
   frontend de este ADR, con su propio issue. La emisión masiva ya trata al predio sin declaración
   como *observado*, que es el comportamiento correcto.

4. **Nadie escribe en catastro salvo catastro.** ARQ-01 §4 regla 4, entera: «Nadie escribe en
   `catastro` salvo `catastro` y la transferencia de `rentas`, y esa escritura va por el puerto
   público `GestorDeTitularidad` del paquete raíz de catastro». Si algún acto de conciliación
   necesitara tocar el padrón, iría por un puerto así, nunca por SQL directo ni por dependencia
   inversa.

5. **El caso del código heredado es migración, no operación.** Si la implantación encuentra
   códigos prediales del sistema anterior que no casan con el catastral (el `codigoAnterior` de
   solo lectura del prototipo), ese emparejamiento pertenece a D-04 (migración desde SQL
   Server), con su propia herramienta y su propia auditoría. No se le da botón en la operación
   diaria.

## Lo descartado, y por qué

- **`PUT` sobre la ficha en catastro** (opción A del issue): exigiría que catastro consulte
  rentas (ciclo prohibido) o una columna en una tabla cuyo invariante es no sobrescribirse; y el
  dato no es de la versión de la ficha sino del predio — y ni siquiera del predio: es de la
  relación con rentas, que rentas conoce.
- **Parte de la transferencia** (opción C): la transferencia cambia quién es titular, no si el
  predio está afecto; un predio recién fichado sin transferencia alguna es justamente el caso
  que la conciliación describe. `RegistrarTransferencia` documenta además que no genera efectos
  de deuda por sí sola.

## Consecuencias

- **Frontend, hoy** (#322): la columna «Conciliada» y «Cod. Predial Rentas» conservan sus
  rótulos (RNF-080) con contenido honesto — «—» mientras la lectura no exista; el aviso de
  dominio dice la consecuencia («un predio sin declaración jurada no genera deuda predial») y
  cómo se hace hoy ese acto; la insignia, cuando llegue el dato, lleva texto y nunca solo color
  (FRO-02 §2.1). La franja de actos honestos ya cubre «Conciliar seleccionadas», y el filtro
  `conciliadaConRentas` se dibuja **bloqueado**: cuando se escribió, vivo garantizaba el 422 con
  cualquier valor. **Sigue bloqueado tras #344** —la interfaz aún llama a `/catastro/fichas` y
  borra el parámetro antes de enviarlo—, y desbloquearlo es cablear la pantalla contra la ruta
  nueva: la mitad frontend de este ADR, que va en su propio issue.
- **Backend, hecho** ([#344](https://github.com/hneyra/sgtm/issues/344)): la lectura compuesta
  vive en `sgtm-rentas` —`ConsultaDeConciliacion`, con el paginado de fichas que le pide a
  catastro por el puerto público `FichasDelPadron` y el `conciliadoA(ejercicio)` derivado de
  `declaracion_jurada.predio_id` sobre los estados de §1—, la publica
  `GET /api/v1/catastro/fichas/conciliacion` con el acceso `consulta_fichas`, y el filtro «No»
  va detrás de `fisc_omisos` con su fila de `ACCESO` en la bitácora. **Ninguna migración**: no
  había columna que añadir porque no hay estado que guardar.

  El 422 de `ConsultaController` **se retiró redirigiendo**: la petición que trae
  `conciliadaConRentas` se responde con `307` y la consulta entera hacia la ruta nueva. Es la
  opción que conserva lo que el 422 defendía —no contestar con un listado sin filtrar, que sería
  plausible y equivocado— y además contesta. Que el destino sea un subcamino del mismo recurso,
  `/catastro/fichas/conciliacion`, es lo que evita que catastro nombre una ruta de otro módulo:
  quién la sirve es un detalle de dónde vive el código.
- **El contrato compartido documenta las dos rutas**, y solo desde que las dos existen: declarar
  una ruta sin servidor pondría en rojo la prueba de las dos direcciones, a propósito.
- **El titular enlaza desde [#366](https://github.com/hneyra/sgtm/issues/366), y por la puerta
  que §2.4 decide.** El listado sigue sin él —`FichaDelPadron`, la proyección que cruza la
  frontera, lleva el **nombre** del titular y no su identificador, y su prueba de frontera no
  se tocó—; lo que hay es una resolución puntual,
  `GET /api/v1/catastro/predios/{predioId}/titulares`, que la sirve `rentas`
  —`ConsultaDeTitulares`, componiendo el puerto nuevo `catastro.TitularesDelPredio` con
  `contribuyentes.DirectorioDeContribuyentes`—, exige lectura sobre `contribuyentes` y escribe
  su fila de `ACCESO` sobre `titularidad` en la misma transacción que la lectura. Devuelve
  **las cuotas vigentes a una fecha**, que la respuesta dice siempre. **Ninguna migración**: no
  hay dato nuevo que guardar, solo uno que se deja preguntar con permiso y con rastro.

  Y el intento de alojarlo donde el issue lo pedía dejó un hallazgo que conviene no volver a
  descubrir: **`contribuyentes` no puede depender de `catastro`**. Es la base del grafo de
  ARQ-01 §2 —todos lo referencian y él a nadie— y `catastro` ya depende de él para resolver el
  nombre de sus titulares, así que «el endpoint natural es de contribuyentes» era, en el código
  real, un ciclo de módulos.
