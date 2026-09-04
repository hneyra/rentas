# DAT-02 — Los cuatro baselines

| Campo | Valor |
|---|---|
| Estado | Generados y **verificados ejecutando** |
| Fecha | 2026-09-03 |
| Motor de la verificación | PostgreSQL **16.4** real (embebido, sin Docker) |
| Implementa | [ADR-0032](../../30-arquitectura/adr/ADR-0032-el-esquema-nace-en-baseline.md) §1: «los cuatro baselines se generan una sola vez y por adelantado, junto al inventario del corte» |
| Consume | [GOB-05, el inventario del corte](../../00-gobierno/inventario-del-corte.md) §2 |

Cuatro archivos, uno por sistema. **Las extracciones de la etapa 5 los consumen; no los generan.**

```
rentas/V1__baseline.sql      132 tablas    4 158 líneas
catastro/V1__baseline.sql     28 tablas    1 049 líneas
normativa/V1__baseline.sql    19 tablas      836 líneas
caja/V1__baseline.sql         23 tablas      966 líneas
```

**Las migraciones `V1..V78` de `sgtm` no se copian a ningún repositorio.** Están entrelazadas a
propósito y no hay reparto posible: `V1` crea núcleo y catastro juntos, `V6` aplica RLS a todo el
esquema de una vez y `V7` reparte los privilegios de todos los roles. La historia se queda en
`sgtm`, que no se borra.

## 1. Cómo se generaron, y por qué así

**No se transcribieron a mano.** Se levantó una base vacía, se le aplicaron las 68 migraciones
reales con Flyway, y los baselines se **emitieron desde el catálogo** de esa base restringido a
las tablas de cada sistema. Componer 5 000 líneas de DDL a mano es el modo caro de equivocarse;
así el baseline dice lo que el esquema *tiene*, no lo que alguien creyó que tenía.

Y el emisor no es su propio juez: **el comparador es una pieza distinta** que lee el catálogo
entero —no sólo lo que el emisor emite— y se aplica a los dos lados. Si el emisor se olvidara de
un privilegio de columna, de un disparador o de una política, el lado que lo tiene y el que no
diferirían. Es lo que hizo falta para encontrar los cuatro defectos de §4.

## 2. Los cuatro diffs, publicados

El criterio pide publicarlos aunque estén vacíos. Están en
[`verificar/diffs/`](verificar/diffs/) y son éstos, enteros:

### `rentas` — **vacío**

```
```

Cero líneas. El esquema que produce `rentas/V1__baseline.sql` sobre una base vacía es, columna a
columna, restricción a restricción, privilegio a privilegio y disparador a disparador, el mismo
que producen las 68 migraciones de `sgtm`.

### `normativa` — **vacío**

```
```

Cero líneas, y lo es **porque `arancel` se quedó en `catastro`** (✅ D-N4 revisada): su clave
foránea a `via` se crea de verdad, y **ninguna sale de `normativa`**.

### `catastro` — 3 líneas

```diff
148d147
< RESTRICCION arancel_conjunto_fk FOREIGN KEY (municipalidad_id, conjunto_id) REFERENCES conjunto_parametros(municipalidad_id, id) NOT VALID
819d817
< RESTRICCION inquilino_contribuyente_fk FOREIGN KEY (municipalidad_id, contribuyente_id) REFERENCES contribuyente(municipalidad_id, id)
1586d1583
< RESTRICCION titularidad_contribuyente_fk FOREIGN KEY (municipalidad_id, contribuyente_id) REFERENCES contribuyente(municipalidad_id, id)
```

### `caja` — 1 línea

```diff
955d954
< RESTRICCION recibo_contribuyente_fk FOREIGN KEY (municipalidad_id, contribuyente_id) REFERENCES contribuyente(municipalidad_id, id)
```

**Las cuatro líneas son las cuatro claves foráneas que cruzan la frontera**, y no pueden estar:
la tabla de destino no existe en ese sistema. Son `titularidad`/`inquilino` → `contribuyente`,
`recibo` → `contribuyente` y `arancel` → `conjunto_parametros`; esta última es la referencia al
conjunto sellado con que se publicó el cuadro, la misma que ya llevan `determinacion` y
`prescripcion`, y que ADR-0025 §3 conserva como **dato guardado** en vez de como restricción. Son
**D-18**: el motor deja de garantizar la referencia y pasa a garantizarla una proyección con su
verificación ([ADR-0027](../../30-arquitectura/adr/ADR-0027-la-valuacion-es-un-hecho-sellado.md)).

No desaparecen en silencio: cada baseline las lleva escritas con su nombre y su definición,
marcadas `[CRUZA LA FRONTERA]`, para que se vea lo que se perdió.

```
$ grep -c 'CRUZA LA FRONTERA' */V1__baseline.sql
caja/V1__baseline.sql:1         catastro/V1__baseline.sql:3
normativa/V1__baseline.sql:0    rentas/V1__baseline.sql:0
```

`rentas` tiene 0 porque se lleva el esquema entero: en la primera etapa **es** el monolito con los
doce contextos dentro.

## 3. Lo que se comparó, y lo que no

El comparador vuelca de cada tabla: **relkind**, `rowsecurity` y `forcerowsecurity`, la clave de
particionamiento y los límites de cada partición; **columnas** con su tipo —dominio incluido—, su
`NOT NULL`, su `DEFAULT`, su `GENERATED` y su `IDENTITY`; **todas las restricciones** con su
definición y su marca `NOT VALID`; **índices**; **políticas de RLS** con su mandato, sus roles, su
`USING` y su `WITH CHECK`; **privilegios de tabla y de columna**; **disparadores**; **comentarios**;
y aparte los **dominios** y las **funciones**.

Una diferencia se normaliza antes de comparar, y se declara: **`pg_get_constraintdef` no es
idempotente para un `CHECK ... IN (...)`**. La misma restricción sale
`= ANY ((ARRAY['A'::character varying, …])::text[])` en el esquema original y
`= ANY (ARRAY[('A'::character varying)::text, …])` cuando ese texto se reejecuta. Son 134 líneas
en `rentas`, en pares simétricos.

**No se supuso que fueran equivalentes: se comprobó ejecutando.** `Equiv.java` toma la expresión
que cada base tiene en su catálogo para `acta_fiscalizacion_hallazgo_check`, la evalúa contra los
cinco valores válidos y contra cuatro que no lo son, en las dos bases:

```
ref:      ..... ....   (. = como debe, ! = NO)
t_rentas: ..... ....   (. = como debe, ! = NO)
```

[`canonizar.py`](verificar/canonizar.py) sólo quita casts redundantes sobre literales, se aplica a
**los dos lados por igual**, y nunca elimina un literal, un nombre ni un operador: si a un `CHECK`
le faltara un valor, el diff lo seguiría enseñando.

## 4. Los cuatro defectos que el ciclo encontró

Ninguno se habría visto leyendo los archivos.

| Defecto | Cómo se vio | Qué habría pasado |
|---|---|---|
| **`pg_get_indexdef` emite `ON ONLY`** para el índice de una tabla particionada, y `ON ONLY` no se propaga | El diff: en `sgtm` las particiones de `auditoria` tienen sus índices y en el baseline no | 10 índices en el padre y ninguna partición con ellos. El síntoma es un plan distinto **en la partición**, invisible al leer |
| **Las restricciones heredadas se reemitían** | La migración muere con «constraint "auditoria_observacion_ck" for relation "auditoria_2026" already exists» | El baseline no aplica. Se distinguen por `conislocal`, no por `conparentid` |
| **`nombre_normalizado(text)` no es de disparador** y el emisor sólo emitía las de disparador | «function nombre_normalizado(text) does not exist» al crear `via` | La usa una **columna generada** (`V66`). Ahora se emiten las 9 funciones, y antes de las tablas |
| **Un disparador de restricción está en `pg_constraint`** con `contype='t'` | «syntax error at or near "TRIGGER"» | Se emitía `ALTER TABLE … ADD CONSTRAINT titularidad_no_excede_trg TRIGGER …`, que no es SQL. Es el disparador **diferido** de la titularidad (#16) |

## 5. Que las comprobaciones pueden fallar

Una verificación que no puede ponerse roja no protege nada. Seis mutaciones, medidas:

| # | Mutación | Resultado |
|---|---|---|
| M1 | `ALTER TABLE predio DISABLE ROW LEVEL SECURITY` | **Rojo**: «1 tabla de negocio sin RLS o sin FORCE» |
| M2 | `DROP POLICY via_tenant ON via` | **Rojo ×2**: «tabla con RLS y sin ninguna política» y «tabla de tenant SIN una política que exija el contexto» |
| M3 | `GRANT SELECT ON determinacion_2026 TO sgtm_app` | **Rojo**: «privilegio de sgtm_app sobre una PARTICION: determinacion_2026 SELECT» |
| M4 | Política de tenant con `current_setting(..., true)` | **Rojo ×2**: valor por omisión, y el contraste |
| M5 | **El volcado descuidado**: `GRANT INSERT, SELECT, UPDATE ON declaracion_jurada` en vez del `UPDATE (estado)` | **17 líneas de diff**. `sgtm_app` pasa de poder tocar sólo `estado` a las 16 columnas — el defecto exacto que `V54` existe para impedir |
| M6 | Quitar el disparador `conjunto_sellado_inmutable` y el diferido `titularidad_no_excede_trg` | **5 líneas de diff**, con sus dos funciones y el `DEFERRABLE INITIALLY DEFERRED` |

**M5 es la que más importa** y es la que el enunciado del trabajo nombra: un volcado descuidado
devuelve los privilegios enteros y nadie lo nota. Aquí sí se nota.

## 6. Las guardas, contra el catálogo del motor

No leen los archivos. Corren sobre las cinco bases —la referencia y los cuatro baselines
aplicados— y todas están en verde:

```
ninguna tabla de negocio sin RLS o sin FORCE
ninguna tabla con RLS y sin ninguna política
ninguna política de tabla DE TENANT con valor por omisión
ninguna tabla de tenant SIN una política que exija el contexto   ← el contraste de la anterior
ninguna privilegio de sgtm_app sobre una PARTICIÓN

ref_baselines  tablas: 132   con RLS: 132   políticas: 138   disparadores: 10
t_rentas       tablas: 132   con RLS: 132   políticas: 138   disparadores: 10
t_catastro     tablas:  28   con RLS:  28   políticas:  30   disparadores:  4
t_normativa    tablas:  19   con RLS:  19   políticas:  25   disparadores:  6
t_caja         tablas:  23   con RLS:  23   políticas:  25   disparadores:  1
```

**La tercera guarda nació mal encuadrada y hubo que corregirla, y conviene tenerlo escrito.**
Marcaba en rojo cuatro políticas de `normativa` —`depreciacion`, `parametro_tributario`,
`valor_unitario_edificacion`, `valor_referencial_vehiculo`—, **y también en la referencia**, que
es lo que delató que el defecto era de la guarda. Esas cuatro son los cuadros **nacionales** de
[ADR-0017](../../30-arquitectura/adr/ADR-0017-tablas-de-valuacion-nacionales.md): su
`municipalidad_id` es nulo y su política empieza por `municipalidad_id IS NULL`, que devuelve
cierto sin llegar a `current_setting` — un cuadro del MEF tiene que verse desde cualquier
contexto, incluido el del proceso de carga, que no tiene ninguno. Exigirles la forma estricta era
medir el aislamiento sobre la única clase de tabla que por diseño no aisla. La guarda se acota a
las tablas con `municipalidad_id NOT NULL`, y **gana un contraste** (`3b`) para que ensanchar esa
excepción se ponga rojo por el otro lado.

## 7. Dónde discrepaba el enunciado del inventario

El enunciado del trabajo daba una lista tentativa por sistema y mandaba que, si discrepaba con la
sección 2 del inventario, ganase el inventario. Discrepaba en tres sitios:

| Enunciado | Inventario (§2) | Qué se hizo |
|---|---|---|
| `catastro` incluye **`arancel`** | **Coinciden**, tras revisar ✅ D-N4 el 2026-09-03 | `arancel` va a `catastro`. La primera versión de D-N4 lo mandaba a `normativa`; se revisó al ver que su fuente es **cartográfica** —llega en un GeoPackage de planos por vía y lo importa `catastro.aplicacion.ImportarArancel`—. **El enunciado tenía razón** |
| `normativa` lleva **6** tablas | **6** | 6 |
| `caja` lleva `recibo`, `recibo_correlativo`, `recibo_movimiento`, `cierre_turno`, `cierre_turno_detalle` y «el catálogo de conceptos cobrables» | Lleva **10**: esas 5 más `area`, `caja`, `cierre_caja`, `recibo_detalle` y `tasa` | 10 |

Y una cosa que el enunciado no decía y el inventario sí: **las 13 tablas comunes** —las 6
transversales (`municipalidad`, `documento_emitido`, `auditoria` con sus dos particiones,
`respaldo`) y las 7 de seguridad (✅ D-N5, ✅ D-N7)— **van en los cuatro**. Sin `municipalidad` no
hay ni una clave foránea que crear.

La suma cuadra con el inventario: **15 + 6 + 10 + 88 + 6 + 7 = 132**, y `rentas` es el total.

## 8. PostGIS: el hueco se cerró, y destapó un defecto

**Cerrado el 2026-09-03** contra un PostgreSQL 16 con PostGIS 3.4 real
(`postgis/postgis:16-3.4-alpine`), con las 68 migraciones aplicadas **sin parchear** y los
cuatro baselines aplicados encima. Los cuatro diffs de §2 son los de esa corrida.

**Y no fue un trámite: encontró un defecto que este README afirmaba que no existía.** La
versión anterior decía «los baselines sí las llevan escritas: lo que falta es la medición, no el
DDL». **Era falso.** Los baselines se emitieron desde una base de referencia *sin* PostGIS, así
que el emisor no tenía esas columnas que copiar: a `rentas` y a `catastro` les faltaban **la
columna `geometria`, las cuatro columnas generadas del marco y los dos índices**, y con ellos los
**22 privilegios de columna** que el `GRANT` de tabla les da.

```
$ diff (esquema de V1..V78 con PostGIS) (esquema del baseline)
=== rentas    27 líneas ===
< predio COL 14 geometria geography(MultiPolygon,4326)
< predio COL 15 marco_oeste double precision ... GEN
  … y 25 más
```

Se corrigió añadiéndolas al `CREATE TABLE predio` de los dos —**después de `fecha_registro`**,
que es donde `V61` y `V65` las dejan al añadirlas con `ALTER`; puestas antes, el diff seguía
enseñando las doce líneas del orden— y el diff volvió a 0 y a 3.

**La lección para quien regenere los baselines**: el emisor copia lo que la base de referencia
tiene. Correrlo contra un motor sin PostGIS produce dos baselines incompletos **sin un solo
error**, y lo único que lo delata es el diff con `--con-postgis`.

## 9. Cómo se vuelve a correr

```bash
docs/40-datos/baselines/verificar/verificar-baselines.sh \
    --url jdbc:postgresql://localhost:55440/postgres --usuario postgres --clave postgres
```

Necesita un PostgreSQL 16 con `pg_trgm`, `unaccent` y `btree_gist` —las tres que
`crear-roles.sql` declara además de `postgis`— y una conexión de superusuario, porque crea bases y
roles. No fija ningún puerto: la URL entra por parámetro.

Salida de la última corrida:

```
── 1. La referencia: V1..V78 sobre una base vacia
   Successfully applied 68 migrations to schema "public", now at version v78
── 2.rentas.  OK   diff de rentas: 0 linea(s), las 0 foranea(s) que cruzan
── 2.catastro. OK  diff de catastro: 3 linea(s), las 3 foranea(s) que cruzan
── 2.normativa. OK diff de normativa: 0 linea(s), las 0 foranea(s) que cruzan
── 2.caja.    OK   diff de caja: 1 linea(s), las 1 foranea(s) que cruzan
── 3. Las guardas, contra el catalogo del motor
   TODAS LAS GUARDAS EN VERDE
LOS CUATRO BASELINES CUADRAN
```

## 10. Lo que hay que saber antes de tocar estos archivos

- **Flyway se conserva**, y el motivo no es la migración de datos (ADR-0032 §2): el checksum sobre
  DDL ya aplicado, el Job de implantación que espera consultando `flyway_schema_history` (`V21`), y
  las pruebas de persistencia, que sin versión no pueden decir contra qué esquema pasaron.
- **Antes de cada baseline hay que correr `crear-roles.sql`**: los roles y las extensiones se
  provisionan con superusuario, porque las políticas nombran roles que deben existir y
  `sgtm_owner` no puede instalar una extensión ni crearse a sí mismo.
- **Los cinco hallazgos de RLS de [DAT-01 §0](../modelo-logico-fisico.md) están en el encabezado de
  los cuatro archivos**, como ADR-0032 §Consecuencias exige. No es adorno: el cuarto explica por
  qué hay restricciones `NOT VALID`, y sin él alguien las «arreglaría» en el baseline.
- **En cuanto haya una base en `stg` que alguien no quiera rehacer, estos archivos dejan de poder
  editarse** y el primer `V2` de cada repositorio llega antes de lo que parece.
