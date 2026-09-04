# P5A — El backend entero, copiado a `rentas`

**Fecha:** 2026-09-04. **Origen:** `sgtm@0d33ad7b` (rama `migracion-a-microservicios`).
**Repositorios tocados:** `rentas` (destino) e `infrastructure` (una regla compartida, §6).
**`sgtm` no se tocó:** `git status` queda limpio, sin un solo archivo modificado.

En esta etapa `rentas` **es** el monolito modular menos la interfaz y menos la infraestructura.
Los doce contextos siguen dentro, `catastro`, `parametros` y `tesoreria` incluidos. Se extraen
después, uno a uno, y así nunca hay un momento en que nada funcione.

---

## 1. Los dos números

Medidos ejecutando, no razonados, y **contando los XML de `*/build/test-results/*/TEST-*.xml`**
(atributos `tests`, `failures`, `errors`, `skipped`), no leyendo la salida de Gradle.

| | `sgtm@0d33ad7b` | `rentas` | Diferencia |
|---|---:|---:|---:|
| **Pruebas ejecutadas** | **3 756** | **3 756** | **0** |
| Fallos | 0 | 0 | 0 |
| Errores | 0 | 0 | 0 |
| Omitidas | 0 | 0 | 0 |

**La diferencia es cero módulo a módulo, no sólo en el total** — que es lo que distingue «cuadra»
de «se compensan dos errores»:

| Contexto | `sgtm` | `rentas` | Dif. |
|---|---:|---:|---:|
| `aplicacion` | 130 | 130 | 0 |
| `catastro` | 431 | 431 | 0 |
| `coactiva` | 197 | 197 | 0 |
| `contribuyentes` | 80 | 80 | 0 |
| `cuentacorriente` | 273 | 273 | 0 |
| `dominio-compartido` | 154 | 154 | 0 |
| `esquema` | 46 | 46 | 0 |
| `fiscalizacion` | 297 | 297 | 0 |
| `indicadores` | 57 | 57 | 0 |
| `licencias` | 285 | 285 | 0 |
| `parametros` | 138 | 138 | 0 |
| `plataforma` | 177 | 177 | 0 |
| `rentas` | 586 | 586 | 0 |
| `sanciones` | 240 | 240 | 0 |
| `seguridad` | 180 | 180 | 0 |
| `tesoreria` | 304 | 304 | 0 |
| `valores` | 181 | 181 | 0 |
| **TOTAL** | **3 756** | **3 756** | **0** |

Y las tres tareas del criterio, cada una por su lado:

| Tarea | Resultado |
|---|---|
| `./gradlew build` | **BUILD SUCCESSFUL** — 3 756 pruebas, 0 fallos, 0 errores, 0 omitidas |
| `./gradlew verificarArquitectura` | **BUILD SUCCESSFUL** — 130 pruebas, 0 fallos |
| `./gradlew verificarAislamiento` | **BUILD SUCCESSFUL** — 223 pruebas (46 esquema + 177 pool), 0 fallos |

**El baseline de `sgtm` costó tres corridas, y las dos primeras enseñan algo.** La primera
(`org.gradle.parallel=true`, el valor por omisión del proyecto) cayó con 2 fallos que **no eran del
código**: varios módulos provisionan a la vez contra el **mismo** clúster externo y chocan en el
candado de roles —el defecto que #698 documenta, disparado por su propia condición—. Con
`--no-parallel --max-workers=1`, uno. El que quedaba era del motor, no del árbol: §8.

**Ninguna tarea `test` se dio por `UP-TO-DATE`.** Las dos corridas van con `cleanTest` y
`--no-build-cache`, y se comprobó en el log que los 17 `:test` aparecen ejecutados y ninguno con
`UP-TO-DATE`, `FROM-CACHE` ni `NO-SOURCE`: una tarea que no se ejecuta no demuestra nada
(lección de #192 §2).

---

## 2. Cómo se hizo el renombrado, y con qué se sabe que quedó completo

Un solo guion, con listas explícitas, no archivo a archivo. Dos sustituciones y un movimiento de
directorios:

| Qué | De | A |
|---|---|---|
| Paquetes | `pe.gob.sgtm.*` | `kamayuk.rentas.*` |
| Directorios de paquete | `src/*/java/pe/gob/sgtm/` | `src/*/java/kamayuk/rentas/` |
| Módulos Gradle | `sgtm-<contexto>` | `kamayuk-rentas-<contexto>` |

**La comprobación es un `git grep` que no encuentra nada:**

```
$ grep -rn "pe\.gob\.sgtm\|pe/gob/sgtm" . | grep -v '/build/'
(vacío)
```

Y no lo fue a la primera. **Dos clases de archivo se escaparon del primer barrido** y las dos
las encontró esa comprobación, no una revisión:

- los **`.csv` de casos de regla tributaria** (`RT-013.csv`, `RT-014.csv`), que nombran la clase
  que implementa la regla en su columna `FUERA_DEL_MOTOR`;
- el **descriptor de `ServiceLoader`**, `META-INF/services/kamayuk.comun.verificaciones.ConfiguracionDeLasVerificaciones`,
  que **no tiene extensión** y por eso ninguna lista de extensiones lo alcanzaba. Ése es el peor
  de los dos: sin él, las barreras se quedan sin proveedor y lo que falla no es una prueba de
  negocio sino todas a la vez.

### Lo que sigue diciendo `sgtm`, a propósito

La instrucción es no renombrar nada de Java ni de Gradle salvo paquete y módulo. Se cumple, y
queda esto, que **no es un olvido**:

| Qué | Por qué se queda |
|---|---|
| Roles de base de datos: `sgtm_owner`, `sgtm_app`, `sgtm_readonly` | Son del **clúster**, que los cuatro sistemas comparten. Ya lo dice `CLAUDE.md` de `rentas` |
| Ids de los plugins de convención: `sgtm.calidad`, `sgtm.modulo`, `sgtm.pruebas`, `sgtm.java-base`, `sgtm.pruebas-postgres` | Son identificadores de Gradle |
| Nombres de clase: `SgtmAplicacion`, `ConfiguracionDelSgtm`, `TablasDelSgtm`, `CrucesConsentidosDelSgtm` | Son identificadores de Java |
| `bootJar` → `sgtm.jar`, y el `Dockerfile` que lo copia por ese nombre | El artefacto y su imagen |
| Claves de configuración de Spring: `sgtm.redondeo.*`, `sgtm.implantacion.*`, `sgtm.carga-*`, `sgtm.portal.oidc.*`, y las variables `SGTM_*` | Las lee el código; renombrarlas es cambiar el contrato del despliegue |
| `sgtm-ciudadano` (realm), `s3://sgtm-stg` (bucket de una prueba) | No son módulos. La lista blanca del guion los deja fuera **a propósito** |

Son candidatos a una etapa de limpieza posterior, no a ésta.

### Lo que sí se renombró aunque no fuera paquete ni módulo, y por qué

Dos propiedades, porque **P3 ya las había fijado en `rentas` y su CI las comprueba**:

- `sgtm.java.version` → **`kamayuk.java.version`**. No es cosmético: el paso «La versión de Java
  declarada es la que instala CI» de `backend.yml` hace `sed -n 's/^kamayuk\.java\.version=//p'`
  y, con el nombre viejo, no encuentra nada, compara la cadena vacía con `25` y **falla**.
- `sgtm.pruebas.postgres.*` → **`kamayuk.pruebas.postgres.*`**. El mensaje de error de CI ya
  documenta `-Dkamayuk.pruebas.postgres.url` como la salida sin Docker. Dejar la propiedad con el
  nombre viejo habría dejado en CI **una instrucción falsa**, que cuesta más que una que falta.

---

## 3. La convención de nombres de módulo, y el `rootProject.name`

**Los 17 módulos se llaman `kamayuk-rentas-<contexto>`.** Es la convención del bloque de reglas
(`kamayuk-<sistema>-<contexto>`), y **se aplicó también a los dos módulos que P3 había dejado con
otra forma** — `kamayuk-esquema` y `kamayuk-verificaciones` —, porque lo que no vale es acabar con
`kamayuk-rentas-contribuyentes` al lado de `kamayuk-esquema`.

El motivo de elegir ésta y no la de P3 (`kamayuk-<contexto>`, sin el segmento del sistema) es
**P5C**: cuando `catastro` salga a su repositorio, `kamayuk-rentas-catastro` pasa a
`kamayuk-catastro-<contexto>` cambiando un solo segmento, y en el diff se ve de qué sistema era
cada módulo antes de moverse. Con la forma de P3 esa información no está escrita en ningún sitio.

**Lo que cuesta, dicho aquí: el contexto acotado `rentas` queda en `kamayuk-rentas-rentas`.** Es
feo y es la consecuencia mecánica de la convención; se prefiere la fealdad a una excepción, porque
una excepción en la regla de nombres es lo que hace que la siguiente extracción tenga que
acordarse de ella.

**`rootProject.name` se queda en `kamayuk-rentas-backend`**, que es lo que P3 puso. El enunciado
de la etapa pide `rentas-backend`; el bloque de reglas manda, P3 ya había elegido, y cambiarlo
sólo por seguir la letra del enunciado habría sido pisar trabajo hecho para quedar peor alineado
con el nombre del producto.

---

## 4. Los dos módulos de P3 se fundieron, no se pusieron al lado

`kamayuk-esquema` y `kamayuk-verificaciones` (P3) eran los **andamios** de
`sgtm-esquema` y `sgtm-aplicacion`: las mismas clases con el mismo nombre, configuradas para un
repositorio sin negocio. Ponerlos al lado de los de verdad habría duplicado
`AislamientoMultiTenantTest`, `BaseDeDatosDePrueba`, `MotorPostgres` y `Migrador`, **subido el
número de pruebas** —y el criterio dice literalmente que si sube, algo se duplicó— y, lo peor,
dejado **dos proveedores** de `ConfiguracionDeLasVerificaciones` en el `ServiceLoader`, que es un
fallo ruidoso por diseño («cero proveedores falla y dos también»).

| Andamio de P3 | Queda como | Con el contenido de |
|---|---|---|
| `kamayuk-esquema` | `kamayuk-rentas-esquema` | `sgtm-esquema` (46 pruebas de aislamiento, no 9) |
| `kamayuk-verificaciones` | `kamayuk-rentas-aplicacion` | `sgtm-aplicacion` (las barreras **más** las verificaciones propias del sistema: contrato, formas, respuestas, Modulith) |

Lo que P3 dejó y **no** se tocó: el `includeBuild` hacia `infrastructure/librerias-backend`, el
`require` que comprueba que esté clonado, los nombres de las dos tareas bloqueantes, y que en CI
vayan en pasos separados.

---

## 5. La ruta base: `/api/v1` → `/rentas/api/v1`

**En producción sí era un solo sitio, pero no lo era del todo: eran dos, y lo primero fue
hacerlo uno.**

- `Api.RAIZ` (`kamayuk-rentas-plataforma`) es la constante que usan los 89 `@RequestMapping` /
  `@GetMapping` del backend. Ése es el sitio, y ahí se cambia.
- `SeguridadWeb` **repetía el literal dos veces** (`RAIZ_DE_LA_API`, `RAIZ_DEL_PORTAL`). Ahora los
  **deriva** de `Api.RAIZ`. No es cosmética: si los dos se separan, la cadena de seguridad deja de
  cubrir lo que los controladores publican, **sin error de compilación y sin que ninguna prueba de
  controlador lo note** —cada una monta el suyo—, y el síntoma sería la API entera servida sin
  autenticar.

Comprobación: el único literal de la raíz que queda en `src/main` es el de `Api.java`.

```
$ grep -rn '"/rentas/api/v1' --include='*.java' . | grep '/src/main/'
kamayuk-rentas-plataforma/src/main/java/kamayuk/rentas/web/Api.java:13:    public static final String RAIZ = "/rentas/api/v1";
```

En **pruebas** no era un solo sitio y no se pretende que lo sea: 825 literales de MockMvc en 100
archivos (`post("/api/v1/...")`), más las constantes `RAIZ` de `EndpointsPublicados` y
`ParametrosDeLaConsultaTest`. Sustitución mecánica.

**Lo que NO se rebasó, y por poco se rompe el contrato en silencio.** En
`docs/50-api/generar-openapi.mjs` los literales `ruta: '/api/v1/...'` son un **marcador interno**:
el serializador quita ese prefijo de todas las rutas por igual, porque el camino real lo declara
`servers.url`. Rebasarlos —que es lo que hizo el primer intento— dejó al `replace` sin reconocerlos
y el contrato salió con **el prefijo dentro y duplicado**: 71 operaciones con rutas como
`"/rentas/api/v1/indicadores/trabajo-parado"` bajo un `servers.url` que ya era `/rentas/api/v1`.
Lo cazaron `ContratoDeApiTest` y `RespuestasDeLaApiTest` en las dos direcciones. Se revirtió, y
queda un comentario en el generador diciendo por qué esos literales no se tocan.

**El contrato de `rentas` difiere del de `sgtm` en dos líneas y ninguna más** —el `servers.url` y
una frase que nombra la ruta del portal—, que es la prueba de que el rebase no arrastró nada:

```
$ diff sgtm/docs/50-api/openapi/sgtm-v1.yaml rentas/docs/50-api/openapi/rentas-v1.yaml
24c24 <   - url: /api/v1        >   - url: /rentas/api/v1
175c175 < …`/api/v1/portal/**`… > …`/rentas/api/v1/portal/**`…
```

`node docs/50-api/generar-openapi.mjs --comprobar` → **225 operaciones en 202 rutas**, y está
enganchado en `documentacion.yml`.

---

## 6. Una regla compartida tuvo que dejar de suponer la raíz

`ConElCentinelaDelCiudadanoSoloEnElPortal`, en `comun-verificaciones`, comprobaba que un
controlador con `@RequiereAcceso(CIUDADANO)` cuelgue de **`/api/v1/portal`**, con el literal
escrito a mano. Con la raíz de `rentas` ya no casa con nada, así que la regla **acusaba al único
controlador que sí está bien puesto** (`PortalController`). No es que se relajara: se convirtió en
un falso positivo.

Se parametriza: `ConfiguracionDeLasVerificaciones` gana `raizDeLaApi()` **con valor por omisión
`/api/v1`**, y `rentas` lo sobreescribe devolviendo `Api.RAIZ`. El valor por omisión no es pereza:
es lo que permite que **`sgtm` siga compilando sin tocar una línea**, que es un no-negociable de
esta etapa. Comprobado: `sgtm/backend/./gradlew verificarArquitectura` → `BUILD SUCCESSFUL`, 0
fallos, con `git status` de `sgtm` limpio.

---

## 7. El esquema: una migración, no 68

`kamayuk-rentas-esquema/src/main/resources/db/migration/` tenía las 68 migraciones de `sgtm`
(`V1`…`V78`, con huecos) y ahora tiene **una**: `V1__baseline.sql`, la de
`sgtm/docs/40-datos/baselines/rentas/`, generada en P0B. Son las 132 tablas del esquema completo
de hoy, porque en esta etapa `rentas` es el monolito y las necesita todas.

Sigue siendo **Flyway**, no un `esquema.sql` suelto, y el motivo está escrito en la cabecera del
propio archivo: el checksum sobre DDL ya aplicado, el Job de implantación que espera consultando
`flyway_schema_history`, y que las pruebas de persistencia corren las migraciones reales contra un
motor real.

---

## 8. `verificarAislamiento`: contra un motor real, **no por Testcontainers**

**Éste es el hueco que hay que leer con cuidado.**

El túnel de Docker volvió a estar arriba durante la sesión, pero **Testcontainers no sirve desde
esta máquina**, y no es una suposición — se midió:

```
$ docker run -d --rm -p 55999:5432 … postgres:16
$ docker ps   → sonda-tc  Up 13 seconds  0.0.0.0:55999->5432/tcp
$ nc -z -w 5 127.0.0.1 55999   → NO ALCANZA
```

El demonio es un túnel a un VPS: el contenedor arranca allí y el puerto se publica allí.
`getJdbcUrl()` devolvería un `localhost:<puerto>` que desde aquí no alcanza nada, y el síntoma
sería un *timeout* de conexión **después** de que el contenedor arrancó bien.

Así que se usó el repliegue documentado, con una diferencia respecto de P3 y P4 que conviene
anotar porque **cambió un resultado**: se levantó un **clúster propio** de PostgreSQL 16.15 en
`127.0.0.1:55444`, creado con `initdb --auth-host=scram-sha-256`. El PostgreSQL local que P3 y P4
usaron (`:55432`) tiene `pg_hba.conf` en **`trust`** para *loopback*, y con `trust` **cualquier
clave vale**: `ProvisionamientoCompartidoTest.elMensajeNombraLaCausa` (#698) —que conecta con una
clave mala y exige un `28P01`— **no puede pasar**, porque no se produce ningún error. Contra el
clúster con `scram`, una clave mala se rechaza de verdad y la prueba mide lo que dice medir.

> **Lo que esto significa para CI:** `verificarAislamiento` corrió contra **un motor de verdad**,
> con RLS, `FORCE ROW LEVEL SECURITY` y los cuatro roles reales, pero **no por el camino de
> Testcontainers**, que es el que corre en CI con `postgis/postgis:16-3.4-alpine`. Lo que **no**
> queda demostrado en esta máquina es el arranque del contenedor y la imagen concreta. Es el mismo
> hueco que declararon P3 y P4, medido esta vez en lugar de heredado.

No se tocó el `pg_hba.conf` del motor de la máquina: es configuración de sistema, compartida con
otras sesiones, y el clúster propio consigue lo mismo sin tocarla.

---

## 9. Los cuatro huecos declarados

### 9.1 `design/` no viaja, pero su entrada del generador sí — y es una desviación

`generar-openapi.mjs` **deriva** el contrato de `design/sgtm-data-{1..5}.js`, y `design/` está en
la lista de lo que no se copia. Las dos instrucciones no se pueden cumplir a la vez: sin esos
cinco archivos, el generador es código muerto y `--comprobar` en CI es imposible, que es
exactamente el defecto de #312 —un contrato válido, cumplido por el backend, y que nadie puede
reproducir—.

**Decisión:** viajan los **cinco archivos de datos** (312 KB de los 12 MB de `design/`), a
`docs/50-api/prototipo/`, con nombre propio y fuera de `design/`, declarados en el encabezado del
generador como lo que son: **su entrada**. No viaja nada más de `design/` —artboards, handoff,
design system—: eso es de la interfaz y es de otra etapa.

### 9.2 `infra/` no viaja, pero catorce pruebas leen sus CSV

`ArchivosDeEjemploTest` (7), `ArchivoDeContribuyentesDeEjemploTest` y
`CarteraCuadraConLaConsultaJdbcTest` leen `infra/carga-de-datos/ejemplos/*.csv` **por el
analizador de verdad**. Dejarlos atrás no habría quitado un guion de despliegue: habría puesto
catorce pruebas en rojo y bajado el número que este criterio compara.

**Decisión:** viaja `infra/carga-de-datos/ejemplos/` y su `README.md` (64 KB), **en su ruta
original** para no renombrar la constante de cuatro pruebas. No viajan los guiones
(`sembrar-demostracion.sh`, `cargar-cajas.sh`, `publicar-parametros.sh`…) ni el descriptor de
Pulumi. El README lleva una nota al principio diciendo justo eso, porque un README que describe
guiones que no están es un documento falso.

### 9.3 `documentacion.yml` pierde un paso, y se dice cuál

El sexto paso de `sgtm` —`python3 scripts/catastro/importar_predios_gpkg.py --autoprueba`— **se
retira**. `scripts/` no viaja en P5A y el importador del plano es de `catastro`: se va con él en
P5C. Se retira en vez de dejarse apuntando a un archivo que no existe, porque un paso que falla
por no encontrar su guion es indistinguible de uno que falla porque el guion está mal. Queda
escrito en el propio workflow.

Los otros ocho pasos **se ejecutaron aquí uno a uno** y pasan. Dos de ellos no pasaban al
principio: `verificar-valores-normativos.mjs` y `verificar-publicacion.mjs` apuntaban a
`backend/sgtm-esquema/…` y a `backend/sgtm-dominio-compartido/…/UnidadDePlazo.java`, porque el
barrido de módulos no cubría los `.mjs`.

### 9.4 Lo que ya estaba roto y viaja roto: el `Dockerfile`

El `Dockerfile` construye con el contexto en la raíz del repositorio y **no copia
`infrastructure/librerias-backend`**, así que `settings.gradle.kts` se para en su `require`. Es un
hueco **heredado de P3** —está igual en `sgtm`— y no se arregla aquí porque no tiene arreglo local:
el composite build vive en un repositorio hermano, fuera del contexto de la imagen. Sale de
publicar la librería, de un contexto multi-repositorio o de volver a empotrarla; es una decisión, no
un descuido, y no es de esta etapa.

---

## 10. `git log` no da 1, y no debe

El criterio pide `git log --oneline | wc -l == 1`. **En `rentas` no aplica**, y no se fuerza:
el repositorio ya trae los commits de P1C, P3 y P4, y reescribir su historia para que dé 1
destruiría el rastro de tres etapas ya entregadas para satisfacer una cifra.

La historia real de `rentas` es **`first commit` → el descriptor y su CI (P1C) → las dos barreras
(P3) → el contexto de agente (P4) → esta etapa**, cinco etapas y algún commit de corrección
encima. El número que `git log --oneline | wc -l` devuelva es el de verdad, no un `1` fabricado.

El `Origen: sgtm@0d33ad7b` sí va, en el mensaje del commit de esta etapa.

---

## 11. Lo que se actualizó porque había dejado de ser cierto

P4 dejó documentos que el backend vuelve falsos. Se corrigieron, porque **una instrucción falsa
cuesta más que una que falta**:

| Archivo | Decía | Dice |
|---|---|---|
| `CLAUDE.md` | «`kamayuk-verificaciones`… **79 pruebas**… cero clases de negocio»; «Código de negocio: **NO existe. Ni una clase**»; «Su esquema **NO está aquí**» | Los 17 módulos, el baseline puesto y los números de verdad |
| `README.md` | «dos modulos y **cero clases de negocio**» | Lo mismo |
| `docs/D0-desarrollo/pruebas.md` | 79 y 9 pruebas; `:kamayuk-verificaciones:test` | Los números y los nombres de módulo de hoy |
| `docs/D0-desarrollo/entorno-local.md` | «Corre **79 pruebas**… sin Docker, sin base y sin red» | Lo mismo, y el motor que ahora sí hace falta |
