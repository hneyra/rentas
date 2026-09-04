# Backend del SGTM

Spring Boot 4 sobre Java 25, multi-módulo, monolito modular con Spring Modulith
([ADR-0001](../docs/30-arquitectura/adr/ADR-0001-plataforma-backend.md),
[ADR-0003](../docs/30-arquitectura/adr/ADR-0003-monolito-modular.md)).

**Qué hay:** el esquema completo como migraciones Flyway (hoy, 48, hasta `V57`), el camino del
contexto de tenant (token → `SET LOCAL` → RLS), las verificaciones bloqueantes y **negocio real
en los doce contextos acotados** — del catastro versionado y la caja a la cobranza coactiva.
**Qué no hay:** ninguna regla de cálculo con cifras normativas sin fuente — lo bloquean D-11 y
los cuadros de GOB-03 (ver «Qué falta») —, y es deliberado: las barreras se construyeron primero.

## Comandos

```bash
./gradlew build                   # todo, incluidas Spotless, Checkstyle y NullAway
./gradlew verificarAislamiento    # aislamiento multi-tenant. Bloqueante
./gradlew verificarArquitectura   # ArchUnit, escaner de fuentes y Spring Modulith. Bloqueante
./gradlew spotlessApply           # arregla el formato en vez de solo reprocharlo
```

## Arrancarlo

La instalación completa —motor, migración y aplicación— vive en
[`despliegue/`](../despliegue/README.md):

```bash
cd ../despliegue
cp .env.ejemplo .env          # y poner claves generadas, una distinta por rol
docker compose up --build --wait aplicacion
```

Lo que hay que entender antes de tocarlo: **la aplicación no migra**. Arranca con
`spring.flyway.enabled: false` y se conecta como `sgtm_app`, que no tiene DDL. Quien migra es
[`Migrador`](kamayuk-rentas-esquema/src/main/java/kamayuk/rentas/esquema/Migrador.java), en su propio
contenedor, como `sgtm_owner`, y termina antes de que la aplicación arranque. Es el **mismo**
código que provisiona la base de cada prueba de persistencia: si el despliegue migrara por su
cuenta, lo verificado en CI y lo desplegado en la municipalidad dejarían de ser lo mismo.

La cadena de seguridad está escrita, no heredada:
[`SeguridadWeb`](kamayuk-rentas-plataforma/src/main/java/kamayuk/rentas/plataforma/SeguridadWeb.java) deja
público `/actuator/health`, exige token en `/api/v1/**` y **niega** todo lo demás. Del token salen
**dos** contextos, uno por filtro y por el mismo camino: la municipalidad —`TenantContextFilter`,
de ahí al `SET LOCAL`— y quién hace la petición —`OrigenContextFilter`, de ahí a la auditoría—.

El segundo no existía, y su ausencia no se veía: nueve sitios leen `OrigenContext.actual()` y
nadie lo fijaba, así que la primera petición autenticada del sistema devolvió 500. Es el patrón
que conviene recordar al añadir infraestructura: no faltaba una barrera, faltaba **el camino**, y
eso solo se ve recorriéndolo entero.

**Si el build se queja del formato, no lo pelees: `spotlessApply`.** Checkstyle no revisa formato
a propósito, para no discutir con el formateador. Lo que sí revisa, y es fácil de incumplir con el
teclado en español, son los **identificadores con tilde**: `alicuota`, nunca `alícuota`.

## Pruebas que necesitan PostgreSQL

Necesita un **PostgreSQL real** todo módulo que arranca la base con los fixtures de
`kamayuk-rentas-esquema` — el criterio es su `build.gradle.kts`: declara
`testImplementation(testFixtures(project(":kamayuk-rentas-esquema")))` —, más el propio `kamayuk-rentas-esquema`.
Hoy son casi todos: con V56, 14 de los 17 módulos. Una base en memoria no tiene Row Level
Security y daría falsos verdes (CAL-01 §2).

Por omisión levantan un contenedor con Testcontainers, así que hacen falta Docker y la imagen
`postgis/postgis:16-3.4-alpine` (ADR-0021: `crear-roles.sql` instala PostGIS y la
imagen oficial no la trae).

**Sin Docker** hay una salida documentada —apuntar a un PostgreSQL existente—, y ninguna que omita
la prueba:

```bash
./gradlew verificarAislamiento \
  -Dkamayuk.pruebas.postgres.url=jdbc:postgresql://localhost:5432/postgres \
  -Dkamayuk.pruebas.postgres.usuario=postgres \
  -Dkamayuk.pruebas.postgres.clave=…
```

El usuario debe ser superusuario: la prueba crea los cuatro roles, les asigna su clave y crea una
base nueva por corrida. También sirven las variables de entorno equivalentes
(`SGTM_PRUEBAS_POSTGRES_URL`, …) y `-Dkamayuk.pruebas.postgres.imagen` para cambiar la imagen.

**Qué garantiza este camino, y qué no.** Cada tarea `test` crea su propia base, y eso aísla las
migraciones y los datos sembrados. Lo que **no** es de la base son los **roles**: `ALTER ROLE` vale
para todo el clúster (INF-01 §4.1), así que las cuatro filas de `pg_authid` las comparten todas las
corridas que apunten al mismo motor. Por eso:

| Garantiza | No garantiza |
|---|---|
| Varias tareas `test` **de la misma corrida** en paralelo (`org.gradle.parallel=true` es el valor de este build). La clave del rol **se deriva** del clúster y de la credencial con que se provisiona, así que las dos escriben lo mismo, y el provisionamiento entero se serializa con un candado de asesoramiento del propio motor (#698) | Que convivan con una corrida que traiga **otro código** —una rama anterior a #698, con su clave aleatoria por tarea— o **otra credencial de superusuario**: esas siguen pisando la clave. Lo que cambia es que el fallo **nombra la causa** en vez de salir como `password authentication failed` |
| Que no haga falta `--max-workers=1`: la orden más natural —nombrar los dos módulos que tu cambio toca— ya no falla de forma intermitente | Que el clúster quede como estaba. Los roles, las extensiones y sus claves se reescriben: apuntar esto a un motor **en servicio** deja fuera a la aplicación, y eso no lo arregla ninguna de las dos cosas de arriba |

Con Testcontainers nada de esto aplica —cada tarea `test` levanta su propio motor—, así que es un
detalle exclusivo de esta salida de emergencia. La clave derivada tampoco es un secreto versionado:
no está en el repositorio, sale de la credencial de superusuario que ya hace falta para provisionar,
y cambia con el clúster —cada `initdb`, y por tanto cada contenedor, tiene su propio identificador—.

**La base de prueba declara su codificación; no hereda la del clúster** (#706). `CREATE DATABASE`
a secas la toma de `template1`, y esa la fija `initdb` con el *locale* del entorno: basta que quien
creó el clúster no tuviera `LANG` puesto para que quede en `SQL_ASCII`. Contra uno así, el rango de
prefijo del catálogo vial —que cierra con `chr(1114111)` (#565, V66)— falla con
`requested character too large for encoding`, y lo que se ve son cinco rojos en
`BusquedaDelCatalogoVialTest` hablando de Unicode. Por eso la base se crea con
`TEMPLATE template0 ENCODING 'UTF8' LC_COLLATE 'C.UTF-8' LC_CTYPE 'C.UTF-8'`, y el arranque
comprueba que salió en UTF-8 y **dice cuál es la del anfitrión** si no. Si la intercalación no está
instalada en el sistema, la creación falla nombrándola: hay que instalarla (`locale-gen`), **no**
replegarse a `C` —con ese tipo de carácter `lower` y `upper` dejan de conocer la `ñ`, y el filtro
por uso de la ficha catastral devuelve cero filas sin decir por qué—.

**Sin motor, las pruebas fallan; no se saltan.** Una prueba bloqueante que se omite a sí misma
deja el build en verde sin haber verificado nada.

## Integración continua

Todo pull request corre [`.github/workflows/backend.yml`](../.github/workflows/backend.yml), que
ejecuta **los mismos tres comandos de arriba**, sin atajos y en pasos separados: cuando algo se
rompe, el nombre del paso ya dice qué barrera cayó.

Tres cosas se comprueban **antes** de las verificaciones, para que un fallo de infraestructura no
se disfrace de fallo del código:

| Comprobación | Qué evita |
|---|---|
| La distribución de Gradle se descarga con reintentos | Que un 502 de `services.gradle.org` salga como build rojo, y que relanzar se vuelva la respuesta a cualquier rojo |
| `gradle.properties` declara Java 25 | Que CI verifique en silencio una versión de Java distinta de la que se despliega |
| Hay Docker y `postgis/postgis:16-3.4-alpine` se descarga | Que un runner sin Docker se confunda con un fallo de aislamiento, que es el rojo que menos puede confundirse con otra cosa |

Ninguna de las tres omite nada: sin Docker el job **falla**. La salida documentada es apuntar a un
PostgreSQL existente, no saltarse la prueba.

Cuando algo termina en rojo, los informes de pruebas y de Checkstyle quedan como artefactos de la
corrida.

El frontend tiene el suyo, [`frontend.yml`](../.github/workflows/frontend.yml), y no incluye al
backend a propósito: estas verificaciones necesitan Docker y un PostgreSQL de verdad.

## Módulos

```
kamayuk-rentas-dominio-compartido   Objetos de valor (kamayuk.rentas.dominio) y TenantContext. Sin Spring
kamayuk-rentas-esquema              Migraciones Flyway + la prueba de aislamiento. Sin Spring
kamayuk-rentas-plataforma           Filtro del token, SET LOCAL por transaccion, guardia del pool,
                          el patron de repositorio (kamayuk.rentas.persistencia), la
                          auditoria de ADR-0008 (kamayuk.rentas.auditoria), la capa web
                          comun (kamayuk.rentas.web) y el guardia de acceso
                          (kamayuk.rentas.autorizacion)
kamayuk-rentas-indicadores          El panel de recaudacion (#56). NO es un contexto acotado:
                          agrega lo que cuentacorriente y tesoreria ya publican
sgtm-<contexto> × 12      Los contextos acotados de ARQ-01 §3
kamayuk-rentas-aplicacion           Ensambla, y aloja ArchUnit, el escaner y Spring Modulith
```

Los doce contextos son `contribuyentes`, `catastro`, `rentas`, `parametros`, `fiscalizacion`,
`sanciones`, `cuentacorriente`, `tesoreria`, `valores`, `coactiva`, `licencias` y `seguridad`.
La estructura se creó vacía a propósito —fijar los límites antes de que hubiera código que los
cruzara—, y hoy **los doce tienen código de negocio**: el estado por contexto, con lo que cada
uno publica y lo que le falta, está en [ARQ-01 §5](../docs/30-arquitectura/contextos-acotados.md).
El de más recorrido sigue siendo `catastro`, que nació como repositorio de ejemplo del patrón de
persistencia —elegido porque no arrastra ninguna regla de cálculo y sí tiene `municipalidad_id` y
política RLS, que es lo que había que demostrar— y hoy publica también la escritura versionada de
las fichas.

`kamayuk-rentas-dominio-compartido` contiene **dos** paquetes, y la separación importa:

| Paquete | Qué hay | Por qué separado |
|---|---|---|
| `kamayuk.rentas.dominio` | `Dinero`, `Periodo`, `Alicuota`, `Porcentaje`, `AreaM2`, `Ejercicio`, `MunicipalidadId`, `CodigoContribuyente`, `Placa`, `DocumentoIdentidad`, `Observacion`… y las demás del paquete (hoy, 26 clases; la lista es el propio directorio) | Es dominio: le aplican sin excepción las reglas de ArchUnit —la lista vive en `kamayuk-rentas-aplicacion/…/verificaciones/ReglasDeArquitectura.java`— |
| `kamayuk.rentas.compartido` | `TenantContext`, `Paginacion`, `Pagina` | Son utilidades técnicas —un `ThreadLocal`, el paginado—; no son vocabulario tributario |

El paquete de dominio cuelga de `kamayuk.rentas` y **no** de `kamayuk.rentas.compartido` por una razón
concreta: para Spring Modulith un subpaquete es interno a su módulo, y un objeto de valor que
ningún contexto puede importar no sirve de vocabulario común. Como módulo propio queda expuesto
sin anotar el paquete, y así este módulo Gradle sigue sin depender de Spring —ni siquiera de una
anotación—, que es la regla 7 en su forma más literal.

## Convenciones del build

Las convenciones viven en `buildSrc/` como plugins precompilados, **no** en un bloque
`subprojects {}`: un módulo debe declarar qué convenciones aplica.

| Plugin | Qué aporta |
|---|---|
| `sgtm.java-base` | Toolchain de Java 25, `-Xlint:all`, JUnit Platform |
| `sgtm.calidad` | Spotless (formato), Checkstyle (nombres y trampas), NullAway (nulidad) |
| `sgtm.pruebas` | JUnit y AssertJ. **No** trae Testcontainers, a propósito |
| `sgtm.pruebas-postgres` | Reenvía las propiedades del motor externo al proceso de prueba |
| `sgtm.modulo` | Convenciones de un contexto acotado: BOM de Spring y dominio compartido |

## Base de datos

La aplicación se conecta **siempre** como `sgtm_app`: sin DDL, sin `BYPASSRLS`, sin ser
propietaria de las tablas y **sin `DELETE`**. Las migraciones las ejecuta el proceso de despliegue
como `sgtm_owner`; la aplicación **no migra al arrancar** (`spring.flyway.enabled: false`).

Los roles se crean antes de la primera migración con
[`db/roles/crear-roles.sql`](kamayuk-rentas-esquema/src/main/resources/db/roles/crear-roles.sql), que no es
una migración de Flyway. Las claves no están ahí: los roles se crean sin `LOGIN` y quien
provisiona el ambiente asigna la clave desde su gestor de secretos.

Detalle del esquema: [`kamayuk-rentas-esquema/README.md`](kamayuk-rentas-esquema/README.md) y
[DAT-01](../docs/40-datos/modelo-logico-fisico.md).

## Qué falta

- Toda regla de cálculo tributario. Bloqueada por D-02 —los valores normativos, hoy partida en
  D-02a/b/c— y por D-03c, los puntos de redondeo
  ([GOB-02](../docs/00-gobierno/decisiones-abiertas.md)).
- La verificación de que la municipalidad activa está entre las autorizadas del usuario: falta
  fijar el nombre de ese claim (D-06). Hasta entonces, un usuario, una municipalidad.
- El mecanismo que escribe la auditoría (disparadores o aspecto). Se decide con el primer caso de
  uso de escritura; ver [DAT-02 §4](../docs/40-datos/auditoria-e-historico.md).
- Las particiones de los ejercicios siguientes a 2027, y su automatización.
- El acto de **enrolamiento del ciudadano en ventanilla** (D-15, camino B): el realm
  `sgtm-ciudadano` existe y se reconcilia, y las cuentas se declaran como las de los funcionarios
  (ADR-0012). Lo que falta es el acto del back-office que las da de alta con el documento delante.
