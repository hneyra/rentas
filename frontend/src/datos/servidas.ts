/**
 * Las operaciones que el backend YA sirve en el entorno donde corre la aplicacion.
 *
 * <h2>Treinta y dos: dos de sesion (I-1), cuatro de seguridad (I-3), seis del padron (I-4), dos de
 * licencias (#168), cuatro de la cobranza coactiva (#170), dos de indicadores (#167), tres de
 * la ventanilla de Consultas (#169), la bitacora de auditoria (#181) y cuatro de
 * Fiscalizacion (#179) y cuatro de Transito (#180)</h2>
 *
 * La integracion no es un salto. El backend publica 181 operaciones y el proxy simula
 * dieciocho: encenderlas todas a la vez seria cambiar 181 respuestas en una sola tarde sin poder
 * decir cual de ellas rompio la pantalla. Con esta lista se enciende una, se mira, y se enciende
 * la siguiente — el proxy deja pasar lo que este declarado aqui y sigue contestando lo demas.
 *
 * <h2>Por que estas dos y no otras</h2>
 *
 * Porque son las que **demuestran que el camino existe**, y ninguna otra lo demuestra tan
 * barato. No dibujan una tabla ni un importe: contestan quien esta trabajando y de que
 * municipalidad, que es lo unico que hace falta para saber que el token viajo, que Vite lo
 * encamino, que Traefik lo enruto por `PathPrefix(/rentas)`, que la cadena de identidad lo
 * acepto y que `SET LOCAL` fijo el inquilino. Si algo de esa cadena falla, fallan estas dos y no
 * hay ninguna pantalla a medio pintar de por medio.
 *
 * Y son las dos que hacian **mentir a la barra global**: hasta I-1 el nombre de la entidad y el
 * del usuario eran constantes del artboard —«Municipalidad Distrital de Catacaos» y «J. Cárdenas
 * Vega»—, o sea que la cabecera de todas las pantallas afirmaba de quien son unas cifras sin
 * haberselo preguntado a nadie.
 *
 * <h2>Lo que hizo falta antes, en el orden en que se dijo</h2>
 *
 * El javadoc anterior lo dejo escrito: «token en `solicitar()`, `server.proxy` hacia el backend,
 * y entonces una entrada aqui». Los tres estan, y los dos primeros eran de verdad bloqueantes:
 *
 * <ol>
 *   <li><b>El token.</b> `solicitar()` manda `Authorization: Bearer` desde `api/identidad.ts`,
 *       que lo consigue con codigo de autorizacion y PKCE S256. Sin el, encender una ruta no
 *       traeria datos: traeria un <b>401</b> en `problem+json`, medido con `curl`.</li>
 *   <li><b>El camino.</b> `vite.config.ts` declara `server.proxy` hacia el backend. Sin el,
 *       `/rentas/api/v1/...` lo atendia el propio servidor de Vite y devolvia el `index.html`
 *       de la aplicacion: un <b>200 con HTML</b> donde la pantalla espera JSON, que es peor que
 *       un error porque no parece uno.</li>
 * </ol>
 *
 * <h2>Una ruta declarada aqui tiene que existir en el contrato, y se comprueba</h2>
 *
 * `verificaciones/camino-a-la-api.test.ts` exige que cada entrada de esta lista sea una clave de
 * `docs/50-api/formas-de-la-api.json` —el archivo que genera `FormasDeLaApiTest` del tipo de
 * retorno de cada controlador—. Es una comprobacion **estatica** y es la que sostiene la lista:
 * declarar aqui una ruta que el backend no publica sale rojo antes de que nadie levante nada.
 *
 * Sustituye a una heuristica que estaba mal y que I-1 quito con su medida: el proxy convertia en
 * un 502 ruidoso **cualquier 404** de una ruta declarada, dando por hecho que un 404 significaba
 * «esa ruta no esta publicada». No lo significa. El cuarto peldano de la escalera de identidad
 * —el token identifica a alguien que no es usuario de esta municipalidad— es un <b>404 legitimo
 * de una ruta que si existe</b>, y confundirlos se lo tragaba entero. Ver `api/proxy.ts`.
 */

/** Una operacion que el backend ya sirve. Se compara por verbo y por ruta, con sus `{...}`. */
export interface OperacionServida {
  readonly metodo: string;
  /** Ruta bajo la raiz del sistema, con sus parametros entre llaves. */
  readonly ruta: string;
}

/**
 * Lo que el backend ya sirve. Todo lo demas lo sigue contestando el proxy.
 *
 * El tipo es `readonly OperacionServida[]` y no una tupla: lo que cambia el dia que se encienda
 * la siguiente es esta lista, y nada mas.
 *
 * <b>Son treinta y dos, y llegaron en nueve tandas</b>: las dos de sesion que abrieron el camino
 * (I-1), las cuatro con que se compone la navegacion (I-3), las seis del padron de contribuyentes
 * (I-4), las dos de autorizaciones y licencias (#168), las cuatro de la cobranza coactiva (#170),
 * las dos de indicadores con que se conecta el modulo Inicio (#167), las tres de la ventanilla de
 * Consultas (#169), la bitacora de auditoria (#181) las cuatro de Fiscalizacion (#179) y las
 * cuatro de Transito (#180). Cada tanda dejo escrito lo que vio al encender lo suyo, y las nueve
 * notas siguen aqui porque lo que se vio es lo que justifica que la ruta este en la lista.
 *
 * <h2>Las cuatro que enciende I-3, en el orden en que se encendieron</h2>
 *
 * <ol>
 *   <li><b>`GET /seguridad/modulos`.</b> Al encenderla llegan <b>12</b> modulos en el
 *       envoltorio paginado —`totalElementos: 12`, `tamano: 20`, una pagina—, y sus nombres
 *       resultaron ser <b>los rotulos del artboard byte a byte</b> para los diez que este
 *       sistema sirve. Eso no se sabia antes de pedirla: era la premisa que hacia posible
 *       empalmar el catalogo con el catalogo del backend sin traducir nada.</li>
 *   <li><b>`GET /seguridad/accesos`.</b> Llegan <b>134</b> con su `moduloId`, y **es la unica
 *       razon por la que esta lectura esta en la lista**: sin ella, la matriz de permisos es
 *       una bolsa de 134 codigos planos sin ninguna forma de saber a que rama pertenece
 *       ninguno. Se pide con `?tamano=200` porque el tamano por omision es 20 (ver `RUTAS`).</li>
 *   <li><b>`GET /seguridad/sesion/permisos`.</b> Llegan <b>134</b> llaves, una por acceso, cada
 *       una con sus siete privilegios. Y al pedirla con las <b>dos</b> cuentas de la
 *       instalacion salieron <b>identicas</b>, llave a llave: ninguna de las dos ejercita el
 *       filtro. Eso cambio como se prueba el AC2 — ver `marco/seguridadMedida.ts`.</li>
 *   <li><b>`PUT /seguridad/sesion/ejercicio`.</b> La primera <b>escritura</b> de esta interfaz.
 *       Con observacion contesta 200 y la sesion con su ejercicio dentro; sin ella contesta
 *       <b>500</b>, que es el defecto #30 y no se arregla aqui — se rodea no mandando nunca una
 *       vacia—. Con una observacion corta o un ejercicio fuera de 1990-2100 contesta
 *       <b>422 `VALIDACION`</b> con su frase, y esa frase es la que la pantalla ensena.</li>
 * </ol>
 *
 * <h2>Dos de las cuatro piden un permiso de ADMINISTRACION, y hay que decirlo</h2>
 *
 * `GET /seguridad/modulos` declara `@RequiereAcceso(acceso = "modulos", …)` y
 * `GET /seguridad/accesos` declara `acceso = "accesos"` — o sea «Modulos del sistema» y
 * «Accesos y politicas», las dos opciones con las que se administra el catalogo. **Asi que la
 * navegacion de esta aplicacion se compone hoy de dos operaciones que una cuenta de ventanilla
 * no tiene por que poder llamar**, y a la que no las tenga le contestaran 403 `SIN_PRIVILEGIO`:
 * no se quedaria sin un modulo, se quedaria sin arbol. Es exactamente el caso del AC7, y por
 * eso la pantalla lo explica y ofrece reintentar en vez de dibujar un marco vacio. Cerrarlo de
 * verdad no es de este lado: es publicar el menu de la sesion —el catalogo filtrado por quien
 * pregunta— y eso es del dueno de `seguridad`.
 *
 * <h2>Las seis de I-4, con lo que se vio al encender cada una</h2>
 *
 * Medido contra la instalacion el 2026-09-07, con `administrador` (municipalidad 9) para la
 * escala y `jperez` (municipalidad 1) para el movimiento:
 *
 * <ol>
 *   <li><b>`GET /rentas/contribuyentes`</b> — 200. **10 603 contribuyentes** y `totalPaginas:
 *       5302` con `tamano=2`; 16 con `jperez`. Lo que se vio al encenderla es que la lista deja
 *       de ser una lista y pasa a ser una <b>ventana</b>: el conteo «5 de 5» que dibujaba F-5
 *       decia «20 de 20» sobre un padron de diez mil, y el buscador del cliente contestaba
 *       «ningun contribuyente coincide» para gente que si estaba. De ahi salen AC1, AC2 y
 *       AC3.</li>
 *   <li><b>`GET /coactiva/deudas`</b> — 200 con <b>lista vacia</b> en las dos municipalidades.
 *       No es una averia: es el dato. Con el backend sano, el chip que depende de esta operacion
 *       sale vacio, y la pantalla tiene que decir «ninguno» y no «no se pudo leer» (AC5).</li>
 *   <li><b>`GET /rentas/predial/corridas/ultima`</b> — 200. `{"id":20,"ejercicio":"2026",
 *       "alcance":"TODOS","simulacion":true,…}` con `administrador` y `id: 18` con `jperez`.
 *       Trae tres campos que el port no leia y el contrato si declara —`sector`, `simulacion` y
 *       `conjunto`—, asi que se anadieron al tipo: un campo declarado es un campo que el
 *       proveedor no puede retirar sin poner rojo su build.</li>
 *   <li><b>`GET /rentas/predial/corridas/{corridaId}/observados`</b> — 200 con lista vacia. Se
 *       enciende junto a la anterior porque no se puede pedir sin ella: su `corridaId` sale de
 *       la respuesta de la ultima corrida.</li>
 *   <li><b>`GET /rentas/contribuyentes/{id}/ficha`</b> — 200. Y lo que se vio al encenderla es
 *       lo que obligo a cambiar el tipo: `datosPersonales.fechaNacimiento` y `estadoCivil`
 *       llegan <b>nulos</b>, y la tabla `domicilio` esta vacia en el origen, asi que
 *       `domicilioFiscal` tambien. Un 404 legitimo —`{"codigo":"NO_ENCONTRADO"}`— es la
 *       respuesta a un identificador que no es de esta municipalidad.</li>
 *   <li><b>`GET /rentas/beneficios?contribuyente={codigo}`</b> — 200 con <b>lista vacia</b>, y
 *       la tabla `beneficio` esta vacia tambien en el origen del volcado. Se enciende porque es
 *       la unica de las tres del expediente que <b>si</b> puede acotarse a un contribuyente con
 *       un parametro que la operacion publica (`?contribuyente=`, comparado contra
 *       `c.codigo_contribuyente`). El estado vacio se dibuja como estado y no como averia
 *       (AC9).</li>
 * </ol>
 *
 * <h2>Y las dos del expediente que NO se encienden, con su medida</h2>
 *
 * <b>`GET /rentas/predios`</b> y <b>`GET /consultas/deuda`</b> exigen `?codContribuyente=` —sin
 * el, <b>422</b>—, y hasta #26 el contrato <b>no publicaba ese parametro</b>: mandarlo era
 * construir sobre un nombre que nada comprobaba. <b>Desde #26 lo publica</b>
 * —`docs/50-api/parametros-de-la-api.json`, generado de la firma del controlador—, el expediente
 * lo manda y el proxy contesta 422 sin el, igual que el backend. O sea que el motivo por el que
 * estas dos no se encendian <b>ya no existe</b>.
 *
 * Encenderlas es lo siguiente y <b>no se hace aqui</b>, por una razon medible y no por prudencia:
 * las once de arriba se encendieron <b>midiendo cada una contra la instalacion</b> —el 200, su
 * cuerpo, y los campos nulos que obligaron a cambiar el tipo—, y esa medida es la que dice si la
 * pantalla dibuja un estado vacio o una averia. Sin ella, anadirlas a esta lista seria afirmar
 * algo que nadie comprobo. Mientras tanto el expediente dice de donde sale cada una de sus tres
 * tablas, porque un contribuyente de verdad con los predios del artboard debajo seria peor que
 * una tabla vacia.
 *
 * <h2>Las CUATRO de #170, las de la cobranza coactiva</h2>
 *
 * Son las que encienden `coa-exp` y `coa-cost`, y **ninguna de las cuatro exige un parametro**:
 * los que se les mandan —`tamano`, `tributo`— son opcionales y estan publicados en
 * `docs/50-api/parametros-de-la-api.json`, que es la condicion que #26 dejo escrita.
 *
 * <ol>
 *   <li><b>`GET /coactiva/expedientes`</b> — la cartera. De ella sale <b>que</b> expediente
 *       dibuja `coa-exp`: la pantalla todavia no tiene con que elegirlo, asi que toma el primero
 *       de la relacion. Es la misma composicion que ya usaba el panel del predial con
 *       `corridas/ultima` -> `observados`: la segunda lectura no se puede pedir sin la
 *       primera.</li>
 *   <li><b>`GET /coactiva/expedientes/{numero}/proceso`</b> — la linea de vida del expediente.
 *       Publica la cabecera <b>otra vez</b> —`ExpedienteResource` entero— y ademas sus actos, asi
 *       que los seis campos de la cabecera se leen de aqui y no de la lista: dos fuentes para el
 *       mismo dato es como se llega a una pantalla que se contradice consigo misma.</li>
 *   <li><b>`GET /coactiva/liquidaciones-costas`</b> — la relacion de liquidaciones, con el
 *       detalle de cada una <b>linea por acto</b>. Es la que dibuja `coa-cost`.</li>
 *   <li><b>`GET /coactiva/prescripcion`</b> — la relacion de prescripciones declaradas. Se pide
 *       <b>acotada al tributo de la liquidacion</b>, que es la unica llave que las dos
 *       comparten.</li>
 * </ol>
 *
 * <b>Y la quinta que NO se enciende, con su motivo</b>: `POST /coactiva/convenios` publica los
 * ocho campos de `coa-cart` y **crea un convenio de fraccionamiento**. No existe el `GET`. Esta
 * interfaz hace una sola escritura —`PUT /seguridad/sesion/ejercicio`— y desde luego no va a
 * hacer la segunda para pintar una pantalla: pedir datos fraccionando la deuda de alguien es
 * exactamente el modo de fallo que esta lista existe para no tener.
 *
 * <b>`GET /rentas/arbitrios`</b> tambien contesta 200 con lista vacia, medido, y tampoco se
 * enciende: es de la seccion «Valores» (F-6) y no del padron. Encenderla en este PR cambiaria
 * una pantalla que este issue no toca.
 *
 * <h2>Y las DOS de licencias (#168), con lo que se midio al encender cada una</h2>
 *
 * Lo que se midio aqui **no es un `curl`, y hay que decirlo**: es el contrato generado de los
 * controladores —`docs/50-api/formas-de-la-api.json` y `parametros-de-la-api.json`, regenerados
 * para este issue— campo a campo contra lo que cada pantalla dibuja. Es lo que hace que la guarda
 * de mas abajo pueda ponerse roja sin que nadie levante nada.
 *
 * <ol>
 *   <li><b>`GET /licencias/ciiu`</b> — publica una pagina de ocho campos por fila, y las
 *       <b>cuatro</b> columnas de la tabla de `aut-cat` salen de cuatro de ellos: `codigo`,
 *       `descripcion`, `seccion` y `riesgoItse`. Se enciende porque la cobertura es entera, y se
 *       pide con <b>`?tamano=20`</b> escrito: el catalogo tiene 1 842 giros —el propio artboard lo
 *       dice, y por eso elige un Combobox— asi que la tabla es una ventana. El parametro con el
 *       que el buscador filtrara es `?descripcion=`, que el contrato publica.</li>
 *   <li><b>`GET /licencias/funcionamiento`</b> — publica veintiun campos por licencia, y las
 *       <b>cinco</b> columnas del padron de `aut-tram` salen de `nroLicencia`, `contribuyente`,
 *       `denominacionComercial`, `giros[].descripcion` y `estado`. Es la unica de las diecisiete de
 *       licencias cuya forma cuadra con lo que esa tabla ensena. <b>Hasta #173 el artboard se la
 *       atribuia a `aut-panel`</b> y `aut-tram` la pedia sin declararla; #173 lo decidio con el
 *       artboard delante y la movio en los dos sitios a la vez. El motivo, campo a campo, en
 *       `conectores/licencias.ts`.</li>
 * </ol>
 *
 * <b>Lo que este par NO enciende, y por que.</b> Los seis mandos de `aut-tram` —ejercicio, tipo,
 * estado, agrupacion y el par Desde/Hasta— <b>no son parametros</b> de `GET
 * /licencias/funcionamiento`: los ocho que admite son otros. Quien los admitiria es `POST
 * /licencias/funcionamiento/reportes/padron`, y es una <b>escritura</b> por el verbo — esta
 * interfaz hace una sola, y no es esta.
 * <h2>Y las DOS de indicadores, que enciende #167</h2>
 *
 * Son las dos operaciones del modulo <b>Inicio</b>, y lo que se midio al encenderlas no fue una
 * respuesta de la instalacion sino <b>el contrato generado de los controladores</b>
 * —`docs/50-api/formas-de-la-api.json`, que `FormasDeLaApiTest` produce del tipo de retorno de
 * cada uno—. Es lo que decide si una pantalla puede pintarse, y se leyo campo a campo:
 *
 * <ol>
 *   <li><b>`GET /indicadores/recaudacion`</b> publica `ejercicio`, `fechaCalculo`, `calculadoEn`,
 *       <b>`cargado`</b> —`{importe, actualizadoA}`: lo emitido del ejercicio, como campo—, `kpis`
 *       —`{label, value, note, importe}`— y `paneles` —`{title, note, rows}` con
 *       `rows[].{label, sub, value, pct, avanceConocido, importe, cargado, pendiente}`—. Las tres
 *       ultimas de la fila son las que hacen que «Cuadre por tributo» salga <b>entera</b>: emitido,
 *       recaudado y saldo son tres `ImporteConFecha` distintos, y no hay que restar ninguno.
 *       <b>Admite `?ejercicio`, y es opcional</b> (`parametros-de-la-api.json`): sin el contesta
 *       igual, asi que se pide sin parametros y la respuesta dice de que ejercicio es.</li>
 *   <li><b>`GET /indicadores/trabajo-parado`</b> publica `ejercicio`, `fechaCalculo`,
 *       `calculadoEn` y `frentes` —`{frente, modulo, queEstaParado, porQueCuestaDinero, cuantos,
 *       importe}`—. Cinco de esos seis son las cinco columnas de «Frentes abiertos», columna a
 *       columna; el sexto, `frente`, es el nombre del enumerado y esta para enrutar. Tambien
 *       admite `?ejercicio` opcional.</li>
 * </ol>
 *
 * <b>Lo que NO se midio, y hay que decirlo</b>: ninguna de las dos se pidio contra la instalacion.
 * Las once de I-1/I-3/I-4 se encendieron con su `curl` delante —el 200, su cuerpo, y los campos
 * nulos que obligaron a cambiar el tipo—, y aqui la medida es el contrato. La diferencia importa
 * en un sitio concreto: `importe`, `cargado` y `pendiente` estan declarados <b>anulables</b>
 * porque el controlador los declara `@Nullable`, y el conector los dibuja como «sin cifrar» en vez
 * de como «0.00» sin haber visto todavia una respuesta con un nulo dentro.
 *
 * <h2>Las tres de Consultas (#169), y con que se comprobo cada una</h2>
 *
 * Son <b>de un contribuyente concreto</b>, las tres, y por eso lo primero que hubo que mirar no
 * fue la forma de la respuesta sino <b>el contrato de la peticion</b> —
 * `docs/50-api/parametros-de-la-api.json`, generado de la FIRMA de cada controlador—: es
 * exactamente lo que dejo fuera a `GET /consultas/deuda` hasta #26. Las tres lo publican:
 *
 * <ol>
 *   <li><b>`GET /consultas/unificada?contribuyente={codigo}`</b> — `contribuyente` es
 *       <b>obligatorio</b> en el contrato y lo exige el controlador. Publica `contribuyente`
 *       —codigo, nombre y documento—, `aLaFecha` y `resumenDeSaldos` con sus cinco importes y
 *       `estadoDeLaConsulta`; ademas, seis secciones paginadas que esta hoja no dibuja porque
 *       <b>no tiene tabla</b>. Un codigo que no es de esta municipalidad da <b>404</b>
 *       `NO_ENCONTRADO`, no una ficha vacia: lo dice `ConsultaUnificada#de`.</li>
 *   <li><b>`GET /consultas/deudas-con-beneficio?contribuyente={codigo}`</b> — el contrato lo
 *       declara <b>opcional</b> y el controlador lo exige igual: sin el, 422 «contribuyente es
 *       obligatorio: la simulacion del acogimiento es de una persona concreta, no del padron
 *       entero». Asi que se manda siempre. `simulacion` llega <b>nula</b> mientras no se elija
 *       campana con `?benefAplicable=`, y eso no es un hueco de la interfaz: es la operacion
 *       diciendo que no hay descuento que simular (ver `conectores/consultas.ts`).</li>
 *   <li><b>`GET /consultas/constancias/no-adeudo?codContribuyente={codigo}`</b> — obligatorio, y
 *       <b>sin segundo nombre</b>: aqui no vale `contribuyente`. Con `?formato=PDF|XLS|RTF` el
 *       mismo controlador contesta el archivo (RF-132) y no el JSON; esta lista enciende la
 *       operacion que la pantalla pinta, que es la de sin parametro.</li>
 * </ol>
 *
 * <h2>Y la de #181: la primera servida con un parametro OBLIGATORIO que NO va en la ruta</h2>
 *
 * <b>`GET /seguridad/auditoria`</b> — la bitacora, y la unica operacion que declara `seg-aud`.
 * Publica una pagina de doce campos por movimiento —`id`, `ejercicio`, `tabla`, `clave`,
 * `operacion`, `usuario`, `origenEquipo`, `origenIp`, `fecha`, `observacion`, `datosAnteriores` y
 * `datosNuevos`—, y las cuatro primeras columnas de «Movimientos» salen de cuatro de ellos. La
 * quinta, «Riesgo», no la publica nadie; el reparto campo a campo esta en
 * `conectores/seguridad.ts`.
 *
 * <b>Lo que la hace distinta de las veintitres de arriba, y es el motivo de #181</b>:
 * `parametros-de-la-api.json` la declara con <b>`ejercicio` entre los obligatorios</b> —la unica
 * de las treinta y dos que tiene un obligatorio fuera de la ruta— y el controlador lo exige en la
 * firma (`@RequestParam("ejercicio") int`), asi que sin el la peticion <b>ni siquiera llega al
 * metodo</b>: Spring contesta 422 «Falta el parametro obligatorio 'ejercicio'». Y no es un filtro
 * que se pueda omitir por comodidad — `ConsultaDeAuditoria` lo dice en su propio javadoc: la tabla
 * esta <b>particionada por ejercicio</b>, y una consulta sin el recorre todas las particiones.
 *
 * Los otros nueve que admite —`usuario`, `tabla`, `operacion`, `desde`, `hasta`, `ordenarPor`,
 * `pagina`, `tamano`, `direccion`— <b>son opcionales y no se mandan</b>: son los seis mandos que
 * la pantalla dibuja, y pasarselos al conector es lo que pide #172. De los nueve, `tamano` si se
 * manda —la bitacora tiene 84 182 movimientos segun el propio artboard, asi que la tabla es una
 * ventana— por el mismo motivo por el que `RUTAS.ciiu` lo escribe.
 *
 * <b>Lo que NO se midio, y hay que decirlo</b>: tampoco esta se pidio contra la instalacion. La
 * medida es el contrato generado de los controladores y el codigo de `SesionController`,
 * `ConsultaDeAuditoria` y `Operacion`. Hay un sitio donde eso se nota y esta dicho en el conector:
 * `fecha` es un `Instant`, o sea que Jackson lo publica en <b>UTC</b>, y esta interfaz lo ensena
 * tal cual en vez de moverlo a la hora de Lima.
 *
 * <b>Y lo que NO se pudo hacer con estas tres, dicho aqui y no descubierto luego</b>: no se
 * midieron contra la instalacion con `curl`, como si se midieron las seis de I-4. Lo que se leyo
 * es el contrato generado de los controladores reales —`docs/50-api/formas-de-la-api.json`,
 * campo a campo— y el codigo de los tres controladores. Que la respuesta de verdad traiga nulos
 * donde el contrato declara un objeto —lo que en I-4 obligo a cambiar `FichaDelContribuyente`—
 * esta previsto en los tipos (`simulacion` es anulable) pero <b>no comprobado contra un
 * servidor</b>. Si alguna contesta algo que no cuadre, la pantalla lo dira como averia y no como
 * dato: los cuatro estados de `useDatosDeLaHoja` estan puestos para eso.
 *
 * <h2>Las SEIS de Fiscalizacion —cuatro de #179 y dos de #215—, y la que se decidio NO encender</h2>
 *
 * Lo que se midio aqui es el contrato generado de los controladores —`formas-de-la-api.json` y
 * `parametros-de-la-api.json`— <b>y el codigo de los seis controladores</b>, que es donde estaba
 * lo que el contrato no puede decir: que casi todos los importes de este modulo <b>llegan nulos
 * hasta D-02a</b>. Ninguna de las cuatro exige parametro de consulta; las dos que llevan algo
 * obligatorio lo llevan <b>en la ruta</b>.
 *
 * <ol>
 *   <li><b>`GET /fiscalizacion/programas`</b> — la relacion de programas. <b>El artboard no se la
 *       atribuia a ninguna hoja</b>, y sin ella las otras dos de `fis-prog` no se podian llamar
 *       nunca: las dos llevan `{id}`, que es el identificador <b>interno</b> del programa y no el
 *       «Nº de programa» que la pantalla teclea. Corregido en el artboard y en `arbol.ts`, que es
 *       la unica forma de corregirlo (ver `pantallas/arbol.ts`).</li>
 *   <li><b>`GET /fiscalizacion/programas/{id}/muestra`</b> — los predios sorteados de ese
 *       programa. Un `{id}` que no existe da <b>404</b>; un programa sin muestra sorteada da
 *       <b>200 con pagina vacia</b>, y la pantalla tiene que decir eso distinto de una averia.</li>
 *   <li><b>`GET /fiscalizacion/actas`</b> — la relacion de actas de inspeccion. Publica el lado
 *       <b>hallado</b> y no el declarado, que es lo que decide cuantas celdas de su tabla pueden
 *       llenarse (ver `conectores/fiscalizacion.ts`).</li>
 *   <li><b>`GET /fiscalizacion/resoluciones/{numero}`</b> — la resolucion de determinacion. El
 *       numero va en la RUTA. Desde #193 publica ademas sus <b>tres totales</b> y su `actaId`, y
 *       cada linea su `baseOmitida`; con D-02a abierta los importes llegan nulos y por eso publica
 *       `esperaSusCifras`, para que la pantalla pueda escribir «sin cifrar» en vez de un cero
 *       <b>sin adivinar</b> por que el campo esta vacio.</li>
 *   <li><b>`GET /fiscalizacion/resoluciones`</b> (#192, y se enciende en #215) — la <b>relacion</b>,
 *       paginada. Hasta #192 no existia, y esa ausencia era lo unico que obligaba a `fis-res` a
 *       exigir sujeto: era la unica hoja del sistema que no podia tomar «la primera de la
 *       relacion», de modo que abierta desde el menu <b>no ensenaba una resolucion nunca</b>. Su
 *       unico filtro es `?contribuyente=`, por el <b>codigo del padron</b>, y no se manda.</li>
 *   <li><b>`GET /fiscalizacion/programas/{id}/embudo`</b> (#196, y se enciende en #215) — las
 *       cuatro cifras de `fis-panel`, juntas y cuadradas en UNA lectura. Es lo que esa hoja no
 *       tenia: lo unico que declaraba era `estado-cuenta`, que publica la deuda de fiscalizacion de
 *       un contribuyente y ni una de las cuatro. <b>No admite ni un parametro</b>, y una de sus
 *       cifras —`conActa`— <b>no era</b> el rotulo que el artboard dibujaba, y desde #241 lo es:
 *       la celda decia «Con acta cerrada» y dice «Con acta levantada», que es lo que la cifra
 *       cuenta. Ver `conectores/fiscalizacion.ts`.</li>
 * </ol>
 *
 * <b>Y `GET /fiscalizacion/estado-cuenta` sigue apagada</b>, aunque `fis-panel` la declare y ya este
 * conectada: publica la deuda de fiscalizacion de UN contribuyente —con `?contribuyente=`— y esta
 * pantalla no elige a ninguno. Que una hoja tenga conector no enciende sus otras rutas.
 *
 * <b>Y la que NO se enciende, con su motivo</b>: <b>`GET /fiscalizacion/omisos`</b>, que `fis-prog`
 * si declara. Publica la <b>deteccion</b> —los 3 418 predios que el cruce senala— y la tabla de esa
 * hoja se titula «Muestra del programa», que son los 96 sorteados; pintar una poblacion bajo el
 * rotulo de la otra seria un conteo falso con formato de bueno. Ademas sus cuatro importes
 * —`valorCatastralS`, `valorDeclaradoS`, `diferenciaS` e `impuestoOmitidoS`— salen <b>nulos hasta
 * D-02a</b>, y `diferenciaS` no llega ni a existir en el dominio: el resource lo pasa `null` a
 * mano.
 *
 * <b>Las tres escrituras del modulo tampoco</b>: `POST /fiscalizacion/programas/{id}/muestra`
 * —«Regenerar muestra», que es el boton de la tabla—, `POST /fiscalizacion/liquidaciones` y
 * `PATCH /fiscalizacion/liquidaciones/{numero}/estados`. Esta interfaz hace UNA escritura y no es
 * ninguna de esas: sortear una muestra o liquidar la deuda de alguien para pintar una pantalla es
 * el modo de fallo que esta lista existe para no tener.
 *
 * <h2>Las CUATRO de Transito (#180), y las DOS que se comprobaron para dejarlas fuera</h2>
 *
 * La medida vuelve a ser el contrato generado de los controladores —`formas-de-la-api.json` para la
 * respuesta y `parametros-de-la-api.json` para la peticion—, leido campo a campo contra lo que cada
 * pantalla dibuja, mas el codigo de los tres controladores. Ninguna se pidio contra la instalacion.
 *
 * <ol>
 *   <li><b>`GET /transito/papeletas`</b> — la relacion de papeletas de transito, paginada, con
 *       veintiun campos por fila. Se enciende por <b>uno</b>: `numero`, que es lo que hace falta
 *       para pedir el expediente de abajo. Se pide con <b>`?tamano=1`</b>, escrito: la pantalla
 *       dibuja los actos de UNA papeleta. Sus seis criterios —`nroPapeleta`, `placa`,
 *       `documentoDelInfractor`, `desde`, `hasta`, `estado`— estan publicados y llegan con
 *       #172.</li>
 *   <li><b>`GET /transito/papeletas/{numero}/actos`</b> — el expediente de la papeleta: sus
 *       `descargos[]` y <b>todos</b> sus `actos[]` con sus `acuses[]`, uno por intento. De ahi
 *       salen cuatro de las cinco columnas de `tra-pap`; la quinta —«Estado»— no la publica
 *       <b>nadie</b>, y el porque esta en `conectores/transito.ts`.</li>
 *   <li><b>`GET /transito/internamientos`</b> — la grilla «Vehiculos en deposito». Publica los
 *       dias con su `calculadoA` (regla 9) y <b>ningun importe</b>: `tasaDeCustodia` es el
 *       <b>concepto del TUPA</b>, no una tarifa, y el backend explica en su javadoc que no publica
 *       «Tasa diaria S/» ni «Custodia S/» porque su ordenanza es <b>D-02b</b>, que sigue abierta.
 *       Es el unico caso hasta hoy en que un hueco de la interfaz es el de una decision abierta del
 *       negocio, y no el de un campo que alguien olvido.</li>
 *   <li><b>`GET /rentas/vehiculos/{placa}`</b> — la ficha del vehiculo, y la primera operacion
 *       encendida que lleva su sujeto <b>en la ruta</b>. Publica marca, modelo, categoria, anios,
 *       motor, serie, estado y el historial de placas. Contesta <b>404</b> con una placa que no es
 *       de esta municipalidad y <b>422</b> con una mal formada: dos respuestas distintas a
 *       proposito, y la pantalla las dice como averia y no como dato.</li>
 * </ol>
 *
 * <b>Y las DOS que el arbol declara y NO se encienden, comprobadas y no supuestas</b>:
 * `/transito/descargos` y `/transito/constancias-libres` <b>si</b> estan en el contrato, y las dos
 * con un solo verbo: <b>`POST`</b>. La primera registra un recurso contra una papeleta —pedirle
 * datos seria presentar un descargo en nombre de alguien para pintar una pantalla, y ademas sus
 * filas ya salen por `.../actos`, que publica `descargos[]`—; la segunda contesta <b>`"archivo"`</b>
 * y no un JSON con campos. Esta interfaz hace UNA escritura, y no es ninguna de las dos.
 *
 * <h2>Y la QUINTA de Transito (#184): el resumen que ya estaba publicado y no consumia nadie</h2>
 *
 * <p><b>`GET /transito/reportes/resumen-papeletas`</b> — el agregado de papeletas por grupo, con el
 * total del ejercicio y una linea por grupo de doce campos. Es una de las <b>diez</b> rutas bajo
 * `/transito/reportes/` que el contrato publica y que ninguna pantalla nombraba: el backend iba por
 * delante de la interfaz, y lo que faltaba era medir cual de ellas dibuja este panel.
 *
 * <p><b>Se midieron las cuatro `resumen-*` contra los cinco recuentos de `tra-panel`, y solo esta
 * sirve</b>:
 *
 * <ul>
 *   <li><b>`resumen-por-codigo`</b> y <b>`resumen-por-placa`</b> publican <b>la misma forma</b>
 *       agrupada por otra cosa —el codigo de infraccion y las dos iniciales de la placa—. No
 *       aportan ni un campo que esta no tenga, y lo que traen viene repartido por una dimension
 *       que este panel no dibuja. Ademas <b>ninguna hoja del artboard las ensena</b>: sus pantallas
 *       son opciones del catalogo (`transito_resumen_codigo`, `transito_resumen_placa`) y RentasV8
 *       tiene cuatro hojas de Transito, ninguna de las dos.</li>
 *   <li><b>`resumen-recaudacion`</b> publica <b>importes del libro</b> —lo cobrado por mes y fase—
 *       y `tra-panel` no dibuja ni un importe: sus cinco campos son recuentos de papeletas. Y su
 *       `abonos` <b>no es «papeletas pagadas»</b>: el propio `RecaudacionDeMultasResource` lo deja
 *       escrito —«una papeleta se puede pagar en varios abonos y un recibo puede abonar varias
 *       papeletas»—, asi que usarlo para «Canceladas» seria exactamente la cifra parecida-y-distinta
 *       que ese controlador entero existe para evitar.</li>
 * </ul>
 *
 * <p>Con la que se enciende, `tra-panel` llena <b>tres</b> de sus cinco recuentos y su desplegable
 * de ejercicio; los otros dos dicen «no publicado» y <b>nombran lo que le falta al backend</b>. El
 * reparto campo a campo, y los dos huecos con su causa, en `conectores/transito.ts`.
 *
 * <p><b>Las otras seis de `/transito/reportes/` siguen fuera</b> —`padron`, `padron-coactiva`,
 * `padron-constancias`, `record-conductor`, `record-vehicular` y el `POST` del emisor—: son de las
 * hojas que las ensenan, y las dos de record ademas <b>exigen sujeto</b> —sin licencia, documento o
 * placa contestan 422, «esto seria el padron entero con otro titulo»—.
 */
export const YA_SERVIDAS: readonly OperacionServida[] = [
  { metodo: 'GET', ruta: '/seguridad/sesion' },
  { metodo: 'GET', ruta: '/seguridad/sesion/municipalidad' },
  { metodo: 'GET', ruta: '/seguridad/modulos' },
  { metodo: 'GET', ruta: '/seguridad/accesos' },
  { metodo: 'GET', ruta: '/seguridad/sesion/permisos' },
  { metodo: 'PUT', ruta: '/seguridad/sesion/ejercicio' },
  { metodo: 'GET', ruta: '/rentas/contribuyentes' },
  { metodo: 'GET', ruta: '/rentas/contribuyentes/{id}/ficha' },
  { metodo: 'GET', ruta: '/coactiva/deudas' },
  { metodo: 'GET', ruta: '/rentas/predial/corridas/ultima' },
  { metodo: 'GET', ruta: '/rentas/predial/corridas/{corridaId}/observados' },
  { metodo: 'GET', ruta: '/rentas/beneficios' },
  { metodo: 'GET', ruta: '/licencias/ciiu' },
  { metodo: 'GET', ruta: '/licencias/funcionamiento' },
  { metodo: 'GET', ruta: '/coactiva/expedientes' },
  { metodo: 'GET', ruta: '/coactiva/expedientes/{numero}/proceso' },
  { metodo: 'GET', ruta: '/coactiva/liquidaciones-costas' },
  { metodo: 'GET', ruta: '/coactiva/prescripcion' },
  { metodo: 'GET', ruta: '/indicadores/recaudacion' },
  { metodo: 'GET', ruta: '/indicadores/trabajo-parado' },
  { metodo: 'GET', ruta: '/consultas/unificada' },
  { metodo: 'GET', ruta: '/consultas/deudas-con-beneficio' },
  { metodo: 'GET', ruta: '/consultas/constancias/no-adeudo' },
  { metodo: 'GET', ruta: '/seguridad/auditoria' },
  { metodo: 'GET', ruta: '/fiscalizacion/programas' },
  { metodo: 'GET', ruta: '/fiscalizacion/programas/{id}/muestra' },
  { metodo: 'GET', ruta: '/fiscalizacion/programas/{id}/embudo' },
  { metodo: 'GET', ruta: '/fiscalizacion/actas' },
  { metodo: 'GET', ruta: '/fiscalizacion/resoluciones' },
  { metodo: 'GET', ruta: '/fiscalizacion/resoluciones/{numero}' },
  { metodo: 'GET', ruta: '/transito/papeletas' },
  { metodo: 'GET', ruta: '/transito/papeletas/{numero}/actos' },
  { metodo: 'GET', ruta: '/transito/internamientos' },
  { metodo: 'GET', ruta: '/rentas/vehiculos/{placa}' },
  { metodo: 'GET', ruta: '/transito/reportes/resumen-papeletas' },
];

/** `/rentas/vehiculos/{placa}` → `^/rentas/vehiculos/[^/]+$`. */
function compilar(ruta: string): RegExp {
  const escapado = ruta
    .split(/(\{\w+\})/)
    .map((trozo) =>
      /^\{\w+\}$/.test(trozo) ? '[^/]+' : trozo.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'),
    )
    .join('');
  return new RegExp(`^${escapado}$`);
}

/**
 * Si esa peticion la atiende el backend de verdad.
 *
 * @param servidas la lista que rige en este entorno; el proxy pasa `YA_SERVIDAS` salvo que se
 *   le diga otra cosa
 * @param metodo verbo HTTP, en cualquier caja
 * @param rutaRelativa la ruta ya sin la raiz del sistema, empezando por `/`
 */
export function laSirveElBackend(
  servidas: readonly OperacionServida[],
  metodo: string,
  rutaRelativa: string,
): boolean {
  const buscado = metodo.toUpperCase();
  return servidas.some(
    (o) => o.metodo.toUpperCase() === buscado && compilar(o.ruta).test(rutaRelativa),
  );
}
