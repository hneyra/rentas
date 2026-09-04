# DAT-01 — Modelo lógico-físico

**El esquema vive como migraciones de Flyway**, en
[`backend/sgtm-esquema/src/main/resources/db/migration/`](../../backend/sgtm-esquema/src/main/resources/db/migration/).
Este documento las explica. **Si divergen, mandan las migraciones.**

---

## §0 — Lo primero que hay que saber

**Cinco hallazgos sobre Row Level Security**, verificados ejecutando contra PostgreSQL. Los dos
primeros vienen del proyecto SRTM, del que se hereda la estrategia: no se volvieron a descubrir
aquí, se trasladaron con su mitigación y la prueba de aislamiento los vigila. Los otros tres
salieron aquí, midiendo planes de ejecución y migraciones — y el tercero y el quinto son **el mismo
hallazgo con dos operadores distintos**: una condición que no es *leakproof* no llega al índice, y
el índice sigue ahí para que nadie lo note.

### Hallazgo 1 — Un superusuario omite RLS

`FORCE ROW LEVEL SECURITY` protege del **propietario** de la tabla, no del **superusuario**. Un
rol superusuario ve todas las filas de todas las municipalidades aunque las políticas estén
puestas.

**Consecuencias, todas obligatorias:**

- El rol de aplicación se crea `NOSUPERUSER NOBYPASSRLS`.
- La aplicación no se conecta como propietario de las tablas.
- **Una prueba de aislamiento escrita sobre la conexión por omisión de Testcontainers —que es de
  superusuario— pasa en verde sin verificar nada.** Por eso `AislamientoMultiTenantTest` crea el
  rol `sgtm_app` en su arranque y lo usa para todo, y lo demuestra: con el mismo contexto fijado,
  el superusuario ve las dos municipalidades y `sgtm_app` una.

### Hallazgo 2 — Una partición no hereda la política del padre

Una partición **no hereda** `relrowsecurity`, y consultarla directamente evade la política de la
tabla padre.

**Dos mitigaciones, y la segunda es la que cierra el hueco:**

1. RLS explícita en cada partición (`V6__rls.sql`, segundo bloque).
2. **La aplicación no tiene ningún privilegio sobre ninguna partición.** Los `GRANT` se conceden
   solo sobre las tablas padre. Por eso `V7__privilegios.sql` **no** usa
   `GRANT … ON ALL TABLES IN SCHEMA`: una partición nueva no recibe privilegios salvo que alguien
   se los conceda expresamente, y eso se ve en el diff.

### Hallazgo 3 — Bajo RLS, un `LIKE` no llega nunca al índice

Una búsqueda por prefijo escrita como `columna LIKE 'prefijo%'` **se ejecuta como recorrido
secuencial** para el rol de aplicación, exista o no un índice adecuado. Da igual la clase de
operadores del índice: no se usa.

El motivo es que `textlike` **no es *leakproof*** (`pg_proc.proleakproof = false`), y PostgreSQL se
niega a evaluar una condición que no lo sea *antes* de la política de seguridad — podría revelar,
por un mensaje de error, filas de otra municipalidad. Así que el `LIKE` se queda como `Filter`
después del recorrido, y el índice sobra.

Medido contra PostgreSQL 16 con 30 000 filas, misma tabla, mismo índice, mismos datos y el rol
`sgtm_app` sujeto a la política:

| Cómo se escribe el prefijo | Plan | Coste |
|---|---|---|
| `cod LIKE 'prefijo%'` | `Seq Scan` | 925 |
| `cod ~>=~ 'prefijo' AND cod ~<~ 'prefijp'` | `Bitmap Index Scan` | 308 |

**Mitigación.** Toda búsqueda por prefijo se escribe como un **rango** con los operadores de
`text_pattern_ops` —`~>=~` y `~<~`, los dos *leakproof*—, sobre un índice declarado con esa clase
de operadores. Expresa exactamente el mismo prefijo y sí llega al índice.

No es una peculiaridad de una consulta: le pasa a **toda** búsqueda por prefijo del sistema, y
como el plan no cambia el resultado, nada se pone rojo cuando alguien lo devuelve a `LIKE`. Por eso
hay dos pruebas gemelas en `ConsultaDeFichasTest$Volumen`: una exige que el rango use índice, y la
otra fija que el `LIKE` no lo usa y explica por qué.

El mismo razonamiento vale para cualquier operador no *leakproof* sobre una tabla con RLS. La
búsqueda por aproximación de nombres (`V11`) no está afectada: `similarity` va sobre un índice GIN
que se evalúa como filtro de todos modos.

---


### Hallazgo 4 — Una clave foránea nueva sobre una tabla con RLS no se puede validar

`ALTER TABLE … ADD CONSTRAINT … FOREIGN KEY` lanza, para validar, **una consulta** sobre la tabla.
Esa consulta queda sujeta a la política, la política lee `app.municipalidad_id`, y el migrador corre
como `sgtm_owner` **sin contexto de tenant** —correctamente: migrar no es atender la petición de
ninguna municipalidad—. El resultado es que la migración entera se cae con

```
ERROR: unrecognized configuration parameter "app.municipalidad_id"
```

No sale en la revisión: el `ALTER TABLE` se lee impecable. Sale al ejecutarlo, y apareció al añadir
`valor_referencial_vehiculo.conjunto_id` en `V17`.

Las tablas de `V1` a `V5` no lo sufren porque sus claves foráneas nacieron **antes** que las
políticas de `V6`. Le pasa a toda clave foránea que se agregue de aquí en adelante sobre una tabla
de tenant.

**Mitigación.** `NOT VALID`, y no es un atajo: es la única forma. Salta el escaneo de las filas
existentes y **no debilita nada hacia adelante** — la restricción se comprueba en cada `INSERT` y
en cada `UPDATE` desde ese momento. Lo único que queda sin verificar son las filas anteriores, y en
una tabla vacía no hay ninguna. `VALIDATE CONSTRAINT` después chocaría con lo mismo.

**Un `CHECK` no es una clave foránea, y se midió antes de suponerlo (#542).** Sobre una tabla con
`FORCE ROW LEVEL SECURITY`, en la **misma sesión sin contexto de tenant** en la que
`SELECT count(*)` muere con `unrecognized configuration parameter "app.municipalidad_id"`, un
`ALTER TABLE … ADD CONSTRAINT … CHECK (…)` **validado pasa**: su escaneo de validación no atraviesa
la política, y lo único que puede pararlo es una fila que de verdad viole la condición
(`is violated by some row`). Así que **`NOT VALID` en un `CHECK` es una decisión sobre los datos
que ya hay, no sobre RLS** — se pone cuando no se puede medir qué contienen las instalaciones
desplegadas, o cuando se sabe que alguna fila no encaja y no se va a reescribir (regla 4).

**Y el migrador tampoco puede reescribir esas filas**, por si acaso: un `UPDATE` sobre una tabla de
tenant desde una migración muere con el mismo `unrecognized configuration parameter`. «Normalizar el
vocabulario viejo en la migración» no es una salida disponible, ni siquiera cuando parece la cómoda.

**Y de ahí sale una consecuencia que `V74` (#553) tuvo que resolver, y conviene tenerla escrita.**
Si las filas viejas no se pueden reescribir, lo único que la regla 4 deja para corregir un asiento
equivocado es **reversarlo**: asentar su opuesto con `asiento_reversado_id` apuntando al original.
Y una reversión **copia** el valor del original, porque si no, no netea. Un `CHECK` sin excepción
cerraría ese camino **justo sobre las filas que más falta hace poder corregir**, y la obligación
quedaría partida en dos para siempre. Por eso `asiento_tributo_ck` se escribe como «el vocabulario,
**o** eres la reversión de otra fila», y no debilita nada: `asiento_reversado_id` sólo lo pone
`Asiento.reversionDe`, que exige un asiento ya guardado, mientras que un asiento nuevo —el único que
puede introducir una grafía nueva— lo lleva en nulo.

Del mismo hallazgo sale la otra mitad: **el `CHECK` se pone donde está la verdad, no donde está la
copia**. `saldo_proyectado` es caché reconstruible del libro, así que con el libro acotado lo está
transitivamente; acotar además la caché no añadiría protección y sí haría fallar el `UPSERT` que
`RegistrarAsiento.reproyectar` ejecuta en cada escritura, convirtiendo un defecto **detectable** en
un estado de cuenta que revienta.

**Un `CREATE UNIQUE INDEX` tampoco es una clave foránea, y también se midió (#588).** En la misma
sesión —rol dueño, `FORCE ROW LEVEL SECURITY`, sin contexto de tenant— donde `SELECT count(*)` y
`UPDATE` mueren con `unrecognized configuration parameter`, un `CREATE UNIQUE INDEX … WHERE …`
**funciona**: construir un índice lee el montón directamente y no pasa por la política. Conviene
tenerlo escrito porque el hecho anterior haría esperar lo contrario, y porque de ahí salen dos
consecuencias que sí duelen:

- **La migración no puede diagnosticar.** Como no puede consultar, no hay forma de contar los
  duplicados antes de crear el índice, ni de nombrarlos, ni de repararlos después.
- **Y el fallo no dice cuáles son.** Si alguna fila viola el índice, el error es
  `could not create unique index …` con `DETAIL: Duplicate keys exist.` **sin los valores de la
  clave**: como el dueño está sujeto a la política, PostgreSQL los oculta. El mismo fallo ejecutado
  como superusuario sí los imprime.

Un índice único **no tiene `NOT VALID`**, así que la única forma de que la migración no pueda
pararse es que su predicado excluya por construcción a las filas anteriores — es lo que `V75` hace
con `WHERE acto = 'ALTA_DEUDA'`, columna que `V68` estrenó y que en toda fila previa es nula.

### Hallazgo 5 — Bajo RLS, el operador espacial tampoco llega al índice

Es el hallazgo 3 otra vez, con otro operador, y por eso conviene leerlos como una **familia** y no
como dos casos sueltos: `predio.geometria && ST_MakeEnvelope(…)::geography` **no puede ser condición
de ningún índice** para el rol de aplicación, exista o no el índice GiST que `V61` creó.

El motivo es el mismo: `geography_overlaps` **no es *leakproof*** — y tampoco lo son
`st_intersects(geography,geography)`, `st_intersects(geometry,geometry)` ni `box_overlap` —, así
que PostgreSQL no lo evalúa antes de la política.

**Y aquí el síntoma engaña más que en el `LIKE`, porque el plan dice «Index».** Medido contra
PostgreSQL 16 con PostGIS 3.5, 60 000 lotes repartidos en dos municipalidades, como `sgtm_app`:

```
Bitmap Heap Scan on predio  (cost=329.74..3399.28 rows=404)
  Filter: (geometria && '…'::geography)
  ->  Bitmap Index Scan on predio_sector_ix  (cost=0.00..329.64 rows=30046)
        Index Cond: (municipalidad_id = current_setting('app.municipalidad_id')::bigint)
```

Usa un índice **por la condición de la política y por nada más**: lee los 30 046 predios del
inquilino para devolver unos cuatrocientos. Es literalmente la frase de #313 —«un plan que use el
índice sólo por `municipalidad_id` vuelve a leer la tabla entera y sigue diciendo *Index*»— con otro
operador, y por eso lo que hay que exigir nunca es la palabra «Index»: es que la condición **del
filtro** salga en el `Index Cond`.

`ADR-0021` había creado ese índice GiST con su motivo escrito —«sin él, "qué predios caen en esta
manzana" recorre la tabla entera»— y esa frase, medida, resulta falsa para el único rol que hace esa
pregunta. El índice sí se usa **como superusuario**, que es quien omite RLS; o sea, se usa
exactamente cuando lo prueba quien provisiona la base y nunca cuando lo usa la aplicación.

**Mitigación.** La misma forma que el hallazgo 3: decir la condición con operadores que **sí** lo
sean. `predio` gana en `V65` cuatro columnas **generadas** con el rectángulo envolvente del lote
—`marco_oeste`, `marco_sur`, `marco_este`, `marco_norte`— en `double precision`, y el marco se
escribe con `<=` y `>=` sobre ellas. Con el índice
`(municipalidad_id, marco_oeste, marco_sur, marco_este, marco_norte)`, las cuatro comparaciones **y
la condición de la política** salen juntas en el `Index Cond`:

```
Bitmap Heap Scan on predio  (cost=940.01..5097.41 rows=2905)
  ->  Bitmap Index Scan on predio_marco_ix  (cost=0.00..939.28 rows=2905)
        Index Cond: ((municipalidad_id = current_setting('app.municipalidad_id')::bigint)
                     AND (marco_oeste <= …) AND (marco_sur <= …)
                     AND (marco_este >= …) AND (marco_norte >= …))
```

**`double precision` y no `numeric`, y no es una preferencia**: `numeric_le` tampoco es *leakproof*.
Con `numeric` las cuatro columnas no llegarían al índice y no servirían para nada — que es
exactamente el modo de fallo que este hallazgo describe, reproducido por segunda vez en la misma
tabla.

**Lo que la mitigación no arregla, medido también.** PostgreSQL estima las cuatro desigualdades
**como si fueran independientes**, y no lo son: son un rectángulo. Con una sola municipalidad dueña
de toda la tabla —donde la condición de la política selecciona el 100 %— le salen 2 815 filas donde
hay unas 440, y con esa cifra prefiere el recorrido secuencial aunque el índice sea alcanzable. El
índice sigue estando ahí y la diferencia real es de unas 1 300 páginas a unas 40; a escala municipal
son milisegundos, y en cuanto hay más de una municipalidad —que es la premisa de este sistema— el
índice gana solo. Lo que sí se descartó, porque se midió: **añadir el `&&` como filtro para que
PostGIS aporte su estimador** mejora la cifra (de 2 905 a 39) y no cambia el plan, y esa estimación
tampoco es la correcta —el marco medido contiene unas 440 filas—, así que queda en una segunda copia
del mismo predicado y se retiró.

La otra salida —`ALTER FUNCTION geography_overlaps(geography,geography) LEAKPROOF`— se descartó: es
un acto de superusuario que no cabe en una migración (`sgtm_owner` a propósito no lo es), y sobre
todo es **afirmar** que ningún error de una función en C de un tercero puede revelar la fila de otra
municipalidad. `float8le` es *leakproof* en el catálogo de PostgreSQL, que es una afirmación que ya
está verificada.

## 1. Las migraciones

| Migración | Contenido |
|---|---|
| `V1__nucleo_y_catastro.sql` | Dominios, `municipalidad`, parámetros, contribuyentes y todo el catastro |
| `V2__rentas_y_cuenta_corriente.sql` | Vehículos, declaraciones, beneficios, transferencias, determinación y el libro de asientos |
| `V3__cobranza_valores_y_coactiva.sql` | Caja, recibos, tasas, convenios, valores, notificaciones y coactiva |
| `V4__sanciones_y_licencias.sql` | Infracciones, papeletas, fiscalización, licencias y anuncios |
| `V5__seguridad_y_auditoria.sql` | Módulos, accesos, grupos, usuarios, permisos, sesiones y auditoría |
| `V6__rls.sql` | Row Level Security en todas las tablas |
| `V7__privilegios.sql` | `GRANT` solo sobre tablas padre; sin `DELETE`; sin `UPDATE` en lo inmutable |
| `V8__respaldo.sql` | Registro de respaldos |
| `V9__conjuntos_sellados.sql` | Conjuntos de parámetros por ejercicio, con su sellado |
| `V10__varias_versiones_selladas.sql` | Varias versiones selladas del mismo ejercicio (ARQ-09 §3, `../srtm`) |
| `V11__busqueda_por_aproximacion.sql` | `nombre_normalizado(…)` inmutable e índice GIN de trigramas |
| `V12__responsables_solidarios.sql` | Quién responde por la deuda además del contribuyente |
| `V13__fichas_economica_bienes_y_rural.sql` | Detalle de los otros tres tipos de ficha |
| `V14__indices_de_la_consulta_de_fichas.sql` | Los tres índices de la consulta transversal (ver §0, hallazgo 3) |
| `V15__documentos_emitidos.sql` | Documentos emitidos con los datos que los generaron, para reimprimirlos idénticos |
| `V16__instalacion_de_demostracion.sql` | `municipalidad.es_demostracion`: todo documento que emita el tenant sale marcado |
| `V17__placa_normalizada_y_valores_por_conjunto.sql` | La placa es única sin su guion, y el valor referencial cuelga del conjunto sellado (ver §0, hallazgo 4) |
| `V18__tablas_de_valuacion_por_conjunto.sql` | `arancel`, `valor_unitario_edificacion` y `depreciacion` cuelgan de un conjunto de parámetros sellado (#17) |
| `V19__declaracion_jurada_ficha_y_rectificatoria.sql` | La DJ enlaza la versión de ficha vigente a su presentación, y la rectificatoria es autorreferencia (#28) |
| `V20__determinacion_detalle_por_predio.sql` | Detalle de la determinación por predio, y el predial nunca por un solo predio (NEG-05 §1, #30) |
| `V21__lectura_de_flyway_schema_history.sql` | `sgtm_app` puede leer `flyway_schema_history`: el Job de implantación espera a la migración consultándola (#158) |
| `V22__catalogo_de_infracciones_vigente.sql` | Una sola versión vigente por código de infracción (#43) |
| `V23__determinacion_de_arbitrios.sql` | Determinación de arbitrios por predio, servicio y cuota, cada servicio con su propia tasa (#31) |
| `V24__acta_de_fiscalizacion_ficha_y_vehiculo.sql` | El acta guarda de qué versión de ficha partió la visita, y la FK a `vehiculo` que faltaba (#45) |
| `V25__arancel_sin_tramo_es_unico.sql` | Un arancel sin tramo también es único por vía y conjunto: en el UNIQUE de `V18`, `NULL` no es igual a `NULL` |
| `V26__valores_correlativo.sql` | Numeración correlativa de OP/RD/RM, por municipalidad, tipo y ejercicio (#37) |
| `V27__valores_masivo.sql` | Criterio e items de una corrida de generación masiva de valores (#38) |
| `V28__notificacion_prescripcion_y_pase_a_coactiva.sql` | Acuse de notificación, pase a coactiva (`valor_movimiento`) y declaración de prescripción con su cómputo por ejercicio y sus hechos interruptivos/suspensivos (#39). Le revoca el `UPDATE` que `V7` le daba a `notificacion` |
| `V29__caja_recibo_y_abono.sql` | El punto donde entra el dinero: la serie es de la caja, el recibo no se edita —`REVOKE UPDATE`— y el cobro es idempotente (#33) |
| `V30__recibo_movimiento.sql` | Lo que le pasa a un recibo después de emitirse —duplicado y anulación como movimientos—; las columnas de estado de `V3` se retiran (#34) |
| `V31__convenio_de_fraccionamiento.sql` | El convenio de fraccionamiento de punta a punta: no se edita, su estado se deriva y exige el recibo al formalizar (#35) |
| `V32__cierre_de_turno.sql` | El cierre del turno y cómo se deja sin efecto —una reversión que reabre—; las columnas de cierre de `V3` se retiran (#36) |
| `V33__expediente_coactivo.sql` | El expediente coactivo: la carpeta que agrupa lo exigible, con su numeración, sus valores importados y su historial (#40) |
| `V34__actos_y_notificaciones_coactivas.sql` | Los actos del procedimiento (REC-1, REC-2) y sus notificaciones; `notificacion` sirve tal cual, con `objeto = 'ACTO_COACTIVO'` (#41) |
| `V35__costas_procesales.sql` | Las costas del procedimiento coactivo, liquidadas acto por acto según el arancel aprobado (#42) |
| `V37__licencia_de_funcionamiento.sql` | La licencia de funcionamiento (#44): retira de `licencia_funcionamiento` las columnas de estado que decían `VIGENTE` para siempre y el `resolucion` de texto libre; exige el recibo y el documento emitido; agrega `licencia_movimiento` —de donde se deriva el estado— y `licencia_correlativo`; le pone al catálogo `ciiu` su sección, su riesgo de ITSE y su traza; y revoca el `UPDATE` sobre la licencia y sus duplicados |
| `V39__liquidacion_de_fiscalizacion.sql` | La liquidación de fiscalización, su reliquidación —que referencia a la anterior— y su historial (#49) |
| `V41__descargos_internamiento_y_resoluciones.sql` | Descargos, internamiento vehicular y resoluciones de gerencia —ordinaria primero, sancionadora después— (#50) |
| `V43__licencia_de_edificacion.sql` | La licencia de edificación: el FUE completo, por partes; **retira** `valor_obra`, que con una celda ausente habría valido cero (#48) |
| `V45__anuncios_y_propaganda.sql` | Anuncios y propaganda, con la deuda por la tasa generada al autorizar; retira lo que mentiría, como `V30`–`V37` (#51) |
| `V47__valores_masivos_de_papeletas_y_constancias.sql` | Los valores masivos de papeletas y los reportes de sanciones (#53): `papeleta_masivo` —el criterio congelado de una corrida, con la `fecha_criterio` a la que se evalúa la deuda de cada candidato— y `papeleta_masivo_item`, con `papeleta_valor_unico_uq`, el índice único **parcial** que garantiza un valor por papeleta en toda la vida del padrón; `constancia_libre`, con la fecha a la que se verificó que el vehículo no debía nada; y los índices de los padrones y resúmenes, incluido `papeleta_placa_prefijo_ix` con `text_pattern_ops`, porque el resumen por iniciales busca por rango y no con `LIKE`. **No hay ninguna tabla de correlativos**: el número de cada resolución de multa sale de `valor_correlativo` (`V26`) |
| `V49__transferencia_a_rentas.sql` | La transferencia a rentas y su resolución de determinación: la frontera delicada de ARQ-01 §3.5 hecha esquema (#52) |
| `V51__certificados_y_padrones.sql` | Certificados de numeración y zonificación —acto administrativo nuevo, con numeración y vigencia propias— y lo que los padrones de licencias necesitan del motor (#54) |
| `V53__indice_de_construccion_por_ficha.sql` | El índice por ficha que le faltaba a `construccion`: sin él, «las construcciones de esta ficha» es un `Seq Scan` (#313) |
| `V54__declaracion_jurada_correlativo_y_actos.sql` | La declaración jurada como acto: numeración con correlativo propio, unicidad de la rectificatoria y qué columnas puede tocar la aplicación (#365, ADR-0015 §3) |
| `V55__tablas_de_valuacion_nacionales.sql` | Las tres tablas de valuación pasan a NACIONALES: `municipalidad_id` nulo, se cargan una vez para todas (D-13, ADR-0017, #188) |
| `V56__determinacion_detalle_valuo_exonerado.sql` | El detalle por predio dice también qué parte del autovalúo **no** está afecta, para que la ponderación se reconstruya (#395) |
| `V57__depreciacion_por_uso_de_la_edificacion.sql` | La tabla de depreciación son **cuatro** tablas, una por uso de la edificación: `uso` entra en la clave y «más de 50 años» entra sin tope (H-15, #188) |
| `V65__marco_del_predio.sql` | El rectángulo envolvente del lote, en cuatro columnas generadas, y su índice: es lo único que llega al índice bajo RLS, porque el operador espacial no es *leakproof* (ver §0, hallazgo 5; #536) |
| `V77__causal_de_la_baja.sql` | La causal de la baja de deuda como columna con vocabulario cerrado: hasta entonces viajaba **dentro de la observación** y el libro sabía *qué* se hizo (`acto`, `V68`) y no *por qué* (#684) |
| `V75__idempotencia_del_alta_de_deuda.sql` | `asiento_alta_unica_uq`: un alta de deuda por obligación, documento de sustento y concepto. Índice único **parcial** sobre `acto = 'ALTA_DEUDA'` —la columna que `V68` estrenó—, con `COALESCE` en las tres columnas nulables y `ejercicio` dentro por ser la clave de partición (#588) |

La numeración salta —no hay `V36`, `V38`, `V40`, `V42`, `V44`, `V46`, `V48`, `V50` ni `V52`— y no
es un error: hoy, con `V77`, hay **64** migraciones, y la lista viva es el propio directorio
`backend/sgtm-esquema/src/main/resources/db/migration/`.

Los roles se crean **antes**, con `db/roles/crear-roles.sql`, que no es una migración: las
políticas de `V6` los nombran, y un rol no puede crearse a sí mismo.

## 2. Dominios

Las restricciones viajan con la columna, para que no dependan de que alguien las repita.

| Dominio | Tipo | Restricción |
|---|---|---|
| `dinero` | `numeric(15,2)` | Escala ratificada y redondeo `HALF_UP` ([ADR-0018](../30-arquitectura/adr/ADR-0018-el-redondeo-decidido.md)); el del cierre de caja sigue abierto (D-03d) |
| `monto_calc` | `numeric(18,6)` | Cálculos intermedios, antes de redondear |
| `alicuota` | `numeric(7,4)` | 0 ≤ v ≤ 100 |
| `porcentaje` | `numeric(7,4)` | 0 < v ≤ 100 |
| `area_m2` | `numeric(12,2)` | v ≥ 0 |
| `ejercicio` | `smallint` | 1990 ≤ v ≤ 2100 |
| `cod_catastral` | `varchar(25)` | Solo dígitos, 18–25 posiciones. La longitud exacta es **D-10** |

**Ningún importe es de coma flotante**, ni en la base ni en el código (RNF-055).

## 3. Clasificación de tablas

Toda tabla es exactamente una de tres cosas, y la prueba de aislamiento falla si aparece una sin
clasificar:

| Clase | Cuáles | RLS |
|---|---|---|
| **De tenant** | Todas las de negocio (hoy, con `V57`: 113): llevan `municipalidad_id NOT NULL` | Política con `USING` y `WITH CHECK` |
| **De catálogo** | Seis: `municipalidad`, `parametro_tributario`, `respaldo` (`V8`) y las tres de valuación nacionales de `V55` —`valor_unitario_edificacion`, `depreciacion`, `valor_referencial_vehiculo`—. La lista normativa es `TABLAS_DE_CATALOGO`, en el código de la prueba de aislamiento | Política propia, enumerada en el código de la prueba |
| **Exenta** | `flyway_schema_history` | Sin RLS; desde `V21`, `sgtm_app` puede leerla |

## 4. Las piezas centrales

### 4.1 Contribuyente y su código único

`contribuyente` lleva `codigo_contribuyente` único por municipalidad: es el «código único» del
manual, con el que se enlazan predios, vehículos, papeletas y licencias. El documento de identidad
también es único por municipalidad.

`domicilio` lleva vigencias, con un índice parcial que garantiza **un solo domicilio fiscal
vigente** por contribuyente. `contacto` unifica teléfonos, correos, gestores y contactos.

### 4.2 Catastro: la ficha se versiona, no se sobrescribe

Es la exigencia del manual (cap. 2 §Actualización del Catastro), y aquí es el modelo:

- `ficha_catastral` lleva `version`, `vigencia_desde/hasta`, `origen`, `documento_origen`,
  `usuario_registro` y **`observacion NOT NULL`**.
- Un índice parcial garantiza **una sola ficha vigente** por predio y tipo.
- `construccion` guarda las categorías **A–J** por cada una de las **siete** características del
  formulario del manual (muros, techos, pisos, puertas, revestimientos, baños, instalaciones);
  **el valor de cada letra no está aquí**, sino en `valor_unitario_edificacion`, versionado por
  ejercicio.

  **Y las siete de la ficha no son las del cuadro** (`V59`, #436). El Cuadro de Valores Unitarios
  vigente publica **tres** partidas de apreciación exterior —muros y columnas, techos, y puertas y
  ventanas—, así que `valor_unitario_edificacion.partida` y `edificacion_estructura.partida` admiten
  esas tres. Las siete de `construccion` vienen del **formulario del manual** (`V1`: «manual,
  cap. 2 §Caract. Construccion»), describen una edificación y no le ponen precio; un catastro puede
  registrar más características de las que la valorización usa. Lo que `V1` afirmaba —«son las dos
  mitades de la misma matriz»— resultó no ser cierto.

  La **`J`** llegó con `V58`: el Anexo I.4 (Selva) tiene diez categorías, no nueve.

`titularidad` tiene un **trigger diferido** que exige que los porcentajes vigentes de un predio
**no excedan** 100 —no que sumen exactamente 100—. Diferido porque una transferencia cierra una
titularidad y abre otra en la misma transacción, y en el intermedio la suma no cuadra.

> **Por qué «no excede» y no «suma 100».** Es la regla que el SRTM del MEF valida, heredada
> verificada de [`../srtm` DAT-02 §4.2](../../../srtm/docs/40-datos/modelo-logico-fisico.md)
> (allí es D-36). Un padrón real tiene predios con titularidad parcialmente identificada;
> exigir que sume 100 obligaría al operador a **inventar un titular para cuadrar**, que es peor
> que registrar el 60 % que efectivamente se conoce. Queda abierto si el resto del autovalúo se
> determina a alguien o no se cobra.

Complemento: un `CHECK` exige que el `PROPIETARIO_UNICO` tenga porcentaje 100 —lo es por el
total, su porcentaje no se declara—.

### 4.3 Determinación: reproducible o no sirve

`determinacion` guarda `conjunto_id` —con qué conjunto de parámetros se calculó— y
`reglas_aplicadas`. Sin eso, recalcular un ejercicio pasado no da el mismo resultado y el sistema
no sirve como prueba de nada (ADR-0007). Está **particionada por ejercicio**.

> ⚠ **Falta el detalle por predio, y la tabla sola invita al error.** El predial se determina
> **por contribuyente, no por predio**: los tramos progresivos se aplican al conjunto de sus
> predios, y un contribuyente con tres predios pequeños puede caer en un tramo superior
> (`../srtm` NEG-05 §1, confirmado contra el manual M02 del MEF). Hoy `determinacion` admite
> `predio_id`, así que nada impide emitir una fila por predio —que es exactamente el error
> sistemático **a la baja en todo el padrón**—.
>
> Lo que falta es la grilla de «detalle de los predios» dentro de una determinación, con el
> aporte de cada uno a la base: `autovalúo → × % actualización → × % propiedad →
> base_imponible_predio`, y `base_contribuyente = Σ base_imponible_predio`. Se modela junto con
> la primera regla de cálculo, no antes: `% actualización` es uno de los cuatro factores que
> NEG-05 §0.1 marca **sin fuente identificada**.

### 4.4 Cuenta corriente: solo se agrega

`cuenta_corriente_asiento` es el libro de ADR-0006. `CARGO` o `ABONO`, con `concepto`
—insoluto, reajuste, interés, gasto, pago, compensación, anulación, condonación, ajuste,
fraccionamiento— y `fase` —ordinaria, valor, coactiva, convenio—.

- La aplicación tiene `SELECT` e `INSERT`. **Nada más.**
- `asiento_reversado_id` enlaza la corrección con lo corregido.
- Los conceptos que alteran deuda sin cobro (`ANULACION`, `CONDONACION`, `AJUSTE`) exigen
  `motivo`, por `CHECK`.
- `referencia_externa` es cómo entran papeletas y licencias **sin** que el libro dependa de esos
  contextos: no hay clave foránea a propósito (ARQ-01 §4 regla 2).
- `acto` (`V68`, #601) dice **por qué** existe la fila cuando el libro lo sabe: `ALTA_DEUDA` o
  `BAJA_DEUDA`. Nulo no es «se ignora», es «no nació de un alta ni de una baja».
- `causal` (`V77`, #684) dice **por qué se dio de baja**: las seis del desplegable de RF-044, letra
  por letra salvo la tilde y el espacio. Hasta entonces viajaba dentro de la observación —texto
  libre de quien atiende—, así que RF-045 no podía contestar «enséñame las bajas por prescripción»
  y «PRESCRIPCION DECLARADA», «prescripción declarada» y «prescrita s/ Res. 123-2026» eran la misma
  causal en tres cadenas distintas. Son **dos cosas y siguen siéndolo**: la causal es el sustento
  jurídico del acto y `motivo` es la observación del usuario (regla 10). Tres `CHECK`: el
  vocabulario y «sólo una baja tiene causal» van **validados** —la columna nace y todas las filas
  existentes quedan en nulo—, y «toda baja nueva declara la suya» va **`NOT VALID`**, porque una
  instalación en marcha ya tiene bajas escritas por `V68` y un `ALTER TABLE` validado fallaría con
  «is violated by some row» dejándola sin migrar (la lección de `V64`). Esas filas viejas **no se
  pueden reparar** —el libro no admite `UPDATE` (`V7`, regla 4) y el migrador no puede reescribir
  una tabla con `FORCE ROW LEVEL SECURITY` (§0, hallazgo 4)—: siguen saliendo en la relación sin
  filtro y desaparecen al filtrar por una causal concreta.
- **Un alta de deuda no se puede registrar dos veces** (`asiento_alta_unica_uq`, `V75`, #588). La
  clave es la obligación —las mismas seis columnas de `saldo_uq`— más `documento_origen` y
  `concepto`, y el índice es parcial sobre `acto = 'ALTA_DEUDA' AND asiento_reversado_id IS NULL`.
  Lo garantiza el índice y no un `if`: entre leer y escribir cabe otra petición. La **baja** queda
  fuera a propósito, porque ya tiene su guarda —`verificarQueNoExcedeLaDeuda`, que el alta no
  tiene—; y la reversión también, porque `Asiento#reversionDe` copia el acto y el asiento que
  corrige a otro no puede quedar bloqueado por el índice que protege al original.
- `saldo_proyectado` es caché reconstruible. Si diverge, manda el libro.
- `tributo` es un **vocabulario cerrado** desde `V74` (#553), con los siete que `determinacion`
  ya declaraba en `V2` más `MULTA_TRIBUTARIA`, `MULTA_TRANSITO`, `MULTA_ADMINISTRATIVA`,
  `CONVENIO` y `COSTAS PROCESALES`. Nació como `varchar(20)` sin restricción, y como
  `ClaveDeSaldo` compara ese texto por igualdad exacta, **dos grafías del mismo tributo eran dos
  obligaciones distintas**: `DeterminarArbitrios` asienta `ARBITRIO` y
  `ejemplos/deuda.csv` sembraba `ARBITRIOS`, así que el filtro «Arbitrios» de la consulta
  unificada no encontraba la deuda sembrada. El vocabulario vive en un solo sitio,
  `pe.gob.sgtm.cuentacorriente.TributoDelLibro`, y es API pública del módulo porque los siete
  contextos que asientan lo necesitan.
- **`saldo_proyectado.tributo` no lleva `CHECK`, y es deliberado**: es caché derivada del libro
  —su tributo sólo puede venir de un asiento—, así que acotarla no añade protección y sí
  impediría reproyectar una obligación con grafía anterior a `V74`, que es exactamente la que hay
  que poder seguir leyendo. `RegistrarAsiento.reproyectar` hace ese `UPSERT` en **cada**
  escritura.

### 4.5 Sanciones: el desglose se guarda, no se recalcula

`papeleta` guarda los seis importes del manual: base imponible, porcentaje de la infracción,
importe de la infracción, porcentaje realmente a cobrar, importe a pagar e importe con beneficio.
Se guardan **todos** porque explicarle el cobro al contribuyente es parte del requisito, y
recalcularlos meses después con otros parámetros daría otra cifra.

Lo que `V4` **no** guardaba, y `V41` (#50) agrega, es **a quién se le cobra**: `papeleta.obligado_id`.
`RegistrarPapeleta` (#46, #47) recibía el obligado por la firma, asentaba el cargo contra él y no lo
guardaba en ninguna parte. La consecuencia no se ve hasta que un descargo se declara fundado: hay que
dar de baja **esa misma** obligación del libro, y no hay forma de saber contra cuál se asentó —
`infractor_id`, `propietario_id` y `contribuyente_id` son tres candidatos y ninguno es la respuesta,
porque el manual permite cobrarle al propietario aunque condujera otro—. Es también el «Obligado» que
la resolución de gerencia imprime.

Y el escalado del manual, también con `V41`: `descargo` pierde `resultado`, `resolucion` y
`fecha_resolucion` —el fallo escrito dentro del escrito que otro presentó—, que pasan a
`resolucion_gerencia`; e `internamiento` pierde `fecha_salida`, porque liberar un vehículo es un acto
con su acta, su recibo de custodia y quien lo retira, no una fecha rellenada encima del ingreso. Los
dos estados —el del recurso y el del vehículo— se **derivan**, como el del recibo y el del turno.

La **tarifa** de la custodia no está en `internamiento`: solo el **código** del concepto del TUPA. La
tarifa vive en `tasa` con su vigencia (regla 5, ADR-0007), y copiarla la pondría en dos sitios.

### 4.6 Seguridad y auditoría

El modelo del manual, completo: `modulo_sistema`, `acceso` (opción de menú o política), `grupo`,
`usuario`, `miembro`, `permiso` con los siete privilegios, `sesion`.

`auditoria` lleva usuario, equipo, IP, fecha, tabla, clave, operación y **`observacion NOT NULL`
con al menos cinco caracteres no vacíos**: sin observación la inserción falla y la operación
completa se deshace (ADR-0008). Está particionada por ejercicio y la aplicación solo puede
`SELECT` e `INSERT`.

## 5. Particionado

Cinco tablas, por lista sobre `ejercicio`: `determinacion` y `cuenta_corriente_asiento` (`V2`),
`auditoria` (`V5`), `determinacion_predio_detalle` (`V20`) y `determinacion_arbitrio` (`V23`).
Hoy con particiones 2026 y 2027.

**Al crear una partición nueva:**

1. Repetir el bloque de RLS explícita de `V6__rls.sql`.
2. **No concederle ningún privilegio.**

La prueba de aislamiento falla si aparece una partición sin RLS o con privilegios.

## 6. Sin `DELETE`, y en dos tablas sin `UPDATE`

La aplicación **no tiene `DELETE` en ninguna tabla** (RNF-051). Las consecuencias de diseño están
en el esquema, no en una convención:

- Sacar a un usuario de un grupo es `miembro.activo = false`, con `fecha_baja` y `usuario_baja`.
- Quitar un giro de una licencia es `licencia_giro.activo = false`.
- Cancelar una licencia de funcionamiento es **agregar** una fila a `licencia_movimiento` con su motivo y la resolución que la sustenta (`V37`, #44). Las columnas `estado`, `fecha_cancelacion` y `motivo_cancelacion` que `V4` había puesto en `licencia_funcionamiento` se **retiraron**, por lo mismo que las de `recibo` y las de `cierre_caja`: dirían `VIGENTE` para siempre. El estado se deriva de sus movimientos **y de la fecha a la que se pregunta** —una licencia temporal vence, y un padrón con fecha de corte de junio tiene que seguir diciendo lo que decía en junio—.
- Anular un recibo es **agregar** una fila a `recibo_movimiento` con su motivo, su importe y su
  turno, por `CHECK` (`V30`, #34). Las columnas `estado`, `fecha_anulacion`, `usuario_anulacion` y
  `motivo_anulacion` que `V3` había puesto en `recibo` se **retiraron** ahí mismo: desde que `V29`
  revocó el `UPDATE` sobre la tabla, `estado` decía `EMITIDO` para siempre —también en un recibo
  anulado—, y una columna que miente es peor que una columna que falta. El estado de un recibo se
  deriva de sus movimientos.
- Anular, reformular o quebrar un convenio exige `fecha_estado` y `motivo_estado`, por `CHECK`.
- Corregir el cierre de una caja es **agregar** una reversión a `cierre_turno`, que lo deja sin
  efecto y **reabre** el turno (`V32`, #36). Las columnas `estado`, `total_efectivo`, `total_otros`,
  `cantidad_recibos`, `fecha_cierre` y `usuario_cierre` que `V3` había puesto en `cierre_caja` se
  **retiraron**, por lo mismo que las de `recibo`: dirían `ABIERTO` para siempre. El estado del
  turno se deriva de sus movimientos, y su arqueo se congela medio de pago por medio de pago en
  `cierre_turno_detalle` —en dos cajones no se puede conciliar con el banco—.

Y el libro de asientos, la auditoría y la traza de cambio de número de papeleta tampoco admiten
`UPDATE`. Desde `V28` (#39), tampoco `notificacion`, `valor_movimiento` ni `prescripcion`: una
diligencia de notificación, un pase a coactiva y una declaración de prescripción son actos
administrativos, no el estado de un proceso interno —a diferencia de `valor_correlativo` (`V26`) y
`valor_masivo_item` (`V27`), que sí se actualizan en el sitio por ser infraestructura de un proceso—.
Un intento no hallado no se corrige: se vuelve a diligenciar, con otra fila.

Desde `V29` (#33), tampoco `recibo` ni `recibo_detalle` —el contribuyente se lleva el papel—, y desde
`V30` (#34) tampoco `recibo_movimiento`: si el recibo ya no se puede tocar, la salida con rodeo es
corregir la fila que dice si está anulado, y es la misma pérdida por otra puerta.

Desde `V32` (#36), tampoco `cierre_turno` ni `cierre_turno_detalle`: un arqueo es un acto firmado
contra el que se concilia el depósito, y si la cifra declarada se puede reescribir, el descuadre
desaparece justo cuando alguien lo está buscando.

Desde `V37` (#44), tampoco `licencia_funcionamiento` ni `licencia_duplicado`: la licencia es un acto
administrativo que el titular **cuelga en la pared de su establecimiento**, y corregirla en la base
deja al papel y al sistema diciendo cosas distintas. Aquí el `REVOKE` **sí se pudo**, al revés que
con `cierre_caja`, y no por casualidad: el ordinal del siguiente duplicado se serializa con
`licencia_duplicado_uq` y no con un `SELECT … FOR UPDATE` sobre la licencia, precisamente para que el
privilegio se pudiera retirar. `ciiu` y `licencia_giro` **conservan** el `UPDATE`: el catálogo se
corrige, y quitar un giro de una licencia es ponerle `activo = false`.

Desde `V41` (#50), tampoco `descargo`, `resolucion_gerencia`, `internamiento` ni
`internamiento_movimiento`. La resolución es el caso claro y ya conocido: **se notifica**, y el
administrado se lleva el papel; corregirla en la base deja al papel notificado y al sistema diciendo
cosas distintas, y quien tenga el papel gana la discusión. Una resolución equivocada se deja sin
efecto con otra, y las dos quedan. El descargo es el escrito que otro firmó y presentó. Y el
internamiento, la constancia de que un vehículo estuvo retenido y devengó custodia.

Desde `V47` (#53), tampoco `constancia_libre` ni `papeleta_masivo`. La constancia es otra vez el caso conocido: **se entrega al administrado**, y como lo que acredita es «al día tal no debía nada», cambiarle la fecha cambia lo que acredita. El criterio de la corrida tiene su propio motivo: `fecha_criterio` congela a qué día se evaluó la deuda y el plazo de cada candidato, y moverla después de generar dejaría la corrida diciendo que emitió con un criterio que no es el que usó. `papeleta_masivo_item` **sí** conserva el `UPDATE`, por lo mismo que `valor_masivo_item` desde `V27`: su estado es la marca de progreso de un proceso interno, no un acto administrativo.

Desde `V49` (#52), tampoco `resolucion_determinacion` —la transferencia a rentas de un resultado de
fiscalización y la resolución que la materializa—. Es la décima vez por el mismo camino y la única
con **tres** efectos colgando de la misma fila: el papel notificado, la versión de ficha catastral
que quedó inscrita y el cargo que se asentó en el libro. Editarla deja a los cuatro diciendo cosas
distintas, y la que se cobra en ventanilla es la del libro. Borrarla es peor de lo que parece: no
devuelve el sistema a como estaba —la ficha nueva sigue inscrita, la anterior sigue cerrada y el
cargo sigue en el libro—, solo desaparece el acto que los explica.

### La frontera delicada: cómo un dato de fiscalización llega al padrón (`V49`, #52)

`ARQ-01` §3.5 lo llama la frontera delicada del sistema, y `V49` es donde vive. Hasta la
transferencia, todo lo que `fiscalizacion` registra son **copias**: el acta guarda el área medida en
campo y la versión de ficha que regía el día de la visita (`V4`/`V24`), y la liquidación guarda el
contraste hallado/declarado (`V39`). Nada de eso es el dato oficial.

`resolucion_determinacion` es **una sola tabla para el acto y su papel**, y no dos. La resolución de
determinación es el acto administrativo que determina de oficio; transferir es su efecto sobre el
padrón. Separarlas habría producido dos filas 1:1 que nadie puede desincronizar sin que la otra
mienta, y una pregunta sin respuesta: cuál de las dos se notifica.

**No es un `valor` de tipo `RD`, y se comprobó antes de crear la tabla.** Un valor *formaliza* deuda
ya asentada —`RegistrarValor` (#37) la lee del libro y le mueve la fase de `ORDINARIA` a `VALOR`— y
esta resolución es el acto que la *asienta*: emitirla como valor exigiría que la deuda existiera
antes del acto que la determina. Y mientras la liquidación siga saliendo sin importes (#198) ningún
valor se podría emitir, y con él se caería también la mitad de la transferencia que **no** depende
de las cifras: inscribir en catastro la estructura hallada. `D-02a` se cerró el 2026-08-25, pero lo
que #198 sigue esperando es la tabla de valuación que todavía no se puede cargar —el cuadro de
valores unitarios, H-14 de [GOB-03](../00-gobierno/plan-de-desbloqueo-D-02.md); la depreciación ya
se carga desde `V57`— y el `% actualización` de `D-11`. Las dos
cosas conviven: una vez asentado el cargo, `valores` lo formaliza como RD por el camino ordinario.

`ficha_catastral` **no necesitó ni una columna**: `V1` ya la nació con `origen` —que admite
`FISCALIZACION`—, `documento_origen`, `usuario_registro` y `observacion`. Se miró antes de escribir
un `ALTER`.

Y `resolucion_determinacion_liquidacion_uq` es lo que impide transferir dos veces el mismo resultado.
Va en la base y no en un `if` porque dos peticiones simultáneas pasan las dos por cualquier
comprobación de Java. Medirlo, en cambio, exige cuidado: el número del papel sale de
`documento_emitido`, cuyo correlativo es un `count(*) + 1`, así que diez transferencias simultáneas
chocan antes en `documento_numero_uq` —el resultado es correcto, pero por un motivo que no es el que
se quiere medir, el mismo hueco que #44 destapó con `licencia_duplicado_uq`—. Por eso el AC se
comprueba en dos pruebas: una de extremo a extremo que mide el resultado, y otra del repositorio que
inserta diez filas que solo comparten `liquidacion_id`.

### El `REVOKE` que no se puede hacer: `cierre_caja`

`V32` iba a revocarle también el `UPDATE` a `cierre_caja` —el turno se abre una vez y no se edita—.
**No se puede, y se descubrió ejecutándolo:** en PostgreSQL, `SELECT … FOR UPDATE` exige el
privilegio de `UPDATE` sobre la tabla (también `FOR NO KEY UPDATE` y `FOR SHARE`; no hay forma de
bloquear una fila con solo `SELECT`). Y esa fila es **el punto de serialización de la ventanilla**
desde `V29` §2: bloquear el turno es lo que ordena dos cobranzas simultáneas de la misma caja.
Revocarlo no habría hecho el turno inmutable —habría dejado la caja sin poder cobrar—, y el síntoma
no se parece a su causa: `BadSqlGrammarException` en la primera cobranza, porque el `SQLSTATE`
`42501` cae en la clase 42.

`cierre_caja` es por eso la **primera tabla del esquema cuya inmutabilidad no puede apoyarse en el
privilegio**. La sostienen dos cosas: que las columnas que se actualizarían ya no existen, y que la
tabla está en `TABLAS_INMUTABLES` del revisor de código fuente con su muestra que lo viola, de modo
que un `UPDATE cierre_caja SET` en `src/main` rompe el build.

## 7. Índices

Todo índice selectivo empieza por `municipalidad_id`: la política RLS añade esa condición a cada
consulta, y un índice que no la lleve primero no se usa (RNF-064).

### 7.1 La página de omisos, medida (#561, `V69`)

`GET /api/v1/fiscalizacion/omisos` se midió porque #561 lo reportó en **8,5 s por página** sobre el
padrón real de Catacaos, con el coste **independiente del tamaño de página** (`tamano=1` cuesta lo
mismo que `tamano=200`). Lo que sigue es la medida, que es lo que el issue pide antes que ningún
arreglo (precedente de #313).

**Cómo se tomó.** PostgreSQL 16.10, conexión de **`sgtm_app`** con la política RLS activa —no la del
superusuario, que la omite, ni la de `sgtm_owner`, que con `FORCE ROW LEVEL SECURITY` queda sujeto
pero es dueño de las tablas—, `SET LOCAL app.municipalidad_id` dentro de una transacción, y el
padrón de Catacaos —**14 422 predios**— sembrado en **dos** municipalidades (28 844 predios, 28 844
fichas, 28 844 titularidades, 2 884 declaraciones juradas). Cada sentencia se midió en sus dos
formas: con los valores a la vista —el *plan personalizado*, el de las primeras ejecuciones— y con
`plan_cache_mode = force_generic_plan`, que es lo que un pool obtiene a partir de la quinta
ejecución de la misma sentencia sobre la misma conexión.

**Lo primero que la medida corrige es el diagnóstico del propio issue.** #561 se abrió el 2026-09-01
a las 06:56 UTC y señalaba `CatastroRepositoryJdbc.padron(...)` (`PADRON_DESDE`, con su `JOIN
titularidad` interno); **#545 sustituyó esa consulta ese mismo día**. La detección ya no pasa por
ahí: hoy son tres sentencias por página —el conteo y la página de `DeteccionRepositoryJdbc`, más la
lectura de titulares de `CatastroRepositoryJdbc.titularesDeVarios`—. Medidas las cuatro formas
(la de antes de #545 incluida) sobre el mismo padrón y la misma máquina, **ninguna reproduce los
8,5 s**: la más cara son 125 ms. La cifra que sí sobrevive al cambio de máquina es la de páginas
tocadas, y por ella se decide.

| Sentencia | Plan personalizado | Plan genérico | Buffers | Filas descartadas |
|---|---|---|---|---|
| Conteo de la detección | 83,5 ms | 105,4 ms | 30 871 | — |
| Página de la detección (`tamano=20`) | 0,58 ms | 0,45 ms | 161 | — |
| Titulares de la página (**antes de `V69`**) | 23,9 ms | 9,7 ms | 242 | **14 402 para devolver 20** |
| Titulares de la página (**con `V69`**) | 0,24 ms | — | 45 | 0 |
| *(contraste)* La consulta anterior a #545 | 62,1 ms | 75,4 ms | ~600 | — |

Sumadas las cuatro sentencias que la petición ejecuta —el conteo, la página, los titulares y la
resolución de sus nombres en `contribuyente`, que ya entraba por `contribuyente_pk` con las dos
condiciones en el `Index Cond`—, `?tamano=20` sin filtros cuesta **~85 ms** con `V69` sobre los
14 422 predios, frente a los ~110 ms de antes. La lectura de titulares baja de 242 páginas tocadas
a 45 —236 y 39 si se cuenta sólo el nodo que recorre la tabla—. De esos ~85 ms, casi todo es el
conteo, y §7.2 le quita el 98 % de lo que lee.

**El conteo es O(padrón) por naturaleza.** Cuenta el padrón: lee sus 14 422 predios y
sus 14 422 fichas vigentes, y de sus 30 871 buffers **30 295 son el `LEFT JOIN LATERAL`** que busca
la declaración de cada predio —14 422 descensos al índice `dj_ejercicio_predio_ix`, con su nodo
`Sort` montado y desmontado una vez por predio—. Es exactamente el coste que no depende del tamaño
de página. Se midieron dos salidas y **aquí no se toma ninguna** —una tercera, que no cambia el plan
sino la sentencia, se midió después y **sí se toma**: es §7.2—:

- **Reescribir el `LATERAL` como `DISTINCT ON` sin correlación** baja el conteo —medido con el
  filtro `condicion` puesto, que es el caso caro porque conserva el segundo `JOIN` de fichas— de
  31 426 buffers a **1 190**, y de 149,8 ms a 45,5 ms. Pero la página, que hoy se corta a los 21 predios leídos, pasa
  a materializar todas las declaraciones del ejercicio antes de poder devolver veinte: en la medida
  ya aparece `Rows Removed by Join Filter: 27399`. Cambia una consulta O(padrón) por dos, y la
  segunda es la que se ejecuta siempre.
- **Ensanchar `dj_ejercicio_predio_ix` a `(municipalidad_id, ejercicio, predio_id,
  fecha_presentacion DESC, id DESC)`** quita el `Sort` de los 14 422 ciclos y baja el conteo de
  ~80 ms a ~60 ms (−20 %), sin cambiar los buffers. Es un 20 % sobre la parte que ya está acotada
  por debajo de los 600 buffers de las dos lecturas de tabla, y obliga a reemplazar un índice que
  `V39` creó para otra pregunta.

**Lo que sí se arregla es la tercera sentencia**, y es la que tiene la forma que el hallazgo 3 de §0
describe: el plan **dice «Index»** y lee la titularidad entera del inquilino, porque su única
condición de índice es la de la propia política.

```
-- antes de V69, como sgtm_app y con RLS activa
Sort  (actual rows=20)
  ->  Bitmap Heap Scan on titularidad  (actual rows=20)
        Recheck Cond: (municipalidad_id = current_setting('app.municipalidad_id')::bigint)
        Filter: (vigencia_desde <= '2026-09-01' AND (vigencia_hasta IS NULL OR ...)
                 AND predio_id = ANY ('{1,...,20}'))
        Rows Removed by Filter: 14402          <- 236 buffers para devolver 20 filas
        ->  Bitmap Index Scan on titularidad_pk  (actual rows=14422)
              Index Cond: (municipalidad_id = current_setting(...)::bigint)

-- con V69
Sort  (actual rows=20)
  ->  Index Scan using titularidad_predio_ix on titularidad  (actual rows=20)
        Index Cond: ((municipalidad_id = current_setting(...)::bigint)
                     AND (predio_id = ANY ('{1,...,20}'))
                     AND (vigencia_desde <= '2026-09-01'))
        Filter: ((vigencia_hasta IS NULL) OR (vigencia_hasta >= '2026-09-01'))
```

`titularidad` tenía tres índices y ninguno respondía «de quién es este predio **a una fecha**», que
es la única forma en que este sistema pregunta por la titularidad (regla 9):
`titularidad_predio_vigente_ix` es **parcial** —`WHERE vigencia_hasta IS NULL`— y PostgreSQL no
puede usar un índice parcial cuando el `WHERE` de la consulta no implica su predicado; el de la
vigencia a una fecha admite a propósito las cuotas cerradas. `V69` añade el equivalente no parcial.
Las tres condiciones entran en el `Index Cond` porque `int8eq` y `date_le` **son** *leakproof*
—el reverso de los hallazgos 3 y 5 de §0—, y eso lo comprueba `TitularesEnElIndiceTest` leyendo
`pg_proc`.

**Y el padrón pequeño no lo paga**: con 25 predios, la misma consulta pasa de 13 a 54 páginas
tocadas —el motor prefiere veinte descensos al índice antes que recorrer veinticinco filas— y de
0,283 ms a 0,332 ms. Las dos formas están muy por debajo del milisegundo, y ninguna lee el padrón.

### 7.2 El conteo de esa misma página: lo que se contaba y no se usaba (#561, sin migración)

§7.1 cerró la tercera sentencia y dejó escrito que *«el conteo es O(padrón) por naturaleza y no se
toca»*, con dos salidas medidas y descartadas. Volviendo a medirlo apareció una tercera que no
cambia el **plan** sino la **sentencia**, y ésa sí se toma.

**Lo que el conteo hacía y no usaba.** El conteo se armaba con la misma subconsulta con la que se
pinta la página, y esa subconsulta trae la declaración de cada predio para poder **escribir la
columna «Condición»**. Un `count(*)` no escribe columnas. Cuando nadie filtra por condición —que es
como se abre la pantalla, y es el caso del AC 2 de #561— los dos `JOIN` de la declaración son
trabajo cuyo resultado se descarta entero, y además **no pueden cambiar el número de filas**:

- el `LEFT JOIN LATERAL (… LIMIT 1) … ON true` devuelve exactamente una fila por predio, con nulos
  si no hay declaración;
- `fd` entra por la clave primaria de `ficha_catastral`, o sea a lo sumo una.

El que sí podría multiplicar —`f`, la ficha vigente— **se queda en el conteo**: `ficha_vigente_uq`
es *parcial* (`WHERE vigencia_hasta IS NULL`), así que impide dos versiones **abiertas** y nada más;
una abierta y una cerrada pueden cubrir la misma fecha. La página devuelve dos filas de ese predio;
un conteo que no las viera diría un total menor que las filas que la grilla enseña, y la última
página saldría vacía sin que nada lo explicara. El camino de escritura no las produce
—`ActualizarFichaCatastral` cierra la anterior el día antes de abrir la nueva—, pero un padrón
migrado sí puede traerlas, y **el conteo tiene que decir lo que la grilla enseña sea cual sea el
dato**. Eso lo fija una prueba con esa siembra exacta, no este párrafo.

**Medido.** Misma forma que §7.1 —PostgreSQL 16.10, conexión de **`sgtm_app`** con RLS activa,
`SET LOCAL app.municipalidad_id` dentro de una transacción, el padrón de Catacaos (14 422 predios,
14 422 fichas, 14 422 titularidades, 2 885 declaraciones) sembrado en **dos** municipalidades— y en
otra máquina, más lenta y compartida: aquí el conteo que §7.1 midió en 83,5 ms cuesta entre 311 y
534. **Por eso la moneda son páginas tocadas**, que es lo único que sobrevive al cambio de máquina,
que es justo el problema que tuvo este issue.

| Conteo | Páginas tocadas | Reloj |
|---|---|---|
| Sin filtros, **con** los dos `JOIN` de la declaración | 32 293 | 311–534 ms |
| Sin filtros, **sin** ellos | **555** | 86–180 ms |
| Con `sector=01`, con ellos | 6 479 | 24 ms \* |
| Con `sector=01`, sin ellos | **3 828** | 11 ms \* |
| Padrón de 25 predios, con ellos | 67 | — |
| Padrón de 25 predios, sin ellos | **6** | — |
| *(no cambia)* con `condicion=SUBVALUADOR` o `=OMISO` | 32 818 | 464–484 ms \* |

Las dos primeras filas son cinco corridas cronometradas **desde el cliente**, que es lo que la
petición paga; las marcadas con `*` son el `Execution Time` del propio `EXPLAIN ANALYZE`, que sobre
14 422 ciclos lleva dentro su instrumentación (el nodo raíz del conteo largo declara 297 ms y el
`EXPLAIN` entero 636).

De las 32 293 páginas del conteo sin filtros, **31 738 eran el `LATERAL`**: 14 422 descensos al
índice `dj_ejercicio_predio_ix`, uno por predio, con su nodo `Sort` montado y desmontado 14 422
veces. Lo que queda es el suelo real de contar un padrón: leerlo una vez —235 páginas de `predio` y
320 de `ficha_catastral`—.

**Y el planificador ya hacía lo que podía**: en el conteo sin filtros no aparecen ni `sector` ni
`fd`, porque los dos entran por un índice único y sus columnas no se usan, así que PostgreSQL
**elimina** esos dos `LEFT JOIN` él solo. El que no puede eliminar es el `LATERAL`: una subconsulta
con `LIMIT` no le ofrece la prueba de unicidad que la eliminación exige, aunque para quien la
escribió sea evidente. De ahí que esto haya que decirlo en el SQL.

```
-- antes, como sgtm_app y con RLS activa
Aggregate  (Buffers: shared hit=32293)
  ->  Nested Loop Left Join (actual rows=14422)
        ->  Hash Right Join (actual rows=14422)          <- 555: el padron, una vez
        ->  Limit (actual rows=0 loops=14422)            <- 31 738 paginas
              ->  Sort  (Sort Key: d.fecha_presentacion DESC, d.id DESC)
                    ->  Index Scan using dj_ejercicio_predio_ix on declaracion_jurada d

-- despues
Aggregate  (Buffers: shared hit=555)
  ->  Hash Right Join (actual rows=14422)
        ->  Bitmap Heap Scan on ficha_catastral f   (Buffers: shared hit=320)
        ->  Bitmap Heap Scan on predio p            (Buffers: shared hit=235)
```

**Y no es una mejora de plan, sino de sentencia.** Un índice puede dejar de usarse cuando cambian
las estadísticas —es lo que §7.1 y §0 documentan una y otra vez—; una tabla que **no está en el
SQL** no la puede traer ningún planificador. Por eso la prueba mide las dos cosas: las páginas del
plan, y —a través del caso de uso entero, con un pool que anota lo que se prepara— que la sentencia
de conteo que la petición manda de verdad no nombre `declaracion_jurada`.

**Lo que sigue siendo O(padrón), y por qué.** El conteo **con** filtro de condición (32 818 páginas,
~470 ms aquí) no se toca y no se puede: la condición se deriva del cruce, así que para contar
cuántos subvaluadores hay hay que mirar la declaración de cada predio. Lo que sí cambió es que ahora
el conteo y la página son **dos cadenas distintas**, y eso reabre la primera de las dos salidas que
§7.1 descartó: el `DISTINCT ON` sin correlación se descartó porque volvía la **página** O(declaraciones),
y aplicado sólo al conteo filtrado ese reparo ya no aplica. No se toma aquí —serían tres formas del
mismo cruce, y dos de ellas tendrían que coincidir fila a fila en qué declaración es «la que rige»,
que es exactamente la divergencia que #545 vino a cerrar—, pero queda dicho para que retomarlo sea
una decisión y no un redescubrimiento.

## 8. Al agregar una tabla

1. ¿Lleva `municipalidad_id NOT NULL`? Entonces `V6` le pone RLS **sola** —descubre las tablas por
   esa columna— y hay que agregarle su `GRANT` en `V7`.
2. Hay que **sembrarla en `DatosDePrueba`**. Si no, la prueba falla diciendo que la municipalidad A
   no ve filas suyas: una tabla vacía haría que «no se ve nada de B» fuera cierto sin probar nada.
3. Si es catálogo, hay que declararla en `TABLAS_DE_CATALOGO` **en el código de la prueba**, lo que
   obliga a justificarlo en el PR.
4. Si guarda constancia de un acto administrativo, agregarla a `TABLAS_PROTEGIDAS` del revisor de
   código fuente.
