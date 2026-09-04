# P5E — Cerrar `rentas`

| Campo | Valor |
|---|---|
| Fecha | 2026-09-04 |
| Repositorios tocados | `rentas`, `caja` |
| Repositorios medidos | `rentas`, `catastro`, `normativa`, `caja` |
| Motor de la verificación | PostgreSQL **16.15** real en `127.0.0.1:55444`, **no** por Testcontainers (§10, hueco 1) |
| Implementa | [ADR-0029](../30-arquitectura/adr/ADR-0029-cuatro-sistemas-separados.md), [ADR-0027](../30-arquitectura/adr/ADR-0027-la-valuacion-es-un-hecho-sellado.md) |
| Consume | `P5A-copia-del-backend.md`, `P5B-extraccion.md` (normativa), `P5C-extraccion.md` (catastro), `P5D-extraccion.md` (caja) |

---

## 0. Los cuatro criterios, y qué se midió de cada uno

| # | Criterio | Estado | Dónde |
|---|---|---|---|
| **1** | La lista de excepciones está vacía: ningún sistema lee por SQL una tabla que ya no es suya | **Cumplido**, y no lo estaba: en `caja` la regla no podía verlo y había **un cruce vivo**. §2 | §2 |
| **2** | Los tres verificadores bloqueantes en verde | **Cumplido** en los cuatro | §3 |
| **3** | El padrón de un ejercicio da el mismo céntimo que el monolito | **Cumplido en lo que el sistema sabe calcular**; lo que no, está declarado y no es de código | §4 |
| **4** | `generar-openapi.mjs --comprobar` en verde en los cuatro | **Cumplido en 1 de 4.** Los otros tres **no tienen contrato ni generador**, y nunca lo tuvieron | §5 |

---

## 1. Lo que ya estaba hecho, comprobado en vez de creído

El enunciado pide retirar los módulos y las tablas de `parametros`, `catastro` y la mitad de
`tesoreria` que se fue a la caja. **P5B, P5C y P5D ya lo hicieron.** Se comprobó, no se dio por
bueno.

### 1.1 Las tablas

Censadas sobre el archivo de migraciones y **confirmadas contra PostgreSQL** aplicando la cadena
entera a una base vacía:

| Migración | Qué retira | Cuántas |
|---|---|---:|
| `V2` | Las tablas de `normativa` | **6** |
| `V6` | Las tablas de `catastro` | **15** |
| `V7` | Las tablas de la ventanilla, que se fueron a `caja` | **10** |

**31 tablas retiradas.** El esquema vivo tiene **113** (108 `BASE TABLE` más 5 particionadas),
contadas en el catálogo de una base con `V1…V9` aplicadas. De ellas, **11 nacen después del corte**:
las 5 de la copia sellada de normativa (`V3`), las 3 de la proyección de catastro (`V4`), las 2 de la
valuación recibida (`V5`) y el buzón de pagos (`V8`).

**Ninguna tabla se quedó de más**, y ninguna se borra que no se hubiera creado aquí.

### 1.2 Las funciones

`V1` crea nueve. Seis se fueron con sus tablas —cuatro en `V2`, dos en `V6`— y las tres que quedan
son de `rentas`: `declaracion_jurada_estado_es_terminal`, `documento_solo_cuenta_reimpresiones` y
`nombre_normalizado`.

**`nombre_normalizado` se queda, y no por descuido**: `V6` lo dice, y lo dijo el motor antes que
ninguna revisión — «cannot drop function nombre_normalizado(text) because other objects depend on
it — index contribuyente_nombre_trgm_ix depends on it». Comprobado aquí: es la única función que el
esquema final sigue usando, por el índice GIN de búsqueda por aproximación del padrón (RF-014).

**Así que el problema simétrico que P5B, P5C y P5D encontraron —el baseline arrastrando objetos de
otro sistema— aquí no está en las funciones.** Está en otro sitio, y es §7.

### 1.3 Los módulos

Presentes: los **catorce** que el enunciado enumera —`contribuyentes`, `rentas`, `cuentacorriente`,
`valores`, `fiscalizacion`, `coactiva`, `sanciones`, `licencias`, `seguridad`, `indicadores`,
`plataforma`, `esquema`, `aplicacion` y `tesoreria`—, más `dominio-compartido`, que el enunciado no
nombra y sin el cual no compila nada, y más **los dos adaptadores cliente**, que son §6.

`tesoreria` es la mitad que se queda: **convenios** y el **buzón de pagos** de `V8`. No hay ni una
clase de recibo, turno, arqueo o cierre de caja; lo que queda hacia la ventanilla es
`ClienteHttpDeCaja` y sus dos adaptadores.

---

## 2. Criterio 1 — la lista de excepciones, y por qué medirla era el trabajo

`CrucesConsentidosDelSgtm.LISTA` está a **cero en los cuatro repositorios** (en `normativa` la
configuración devuelve `List.of()` sin clase aparte). Eso ya estaba escrito.

**Una lista vacía no vale nada si la regla que la usa no puede ver nada**, así que se midió la
regla, repositorio por repositorio, con la mutación que tiene que morder: una consulta a una tabla
de otro sistema dentro de `src/main`, aplicada sola y restaurada byte a byte con `cmp`.

| Repositorio | Mutación | Resultado |
|---|---|---|
| `rentas` | `FROM predio JOIN titularidad` en `ConciliacionRepositoryJdbc` | **Rojo**, nombrando las dos tablas y su sistema |
| `rentas` | Una entrada rancia en la lista (`ConciliacionRepositoryJdbc`, `#DE-MEDIDA`) | **Rojo**: «está en la lista […] y ya no cruza nada: quítalo» |
| `catastro` | `FROM contribuyente JOIN recibo` en `ValuacionRepositoryJdbc` | **Rojo**, nombrando `rentas` y `caja` |
| `normativa` | `FROM contribuyente` en `SnapshotRepositoryJdbc` | **Rojo**, nombrando `rentas` |
| `caja` | `FROM contribuyente JOIN predio` en `OrdenDeCobroRepositoryJdbc` | **VERDE. La regla no lo veía.** |

### 2.1 Lo que la quinta fila destapó

`ConfiguracionDeCaja.sistemaDeCadaTabla()` sólo nombraba **las doce tablas de la caja y las trece
replicadas**. Y el escáner distingue tres casos a propósito —lo propio, lo replicado y **lo que
nadie repartió**—, y el tercero **no es un cruce**, porque un escáner que marcara toda tabla
desconocida gritaría en cada archivo y dejaría de leerse (#437).

De modo que en `caja` **cualquier** consulta a una tabla de otro sistema pasaba en verde. Y la
propia clase afirmaba de sí misma lo contrario:

> «como el reparto de tablas solo nombra las de este esquema, **cualquier** consulta a una tabla
> ajena se detecta — y no hay ninguna lista de excepciones donde esconderla.»

Es falso, y se midió. `catastro` y `normativa` sí nombraban las tablas de los otros tres; `caja` no.

**El arreglo**: `ConfiguracionDeCaja` nombra ahora también las 88 de `rentas`, las 15 de `catastro`
y las 6 de `normativa`, copiadas del reparto que `catastro` ya tenía. No son tablas de esa base —y
justamente por eso hay que nombrarlas: es lo único que hace que consultarlas se vea al construir y
no en producción—. Con el reparto completo, la misma mutación da **rojo** nombrando las dos tablas.

### 2.2 Y lo que encontró en cuanto pudo ver

En la **primera** corrida con el reparto corregido, sin ninguna mutación:

```
kamayuk-caja-caja/…/ReciboRepositoryJdbc.java — la tabla «contribuyente» es de «rentas»
y esto es «caja» […]: FROM contribuyente
```

**`PENDIENTE-CRUCE-06` no estaba cerrado.** P5D cerró la mitad de la **emisión** —copiar
`pagador_documento` y `pagador_nombre` en el propio recibo— y dejó viva la mitad de la **búsqueda**:
el filtro del listado de recibos (#548) resolvía el código del padrón con una subconsulta
`SELECT t.id FROM contribuyente t WHERE t.codigo_contribuyente = :codigo`, contra una tabla que esa
base no tiene.

Se cierra por donde el propio diseño de P5D ya decía, y no inventando nada: **el recibo se busca por
el documento de quien pagó**, que es lo que `Pagador` lleva escrito —«es con lo que se busca un
recibo en ventanilla —"vengo por el duplicado del recibo de mi DNI"— y por eso tiene índice»— y para
lo que `V2` ya creó `recibo_pagador_ix`. El criterio pasa de `codigoContribuyente` a
`documentoDelPagador` y el parámetro de `?codContribuyente=` a `?documento=`.

**Lo que cuesta, dicho aquí**: desde la caja ya no se puede buscar por código municipal. Quien lo
tenga resuelve antes el documento en el padrón — que es el orden en que la ventanilla lo hace de
todas formas, porque viene una persona con un DNI y no con un código. **Y no reabre D-17**: no se
añade ninguna columna ni ningún registro de pagadores; se usa el que P5D ya había copiado.

---

## 3. Criterio 2 — los tres verificadores bloqueantes

Contra PostgreSQL **16.15 real**, con `cleanTest --rerun-tasks --no-build-cache`, para que ninguna
tarea se dé por `UP-TO-DATE` (lección de #192 §2).

| Tarea | `rentas` | `catastro` | `normativa` | `caja` |
|---|---|---|---|---|
| `./gradlew build` | **VERDE** | **VERDE** | **VERDE** | **VERDE** |
| `./gradlew verificarArquitectura` | **VERDE** | **VERDE** | **VERDE** | **VERDE** |
| `./gradlew verificarAislamiento` | **VERDE** | **VERDE** | **VERDE** | **VERDE** |
| Pruebas | **3 080** | **945** | **598** | **667** |
| Fallos | 0 | 0 | 0 | 0 |

**5 290 pruebas en total, 0 fallos.** Las cifras de `catastro`, `normativa` y `caja` son las mismas
que dejaron P5C, P5B y P5D: esta etapa no perdió ni una. `rentas` sube de **3 076 a 3 080**, y son
exactamente las cuatro de `ProcedenciaDeLasProyeccionesTest` (§8.1) — `kamayuk-rentas-esquema` pasa
de 41 a 45.

Y las cuatro modificadas de `caja` no cambian el recuento a propósito: el filtro del listado de
recibos se midió antes por su nombre (`codigoContribuyente`) y ahora por lo que filtra
(`documentoDelPagador`), en la misma prueba.

El desglose por módulo de `rentas`:

| Módulo | Pruebas | | Módulo | Pruebas |
|---|---:|---|---|---:|
| aplicacion | 130 | | licencias | 285 |
| coactiva | 197 | | parametros | 54 |
| contribuyentes | 80 | | plataforma | 177 |
| cuentacorriente | 273 | | rentas | 595 |
| dominio-compartido | 154 | | sanciones | 240 |
| esquema | 45 | | seguridad | 180 |
| fiscalizacion | 298 | | tesoreria | 134 |
| indicadores | 57 | | valores | 181 |

`kamayuk-rentas-catastro` sigue con **cero pruebas, y sigue siendo correcto**: no tiene qué probar.
Es un adaptador cliente sin dominio, sin repositorio y sin una sola consulta (§6.1).


---

## 4. Criterio 3 — el mismo céntimo, con los cuatro sistemas puestos

Se repite la comparación de P5C, y **las dos**: son dos pruebas distintas que miden el mismo
invariante por lados distintos y conviven.

| Prueba | Qué mueve de sitio | Qué escribe |
|---|---|---|
| `PadronRecalculadoTest` (P5B) | De dónde salen los **parámetros** | El cuadro sellado y el impuesto de catorce autovalúos |
| `PadronDelEjercicioTest` (P5C) | De dónde sale el **predio** | Doce contribuyentes, quince predios, con base, impuesto, derecho de emisión, ponderación y cuotas |

**Cómo se midió**: un `git worktree` de `rentas@24c9ed0` —el árbol posterior a P5B y anterior a
P5C—, con `PadronDelEjercicioTest` copiado tal cual y **sin una sola adaptación**, contra el árbol
de hoy. Cubre de una vez **P5C, P5D y P5E**, que es lo que nadie había medido: P5C midió antes de
que `caja` existiera.

```
$ diff  …/rentas-antes-de-p5c/…/padron-recalculado.csv   …/rentas/…/padron-recalculado.csv
$ diff  …/rentas-antes-de-p5c/…/padron-del-ejercicio.csv …/rentas/…/padron-del-ejercicio.csv
$ shasum -a 256 …
a3f9ff2411c1ffef81b959b61b700ae1d6680b36529ee90ac76252dac017e3c5  padron-recalculado.csv (antes)
a3f9ff2411c1ffef81b959b61b700ae1d6680b36529ee90ac76252dac017e3c5  padron-recalculado.csv (despues)
225d0356656ec62d740254e6e9fd5ce2240f5127d8e637a4bbdc840b210c801d  padron-del-ejercicio.csv (antes)
225d0356656ec62d740254e6e9fd5ce2240f5127d8e637a4bbdc840b210c801d  padron-del-ejercicio.csv (despues)
```

Cero líneas de diff en los dos. **Y las dos huellas son, letra por letra, las que P5B y P5C
publicaron por su cuenta** (`a3f9ff24…` en P5B §4, `225d0356…` en P5C §5): es una comprobación
cruzada que no depende de esta sesión.

**Que el archivo discrimina no se supone.** La rotura de P5C —quitar la ponderación por porcentaje
de propiedad— deja **30 líneas de diff**, con la copropiedad al 50 % pasando de base 100 000 a
200 000 y su impuesto de 270,00 a 870,00. Se conserva el archivo de esa rotura y se vuelve a
comparar aquí.

### 4.1 Lo que este criterio NO puede medir, y no es de código

**No existe ninguna corrida de valuación con cifras**, ni aquí ni en el monolito, así que «el padrón
calculado por los cuatro sistemas» no tiene hoy dos versiones que comparar: tiene cero. Lo bloquean
cuatro decisiones abiertas —el cuadro de valores unitarios y la depreciación (GOB-03 H-14/H-15), los
aranceles de ordenanza (D-02b), el `% actualización` (D-11) y que algún ejercicio esté sellado
(D-02a)—, y por eso `DeterminarPredial` recibe el autovalúo **declarado** en la petición.

`caja` tampoco entra en esta cuenta y no puede: no calcula el padrón, lo cobra. Lo que sí está
medido de su frontera es §2.

Se mide lo que el sistema **sí** sabe calcular, y se compara como archivo. No se inventa una métrica
equivalente.

---

## 5. Criterio 4 — el contrato, en 1 de 4

```
$ node docs/50-api/generar-openapi.mjs --comprobar
El contrato y el generador cuadran: 228 operaciones en 205 rutas
```

**En `catastro`, `normativa` y `caja` este comando no existe.** No es que falle: no hay
`docs/50-api/`, no hay `generar-openapi.mjs` y no hay YAML. Medido:

```
$ find catastro normativa caja rentas -name generar-openapi.mjs
rentas/docs/50-api/generar-openapi.mjs
```

Y no es un descuido de esta etapa: es el **mismo hueco declarado tres veces** —P5B hueco 5, P5C
hueco 7, P5D hueco 10— con el mismo argumento, que conviene repetir porque decide qué haría falta:
el generador de `rentas` **deriva del prototipo del manual** (#312), y los otros tres no tienen
prototipo del que derivar. Un contrato emitido desde sus propios controladores sería un espejo del
código y su `ContratoDeApiTest` sería tautológico.

**Así que este criterio no se cumple, y no se finge que sí.** Lo que costaría está en §6.3, porque
es el mismo bloqueo que impide publicar los clientes.

---

## 6. Punto 3 — dónde viven los clientes HTTP. **Decisión: se quedan**

El enunciado dice que los clientes los publica el dueño de cada API (`catastro-cliente`,
`normativa-cliente`, `caja-cliente`) y que `rentas` sólo los consuma fijando versión (ADR-0030 §4).
**Hoy no es así**, y no se cambia. Aquí está por qué, con lo que se midió.

### 6.1 Qué hay exactamente

| Módulo | Clases de producción | Qué son | SQL |
|---|---:|---|---|
| `kamayuk-rentas-catastro` | **26** | 19 puertos + 7 de transporte | **ninguna consulta** |
| `kamayuk-rentas-parametros` | **33** | puertos, la caché sellada de `V3` y su cliente | sólo `normativa_*`, **su propia caché** |
| `kamayuk-rentas-tesoreria` | 71 | convenios, buzón de pagos y `ClienteHttpDeCaja` | sólo tablas de `rentas` |

Los tres clientes HTTP son `ClienteHttpDeCatastro`, `ClienteHttpDeNormativa` y `ClienteHttpDeCaja`.
Las rutas que piden, censadas del código:

| Sistema | Ruta | Verbo |
|---|---|---|
| `catastro` | `/catastro/fichas?aLaFecha=…` | GET |
| `catastro` | `/catastro/tablas/valores-unitarios?ejercicio=…` | GET |
| `normativa` | `/conjuntos?ejercicio=…` | GET |
| `normativa` | `/conjuntos/{id}/snapshot?ambito=…` | GET |
| `caja` | `/ordenes-de-cobro` | POST |
| `caja` | `/recibos/{numero}` | GET |
| `caja` | `/recaudacion/avance?dia=…&aLaFecha=…` | GET |
| `caja` | `/tasas/{…}/cobros/{…}` y `/tasas/{…}/recaudacion?desde=…` | GET |

### 6.2 Los tres motivos, en orden de fuerza

**(1) Lo que está ahí no es todo transporte, y la mitad no puede mudarse.** De las 26 clases del
adaptador de catastro, **19 son los puertos** —`LectorDeFichas`, `GestorDeTitularidad`,
`TitularesDelPredio`…—, y un puerto lo define **quien lo consume**: está escrito en el vocabulario de
`rentas`, con la forma que las 27 clases de `src/main` que lo llaman necesitan, y sus firmas hablan
en `kamayuk.rentas.dominio.*`. Un `catastro-cliente` publicado por `catastro` no puede contenerlos.
Lo que sí podría mudarse son las 7 de transporte, y eso es lo que bloquea (2).

**(2) Un cliente publicado necesita `comun-dominio`, que no existe (D-23).** Medido: los cuatro
`dominio-compartido` tienen **33 archivos cada uno**, y **31 de los 33 son idénticos módulo el
nombre del paquete** —los otros dos son `MarcoGeografico` y un `package-info`—. Un `catastro-cliente`
que hablara `kamayuk.catastro.dominio.Dinero` obligaría a `rentas` a traducir en la frontera, y esa
traducción sería **otra** copia del contrato. Publicar el cliente antes que el dominio común no da
una versión del contrato: da dos. El orden es al revés — primero `comun-dominio`.

**(3) No hay contrato del dueño contra el que probarlo.** ADR-0030 §4 no dice sólo «lo publica el
dueño»; dice que va **con su prueba de contrato en CI: el proveedor falla si deja de cumplir lo que
su cliente promete**. Eso es lo que sustituye a lo que hoy hace el compilador. Y §5 midió que
`catastro`, `normativa` y `caja` **no tienen contrato**. Un `<sistema>-cliente` sin su prueba de
contrato no es una versión del contrato: es una copia con número de versión, que es peor, porque
parece que alguien la vigila.

Y una cuarta, de tamaño: **7 de los 9 puertos de catastro no tienen hoy ninguna ruta que los
conteste** (P5C hueco 2). Publicar un cliente para dos operaciones cuyos otros siete métodos lanzan
`SinRutaEnCatastro` sería publicar sobre todo el hueco.

### 6.3 Lo que la decisión deja abierto, y en qué orden se cierra

**El riesgo que no se cierra**: nada impide hoy que `catastro` renombre una de sus dos rutas y que
`ClienteHttpDeCatastro` siga pidiendo la vieja. Eso sólo aparece al integrar. La tabla de §6.1 es el
inventario para quien lo cierre.

**No se construyó una guarda de contrato del lado del consumidor, y hay que decir por qué**: una que
sólo comprobara, dentro de `rentas`, que las rutas del cliente están declaradas en una tabla del
propio `rentas` **no puede fallar por lo que importa** —los dos lados serían del mismo repositorio—,
y una que compare contra el contrato del dueño necesita ese contrato, que es §5. Se prefiere no
tener guarda a tener una que no puede fallar.

El orden, y no admite otro: **`comun-dominio` (D-23) → contrato derivado en cada dueño → publicar
`<sistema>-cliente` con su prueba de contrato**. Cada eslabón necesita el anterior.

---

## 7. Punto 6 — el baseline. **Decisión: no se rehace entero; se le quita lo que se midió que sobra**

### 7.1 Lo que se midió

El problema simétrico que el enunciado anticipaba **existe**, y no está en las funciones (§1.2) sino
en las **extensiones**.

`V1` crea `predio` con una columna `geography(MultiPolygon,4326)` y cuatro columnas generadas con
`st_xmin`/`st_ymin`/`st_xmax`/`st_ymax`, más dos índices; y dos restricciones `EXCLUDE USING gist`
sobre `ficha_catastral` y `titularidad`. Las cinco tablas implicadas **son de `catastro` y `V6` las
retira**.

Medido sobre una base con `pg_trgm`, `unaccent` y `btree_gist`, y **sin PostGIS**:

```
psql:V1__baseline.sql:1610: ERROR:  type "geography" does not exist
```

Es el **mismo modo de fallo exacto** que P5D midió en `caja` con `unaccent` («el baseline original
muere en su línea 204»), y el mismo que dejó `stg` cuatro días sin desplegar con `V61` (#742).

Y medido sobre el esquema **final** de `rentas`, con `V1…V9` aplicadas:

| Qué se buscó | Resultado |
|---|---|
| Columnas de tipo `geography`/`geometry`/`box3d` en `public` | **ninguna** |
| Índices `gist`, `spgist` o `brin` | **ninguno** |
| Restricciones `EXCLUDE` | **ninguna** |
| Índices GIN | **uno**: `contribuyente_nombre_trgm_ix` |

O sea: `rentas` necesita `pg_trgm` y `unaccent`, y **no necesita ni `postgis` ni `btree_gist`** — y
aun así su `crear-roles.sql` declaraba las cuatro y su `V1` no podía ni aplicarse sin PostGIS, que
además **no es *trusted*** y exige un superusuario.

### 7.2 Qué se hizo, y qué no

**No se rehizo el baseline entero**, y conviene decir las dos mitades del porqué.

*Por qué no*: los 4 165 renglones de `V1` se emitieron desde el catálogo del monolito (DAT-02), no
se escribieron; rehacerlos exige un emisor que no está en este repositorio, y el resultado habría
que volver a comparar objeto a objeto. Y las tres migraciones de baja **documentan el corte**: por
qué `nombre_normalizado` se queda, por qué no hay `REVOKE CONNECT`, qué significan ahora
`declaracion_jurada.predio_id` y `determinacion.predio_id` sin su clave foránea. Eso se perdería o
habría que reescribirlo dentro de `V1`, donde se lee peor.

*Qué sí se hizo*: quitar de `V1` las **nueve líneas** que pedían las dos extensiones que sobran —las
cinco columnas de geometría del predio, sus dos índices y las dos restricciones de exclusión—, todas
sobre tablas que `V6` borra y que **nunca llegan a tener una fila**, porque nada en `rentas` las
escribe desde P5C (los fixtures usan las tablas `*_de_prueba`). Y quitar `postgis` y `btree_gist` de
`crear-roles.sql`.

**El criterio es el de P0B, y se cumple: diff de esquema vacío.**

```
$ pg_dump --schema-only --schema=public  (V1 anterior + V2…V9)   > /tmp/esq-antes.sql
$ pg_dump --schema-only --schema=public  (V1 de hoy   + V2…V9)   > /tmp/esq-despues.sql
$ diff /tmp/esq-antes.sql /tmp/esq-despues.sql
5c5
< \restrict frEQUefX9B3XKxKUGXXqbW03XgdTZ4bCuyX3Dsh2wG76rspNFgYuUOn0P1KDtpA
---
> \restrict Maa7hVNTDA0vWwQlTnPY2h6xS20fdeHCoJpCoFxETNqgw8Vmqb9cV4PYgumpWUY
12163c12163
…
```

**12 164 líneas idénticas a 12 164**, y las dos únicas diferencias son el testigo aleatorio que
`pg_dump` estampa en cada invocación, no contenido del esquema. Y la cadena entera aplica sobre una
base con **sólo `pg_trgm` y `unaccent`**.

De paso se retiró de `AislamientoMultiTenantTest` la exención de `spatial_ref_sys`: la instalaba
PostGIS, y una exención que ya no exime nada se queda dentro para siempre — la misma razón por la
que existe `ningunCruceConsentidoSobra`.

**Lo que esta decisión deja sobre la mesa, dicho sin adorno**: `V1` sigue creando 31 tablas para
dejarlas caer en `V2`, `V6` y `V7`. Cuesta unos segundos por instalación y no cuesta nada más,
porque el esquema resultante es el correcto. La puerta sigue abierta mientras no haya padrón real
(ADR-0032 §3); el día que la municipalidad piloto cargue el suyo, se cierra.

**Lo que no se hizo y se pudo pensar que sí**: la imagen por omisión de Testcontainers sigue siendo
`postgis/postgis:16-3.4-alpine`. Cambiarla a una imagen simple sería coherente con lo anterior, pero
**es el único camino que esta máquina no puede ejercitar** (§10, hueco 1) y no se toca lo que no se
puede medir.

---

## 8. Punto 4 — la procedencia por fila de las proyecciones (`V9`)

Desde P5C, `predio_ref`, `ficha_ref`, `valuacion_predio` y `valuacion_corrida` son **la única
referencia** que `rentas` tiene de lo que ya no está en su base. `V4` y `V5` las dejaron con **media
procedencia cada una, y ninguna con la mitad que le faltaba a la otra**:

| Tabla | `evento_id` | `secuencia` | `huella` |
|---|:--:|:--:|:--:|
| `catastro_evento_aplicado` (el buzón) | sí | sí | **no** |
| `predio_ref` | **no** | sí | **no** |
| `ficha_ref` | **no** | sí | **no** |
| `valuacion_predio` | sí | **no** | sí |
| `valuacion_corrida` | **no** | **no** | sí (agregada) |

El síntoma de esa falta no es un error: es **una fila plausible**. La pregunta que hay que poder
contestar delante de un contribuyente —«¿por qué esta ficha dice 180 m²?»— se contesta con el evento
que la escribió, y sólo si la fila lo nombra. Sin eso la respuesta es «porque así está proyectado»,
que no es una respuesta.

`V9` completa las tres en las cinco, y las ata:

- **`evento_id` con clave foránea al buzón.** Es lo que separa una procedencia de una decoración:
  un uuid con la forma buena que no apunta a nada es **peor** que no ponerlo, porque parece
  trazable. La clave es compuesta `(municipalidad_id, evento_id)` porque el buzón lo es — sin la
  municipalidad, una fila podría apuntar al evento de la vecina y RLS ni se enteraría: la política
  filtra lo que se **lee**, no lo que una clave foránea alcanza.
- **`secuencia`**, que es lo que impide que un hecho viejo que llega tarde pise a uno nuevo.
- **`huella`**, la del cuerpo del evento **tal como lo emitió el otro sistema**, que no se recalcula
  aquí — eso comprobaría que lo que tenemos es igual a lo que tenemos, que es literalmente lo que
  `V5` ya dice de `valuacion_corrida.huella`. Con ella, una reentrega del mismo `evento_id` con otro
  contenido se puede ver; sin ella la deduplicación la da por buena.

**`NOT NULL` sin `DEFAULT`, y las sentencias fallan si hay una sola fila.** Un `DEFAULT` inventaría
una procedencia. Que hoy no haya filas no se supone: `V4` y `V5` son de este repositorio, no hay
instalación suya desplegada, el único rol que puede escribirlas —`rol_ingestor_catastro`— **no tiene
todavía ningún proceso que lo use** (P5C hueco 3) y en `src/main` no hay un solo `INSERT` sobre
ninguna de las cinco.

Las claves foráneas van **`NOT VALID`** por el cuarto hallazgo de DAT-01 §0: una clave foránea nueva
sobre una tabla con RLS no se puede validar, porque validar es una consulta y el migrador corre sin
contexto de tenant. No debilita nada aquí: sigue comprobando cada `INSERT`, que es todo lo que hay.

### 8.1 La guarda, y que muerde

`ProcedenciaDeLasProyeccionesTest`, cuatro pruebas contra PostgreSQL real. **La lista de
proyecciones no se escribe a mano: se deriva del motor** —una proyección es una tabla que `sgtm_app`
lee y no escribe y que escribe un rol `rol_ingestor_%`—, porque con una lista a mano la tabla que
alguien olvidara añadir sería justamente la que diría que todo está bien.

| Mutación | Resultado |
|---|---|
| Una proyección nueva sin procedencia (`arancel_ref`, con su RLS y sus privilegios) | **2 en rojo**, y la segunda con **3 fallos**, uno por columna, nombrando la tabla |
| Quitar de `V9` la clave foránea de `predio_ref` | **1 en rojo, y sólo ésa**: la fila que nombra un evento que nadie aplicó vuelve a entrar |

`pago_recibido` (`V8`) **no se toca**, y no por olvido: no es una proyección sino el buzón de
`caja`, ya lleva el `pago_id` que genera **quien emite** y el cuerpo entero. Lo que le falta —una
huella de los bytes exactos del emisor— es una decisión que P5D tomó al revés a propósito («gana la
lista blanca»), y reabrirla aquí sería deshacerla de lado.

---

## 9. Punto 5 — las excepciones nominadas, una por una

| Identificador | Dónde estaba | Estado |
|---|---|---|
| `PENDIENTE-CRUCE-01` | `DeteccionRepositoryJdbc`, `ConciliacionRepositoryJdbc` (rentas → catastro) | **Cerrado en P5C**: leen `predio_ref` y `ficha_ref`, la proyección de `V4` |
| `PENDIENTE-CRUCE-02` | `ValuacionRepositoryJdbc` (catastro → normativa) | **Cerrado en P5B/P5C**: copia local sellada |
| `PENDIENTE-CRUCE-03` | `ValorReferencialRepositoryJdbc` (rentas → normativa) | **Cerrado en P5B**: la caché de `V3` |
| `PENDIENTE-CRUCE-04` | `TitularPrincipalRepositoryJdbc` (rentas → catastro) | **Cerrado en P5C**: `TitularPrincipalPorElPuerto` |
| `PENDIENTE-CRUCE-05` | `CuotaDeArbitrioRepositoryJdbc` (rentas → catastro) | **Cerrado en P5C**: traduce contra la proyección |
| `PENDIENTE-CRUCE-06` | `ReciboRepositoryJdbc` (caja → rentas) | **Cerrado a medias en P5D y del todo aquí.** §2.2 |

Ninguna se borra sin cerrarse. La lista queda **vacía en los cuatro** y su mecanismo se conserva
declarado: con la lista a cero, un cruce nuevo no tiene dónde esconderse.

---

## 10. Huecos declarados

1. **Testcontainers no se usó, y es el camino que corre en CI.** Todo corrió contra un PostgreSQL
   **16.15 real** en `127.0.0.1:55444`, con RLS, `FORCE ROW LEVEL SECURITY` y los roles de verdad,
   pero **no por Testcontainers**: el demonio de Docker es un túnel a un VPS, el contenedor arranca
   allí y su puerto se publica allí, así que `getJdbcUrl()` devuelve un `localhost:<puerto>`
   inalcanzable. Mismo hueco que P3, P4, P5A, P5B, P5C y P5D.
2. **El criterio 4 no se cumple en tres de los cuatro repositorios.** §5. No se construyeron los tres
   generadores: el de `rentas` deriva del prototipo del manual (#312) y los otros no tienen prototipo
   del que derivar, así que lo que haría falta es una decisión de diseño, no una tarde de trabajo.
3. **Los clientes HTTP se quedan en `rentas`.** §6, con los tres motivos medidos. El riesgo que deja
   abierto —que el dueño renombre una ruta y sólo se vea al integrar— **no está cerrado por ninguna
   guarda**, y se explica en §6.3 por qué no se escribió una que no podría fallar.
4. **El ingestor de eventos sigue sin existir.** `V9` completa la **forma** de la procedencia; quien
   la escriba es el proceso que consume la cola, y no hay cola, ni suscripción, ni reintento (P5C
   hueco 3). Los fixtures **fabrican** la huella y lo dicen en su javadoc: lo que hoy se puede
   sostener es la forma, no que el valor signifique nada.
5. **La guarda de extensiones de #742 no cubre estos cuatro repositorios.**
   `infrastructure/infra/verificaciones/extensiones-de-las-migraciones.ts` tiene la ruta del
   monolito escrita a mano (`backend/sgtm-esquema/…`). La medida de §7.1 se hizo a mano por eso; y
   por eso mismo, la próxima extensión que alguien necesite en `rentas` volverá a poder faltar.
6. **El baseline no se rehizo entero.** §7.2, con lo que eso deja sobre la mesa y hasta cuándo sigue
   siendo posible.
7. **`rol_ingestor_catastro` sigue sin clave asignada** y no está en el inventario de secretos de
   INF-06 (P5C hueco 9). `V9` no lo cambia.
8. **No hay intercambio de token (RFC 8693).** Los tres clientes reenvían el `Authorization` de la
   petición en curso. Mismo hueco que P5B, P5C y P5D.
9. **El `worktree` de comparación (`rentas-antes-de-p5c`) se retiró al terminar.** Lo que queda es
   este documento y las dos huellas, que cualquiera puede volver a producir con el mismo comando.
