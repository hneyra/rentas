# `rentas` — Contexto para agentes

Contribuyentes, declaraciones juradas, determinación, cuenta corriente, valores, fiscalización,
coactiva, sanciones y licencias. **Es quien decide cuánto se debe.**

Uno de los cinco repositorios de **Kamayuk**, el producto multi-municipal que reimplementa el
sistema documentado en el manual de usuario del SGTM de la Municipalidad Provincial de Sullana.
El reparto lo decide
[ADR-0029](https://github.com/hneyra/infrastructure/blob/main/docs/30-arquitectura/adr/ADR-0029-cuatro-sistemas-separados.md);
qué tabla fue a qué repositorio y por qué, [GOB-05](https://github.com/hneyra/sgtm/blob/migracion-a-microservicios/docs/00-gobierno/inventario-del-corte.md).

## Qué hay hoy, medido y no supuesto

| Pieza | Estado |
|---|---|
| `infrastructure/` — el descriptor de despliegue | **Existe.** `yarn verificar` en verde, sin Pulumi, sin token y sin clúster |
| `backend/` — **17 módulos** | **Existe, y la resta terminó (P5E).** `./gradlew build` en verde: **3 080 pruebas**, 0 fallos. Es el monolito modular menos la interfaz, menos los valores normativos (P5B), menos el catastro (P5C) y menos la ventanilla (P5D). De los 17 módulos, **quince son de este sistema** y dos —`kamayuk-rentas-catastro` y `kamayuk-rentas-parametros`— son **adaptadores cliente**: puertos y transporte, sin dominio y sin una sola consulta a una tabla ajena |
| `backend/kamayuk-rentas-seguridad` — la copia local de la autorización | **Ya no administra a nadie (ADR-0039, etapa 4; `identidad`#4).** Las once escrituras de administración —altas, bajas, reactivaciones y vigencias de grupos y usuarios, la afiliación y las dos matrices de permisos—, sus dos repositorios, tres controladores y **diecinueve rutas** se fueron a `identidad`, que es el dueño de la autorización; con ellas salen del catálogo las opciones `usuarios`, `grupos`, `miembros` y `permisos` (**134 → 130**, y el contrato **181 → 162** operaciones publicadas). Lo que queda es **la copia local**, y **desde la etapa 5 este sistema no siembra ni una fila de ella** (`identidad`#5): `SembradorDelCatalogo` —lo que era `SembradorDeLaCopiaLocal`— siembra sólo el **catálogo**, `modulo_sistema` y `acceso`, o sea qué pantallas existen; el grupo, el administrador, su afiliación y sus permisos **llegan por el buzón**, y `ImplantarMunicipalidad` corre esa pasada **en línea** y **falla nombrando lo que falta** si no hay buzón, si no contesta, o si contesta y no trae ni una cuenta — nunca `Complete` con cero usuarios. `AplicarUnEventoDeIdentidad` la actualiza con lo que llega por el buzón de `identidad` (`GET /identidad/api/v1/eventos/pendientes` → una transacción por evento con `SET LOCAL` → acuse DESPUÉS del commit), y `CorrerElConsumidorDeIdentidad` lo corre cada cinco minutos desde un `CronJob` —y **se aparta en el `Job` de implantación**, porque ahí la pasada ya la hizo la implantación en línea—. **Las seis lecturas de `LecturaDeLaCopiaLocalJdbc` llevan `@Transactional(readOnly = true)`, y no es decorativo**: sin transacción no hay `SET LOCAL` y la política RLS evalúa `''::bigint` — medido con las cinco aplicaciones levantadas, `GET /seguridad/modulos` y `/accesos` contestaban **500** (`identidad`#4, AC-5/AC-6). **El canal del responsable NO tiene que ser http(s)**: los ambientes declaran un correo, el aviso se registra siempre con ERROR y además se entrega por `POST` cuando se puede; y un evento pospuesto de más de **15 minutos** avisa una vez por corrida, con la corrida en rc=0. El guardia **no cambia**: sigue autorizando contra su propia base. `GET /seguridad/modulos`, `GET /seguridad/accesos`, la sesión entera y las doce rutas de `YA_SERVIDAS` de la interfaz **siguen aquí**. La regla 12 se vigila desde la etapa 4, y **desde la 5 declara UN escritor y sin fecha de fin**: `AplicarUnEventoDeIdentidad`. El del sembrador se retiró con sus cuatro `INSERT`, y su rojo lo confirmó —#27 nombra la entrada que ya no exime a nadie— |
| `backend/kamayuk-rentas-parametros` | **Ya no publica ningún valor normativo**: eso es de `normativa` desde P5B (ADR-0025). Lo que queda aquí es el **cliente** —`LectorDeParametros` con la misma firma de siempre, leyendo de la copia local de un conjunto sellado (`V3`)— y las reglas puras, que todavía viven en los dos repositorios (hueco declarado: `normativa/docs/00-gobierno/P5B-extraccion.md` §7.1) |
| **Un repositorio hermano más** | Desde P5B, `./gradlew test` **no pasa sin `normativa` clonado al lado**: tres clases comprueban que la llave con que su derivado publica un valor es la que este backend pide (#192). Si no está, fallan nombrando el `git clone`; no se saltan |
| `backend/kamayuk-rentas-esquema` | **Existe, con su baseline y ocho migraciones más.** `V1` nació con 132 tablas; `V2`, `V6` y `V7` retiran las **31** que se fueron a los otros tres sistemas, y `V3`…`V5`, `V8` y `V9` traen las **11** que nacen del corte —la copia sellada de normativa, la proyección de catastro, la valuación recibida, el buzón de pagos y su procedencia—. **113 tablas vivas.** `verificarAislamiento` corre **222 pruebas** —45 del esquema y 177 del pool—, 0 fallos |
| Las extensiones que pide su base | **Dos, y no cuatro** (P5E): `pg_trgm` y `unaccent`, las dos *trusted*. `postgis` y `btree_gist` salieron de `crear-roles.sql` porque nada del esquema final las usa —medido: ni una columna PostGIS, ni un índice GiST, ni una restricción `EXCLUDE`— y lo que las mantenía vivas era `V1`, que creaba la geometría del predio para dejarla caer en `V6` |
| `backend/kamayuk-rentas-aplicacion` | **Existe.** `verificarArquitectura` corre **176 pruebas**: las barreras de la librería común más las propias de este sistema (contrato, formas, respuestas, límites de Modulith) |
| `docs/30-arquitectura/adr/` | **Existe**, 11 ADR propios más los que enlaza |
| **Código de negocio** | **Existe.** Llegó entero en P5A ([P5A](docs/00-gobierno/P5A-copia-del-backend.md)), copiado de `sgtm@0d33ad7b` con el mismo número de pruebas: 3 756 = 3 756. Lo que sigue calculando **da el mismo céntimo**: dos archivos comparados byte a byte contra el árbol anterior a P5C, con las mismas huellas que P5B y P5C publicaron ([P5E](docs/00-gobierno/P5E-cierre.md) §4) |
| **La lista de cruces de frontera** | **Vacía en los cuatro repositorios** ([P5E](docs/00-gobierno/P5E-cierre.md) §2). Es el criterio de que la separación terminó: no que los repositorios existan, sino que ninguno lea por SQL una tabla que ya no es suya. Y está medido que la regla puede fallar, repositorio por repositorio |
| Su esquema (`V1__baseline.sql`) | **Está aquí**, en `backend/kamayuk-rentas-esquema/src/main/resources/db/migration/`. Es una migración de Flyway y no un `esquema.sql` suelto (ADR-0032 §2) |
| Su frontend (`rentas-web`) | **Existe, y esta EN MEDIO de una reimplantacion.** Conviven dos interfaces: la **V6**, que es la que se sirve, y la **V8**, que se esta construyendo y todavia no monta. Vite 7 + React 19.3 + TypeScript 5.9 en `frontend/`, con el codigo en `frontend/src/` —y no en un monorepo, porque `verificar-fila-del-registro.mjs` declara `/^frontend\/src\//` como codigo de produccion—. `yarn verificar` encadena lint, tipos y pruebas: **1 296 pruebas en 53 archivos**, 0 fallos; `yarn build` produce **321 kB de JS y 45 kB de CSS**. Cifras medidas en la CI de `main` el 2026-09-13, no arrastradas.<br><br>**Y NO INSTALA SIN SU CLON HERMANO.** `frontend/package.json` declara cuatro `link:../../kamayuk-lib/paquetes/*` —`@kamayuk/{api,formato,sesion,ui}`— que yarn resuelve **contra el disco**. Si falta: `git clone https://github.com/hneyra/kamayuk-lib ../../kamayuk-lib`. Ojo: `yarn install --frozen-lockfile` con el hermano ausente sale con **codigo 0** y deja enlaces colgantes; quien lo dice es `verificaciones/enlace-con-kamayuk-lib.test.ts`, y lo dice nombrando el `git clone`.<br><br>**Lo que es V6, y se va** (#90): `src/estilos/`, `src/ds/`, `src/marco/`, `src/secciones/`, `src/aplicacion.tsx`, `src/datos/prototipo.ts` y `e2e/`. Es la interfaz completa de hoy —marco con barra, arbol de diez modulos y cuarenta destinos, pestanas, paleta `Ctrl/Cmd+K`, y las cuatro secciones del modulo «Rentas · Registro»—, con un **proxy que sustituye `globalThis.fetch`** detras de `VITE_KAMAYUK_PROXY_DE_DATOS`. **Doce** operaciones salen a la red de verdad (`datos/servidas.ts`, `YA_SERVIDAS`, medido); el resto las contesta el proxy con las cifras del artboard V6. **No construir nada nuevo aqui.**<br><br>**Lo que es V8, y se queda**: `diseno/RentasV8.dc.html` mas su hoja de tokens, vendorizados (#78, #80); `src/pantallas/` con **las 40 pantallas como dato tipado**, el arbol de 10 modulos y 40 hojas, y **el interprete** que las dibuja con las piezas de `@kamayuk/ui` (#86, #89). Lo que ata todo eso al artboard son cuatro guardas: la paleta token a token, las pantallas **campo por campo**, que toda clase de Tailwind **produzca una regla** —y no solo este escrita— (#91), y que el interprete **no nombre ningun sistema**, porque esta destinado a `@kamayuk/ui`.<br><br>**Lo que falta para que la V8 se sirva**: el armazon (`@kamayuk/shell`, en `kamayuk-lib`#13) y el cambio de guardia (#90), que conecta Tailwind —hoy no se procesa: su *preflight* le cambiaria la cara a la V6— y retira lo de arriba.<br><br>**Y desde I-1 (#24) e I-3 (#31) esta CONECTADO**: entra con codigo de autorizacion y PKCE contra Keycloak. El arbol del marco no es la constante del artboard: lo compone `marco/composicion.ts` de `GET /seguridad/modulos`, y **lo que la cuenta no puede abrir no se ofrece en ninguna de las tres listas ni por el hash**. **Y desde I-2 (#28) tiene ARNES de extremo a extremo** (`frontend/e2e/`, Playwright sobre Chromium, `yarn e2e`): 21 caminos contra la instalacion levantada, que entran por el formulario de Keycloak de verdad. No corre en `yarn verificar` ni en CI: lo primero a proposito (AC8) y lo segundo declarado en `frontend.yml`. **Se rehace con el cambio de guardia** |
| Su imagen `ghcr.io/hneyra/kamayuk-rentas` | **NO existe.** El descriptor la nombra igual, y es correcto: aquí no se despliega nada |

**Las barreras se construyeron primero, a propósito**, y el negocio entró después por encima de
ellas. Lo que hoy vigilan es real: 3 080 pruebas sobre los contextos que quedan.

**Lo que este repositorio NO tiene todavía, y hay que saberlo antes de tocar la frontera**: los
clientes HTTP hacia `catastro`, `normativa` y `caja` viven **aquí** y no los publica el dueño de
cada API, que es lo que ADR-0030 §4 pide. La decisión y sus tres motivos medidos están en
[P5E §6](docs/00-gobierno/P5E-cierre.md); el orden para cerrarla no admite otro: `comun-dominio`
(D-23) → contrato derivado en cada dueño → `<sistema>-cliente` con su prueba de contrato.

## Lo que este repositorio NO hace

- **No valoriza un predio.** Eso es `catastro` ([ADR-0024](docs/30-arquitectura/adr/ADR-0024-la-frontera-del-calculo.md)):
  aquí llega un valor ya calculado y sellado, y sobre él se aplican tramos, deducciones y alícuotas.
- **No sella un valor normativo.** Eso es `normativa`; aquí se **consume** un conjunto ya sellado,
  una vez por corrida y no una vez por predio.
- **No recibe dinero.** Eso es `caja` ([ADR-0026](docs/30-arquitectura/adr/ADR-0026-el-camino-del-dinero.md)):
  aquí se emite la orden de cobro y se imputa el abono cuando llega.
- **No administra la autorización, y desde la etapa 5 tampoco la siembra.** Eso es `identidad`
  (ADR-0039): aquí no se da de alta un usuario, no se afilia a nadie y no se fija un permiso. Lo
  único que este sistema siembra al implantar es **su catálogo** —`modulo_sistema` y `acceso`, que
  no dicen a quién se le concede nada sino qué pantallas existen—; el resto llega por el buzón, y
  sin él la implantación falla en vez de terminar en verde. Lo que sí hace es **autorizar contra su
  copia local**, y eso no cambia.
- **No decide la etiqueta de su imagen, ni su namespace, ni sus `PriorityClass`.** Las pone `infrastructure`.
- **No tiene `git log` de su historia.** La tiene `sgtm`, que no se borra.

## Estructura

```
backend/                Gradle. Java 25, Spring Boot 4. 17 módulos
  kamayuk-rentas-esquema/         V1__baseline.sql y la prueba de aislamiento
  kamayuk-rentas-dominio-compartido/  objetos de valor y contexto de tenant
  kamayuk-rentas-plataforma/      token -> SET LOCAL -> RLS, y el patrón de repositorio
  kamayuk-rentas-indicadores/     el panel de recaudación (no es contexto acotado)
  kamayuk-rentas-<contexto>/      los doce de ARQ-01 §3
  kamayuk-rentas-aplicacion/      ensambla el artefacto, y donde corren las barreras
frontend/               Vite 7, React 19, TypeScript 5.9, con yarn. `rentas-web`
  src/                            el código de la interfaz. ESTA ruta, y no otra (F-1)
  src/api/                        el único sitio donde se puede llamar a `fetch`. Ahí vive el proxy
  src/estilos/                    `estilos.css`, los cinco de `tokens/` y `marco.css` (F-2, F-3)
  src/ds/                         V6 — los ocho componentes base (F-2). SE VA (#90)
  src/marco/                      V6 — barra, arbol, pestanas, hash y paleta (F-3). SE VA (#90)
  src/secciones/                  V6 — las cuatro pantallas del modulo (F-5, F-6). SE VA (#90)
  src/pantallas/                  V8 — las 40 pantallas como dato, el arbol y el interprete (#86, #89)
  src/dominio/                    los tipos del dominio y el formato de importes y fechas
  src/datos/                      la captura del artboard, la invención apartada y las 18 operaciones
  diseno/RentasV6.dc.html         el artboard de la interfaz que se sirve HOY. SE VA (#90)
  diseno/RentasV8.dc.html         el artboard NUEVO, con su hoja de tokens. Contra el se comparan
                                  la paleta, las 40 pantallas y las clases de Tailwind
  diseno/RentasV8.dc.html         el artboard NUEVO, contra el que se reimplanta (#78)
  eslint.prohibiciones.mjs        las prohibiciones como dato: las leen el config y la prueba
  verificaciones/                 las barreras, sus `muestras/` que las violan y `tipos/`
infrastructure/         el descriptor de despliegue en TypeScript, con yarn
docs/                   ADR propios, hallazgos de RLS y esta guía de desarrollo
```

El frontend va en **`frontend/src/`** y no en un monorepo con `frontend/apps/<app>/src/`, y no es
una preferencia: `docs/00-gobierno/verificar-fila-del-registro.mjs` declara
`RUTAS_DE_CODIGO = [… /^frontend\/src\//, …]`. Con cualquier otra disposición, un PR de frontend
que cierra un issue deja de tener que escribir su fila del registro **y nadie se entera**, que es
exactamente el modo de fallo que esa guarda existe para impedir.

El backend **no compila sin `infrastructure` clonado al lado**: las barreras se consumen como
*composite build* desde `../../infrastructure/librerias-backend`. `settings.gradle.kts` lo
comprueba antes y falla diciendo qué `git clone` falta, en vez de dejar reventar a Gradle sobre un
directorio que no está.

**Y desde #74 el frontend tampoco funciona sin `kamayuk-lib` clonado al lado**: `package.json`
declara `@kamayuk/{api,formato,sesion}` como `link:../../kamayuk-lib/paquetes/*`, que yarn resuelve
contra el disco y ninguna variable de entorno redirige (ADR-0038). **Y aquí no hay nada de yarn en
que apoyarse**, medido: `yarn install --frozen-lockfile` con el hermano ausente sale con **código
0** y no enlaza nada —ni `--check-files` lo caza—, y el rojo llega dos pasos después como
`TS2307: Cannot find module '@kamayuk/formato'`, que no nombra ni el clon ni el `link:`. Quien lo
dice, nombrando el `git clone`, es `frontend/verificaciones/enlace-con-kamayuk-lib.test.ts`, dentro
de `yarn verificar`.

Los paquetes son `kamayuk.rentas.*`; los módulos, `kamayuk-rentas-<contexto>`. Los **roles de base de datos son
`kamayuk_owner`, `kamayuk_app`, `kamayuk_readonly` y `rol_carga_parametros`** (etapa C del renombrado).
Son del **clúster**, que los cuatro sistemas comparten, así que se renombran en los cuatro a la vez
o en ninguno: un `crear-roles.sql` con el nombre nuevo y otro con el viejo dejan a uno de los dos
sin poder conectarse. Los dos que no llevan el nombre del producto —`rol_carga_parametros` y
`rol_ingestor_catastro`— no se tocaron.

## Antes de escribir código, leer

| Si vas a tocar… | Lee |
|---|---|
| Cualquier cosa | [ADR-0002 — Estrategia multi-tenant](https://github.com/hneyra/infrastructure/blob/main/docs/30-arquitectura/adr/ADR-0002-estrategia-multi-tenant.md) — es el riesgo número uno |
| Base de datos | [Los cinco hallazgos de RLS](docs/40-datos/hallazgos-de-rls.md) **primero**, y `../srtm/docs/40-datos/ddl/esquema-verificado.sql` para tipos y longitudes |
| Cálculo tributario | `../srtm/docs/10-negocio/reglas-impuesto-predial.md` (NEG-05) y `../srtm/docs/30-arquitectura/motor-de-reglas-y-parametrizacion.md` (ARQ-09). **Aquí no se rediseña** |
| La frontera con catastro | [ADR-0024](docs/30-arquitectura/adr/ADR-0024-la-frontera-del-calculo.md) |
| El cobro | [ADR-0026](docs/30-arquitectura/adr/ADR-0026-el-camino-del-dinero.md) |
| Backend | [ARQ-04 — Estándares de código](https://github.com/hneyra/infrastructure/blob/main/docs/30-arquitectura/estandares-de-codigo-backend.md) |
| Montar el entorno | [D0 — Desarrollo](docs/D0-desarrollo/README.md) |

Índice de decisiones: [`docs/30-arquitectura/adr/README.md`](docs/30-arquitectura/adr/README.md).

**Si `../srtm` no está en el disco, se clona: `git clone https://github.com/hneyra/srtm`.** No es
opcional para el cálculo: el motor de reglas se escribió una vez sin poder leer NEG-05 ni ARQ-09 y
salieron dos defectos estructurales, los dos en verde.

## Decisiones abiertas que bloquean

Registro completo en [GOB-02](https://github.com/hneyra/sgtm/blob/migracion-a-microservicios/docs/00-gobierno/decisiones-abiertas.md).

| # | Decisión | Bloquea |
|---|---|---|
| D-02b | Valores de **ordenanza local** con su ratificación provincial | Arbitrios, sanciones, fraccionamiento |
| D-11 | El **`% actualización`**: **cerrada para 2026 y sólo para 2026** (2026-09-06). Su fila está publicada y sellada en `normativa`, y lo que la sella no es un valor por omisión sino un **hecho**: el supuesto del art. 12 del TUO LTM no se cumple ese ejercicio porque se publicaron los aranceles y los precios unitarios, de modo que no hay actualización que aplicar. **Para cualquier otro ejercicio sigue sin fuente**, y su valor neutro es **cero**, no uno — §1.6 del archivo del corpus lee el supuesto contra 2026 y ninguno más, y **no se hereda** | `RT-002`, `RT-005`, `RT-011` en los ejercicios distintos de 2026 |
| D-14 | Regla de imputación de un pago parcial | El camino del dinero |
| D-18 | La clave foránea que se pierde al separar `catastro` (`declaracion_jurada.predio_id` y las suyas) | El baseline |
| D-21 | Dónde se aplica el **`% de propiedad`** | La frontera de ADR-0024 |
## Reglas que no se negocian

Son las mismas en los cinco repositorios, y las verifica **el mismo artefacto**:
[`comun-verificaciones`](https://github.com/hneyra/infrastructure/tree/main/librerias-backend/comun-verificaciones),
que vive en `infrastructure` y se consume como *composite build*.

| # | Regla | Motivo |
|---|---|---|
| 1 | **Importes en `BigDecimal`/`NUMERIC`.** Prohibidos `double` y `float` | Precisión monetaria (RNF-055) |
| 2 | **Ningún método de dominio recibe `municipalidadId`.** Sale del token, se fija una vez con `SET LOCAL` | Si el desarrollador no lo maneja, no puede olvidarlo |
| 3 | **`SET LOCAL`, jamás `SET SESSION`** | `SET SESSION` sobrevive al retorno de la conexión al pool y contamina la petición de otra municipalidad |
| 4 | **Sin `DELETE`** en deuda, pagos, recibos, valores, valuaciones, asientos ni auditoría. Se anula, se da de baja o se reversa | RNF-051, y el manual §Auditoría |
| 5 | **Ningún literal numérico tributario en el código.** UIT, tramos, alícuotas, valores unitarios, aranceles y tablas de depreciación viven en datos versionados | Reproducibilidad y cambio sin despliegue (RNF-053) |
| 6 | **Las reglas tributarias son funciones puras.** Sin base de datos, sin reloj, sin configuración global; la fecha entra como argumento | Recalcular 2027 en 2037 debe dar el mismo céntimo |
| 7 | **Nada de Spring ni JPA en la capa `dominio`** | Las reglas deben probarse sin levantar el contexto |
| 8 | **`alicuota`, nunca `tasa`**, para un porcentaje | `tasa` es un tipo de tributo |
| 9 | **No existe «la deuda»:** es `deudaActualizadaA(fecha)`, y toda cifra mostrada indica su fecha | RNF-075 |
| 10 | **Toda modificación de datos exige observación del usuario.** Sin observación no se guarda | Manual §Auditoría; RNF-052 |

Las reglas 1, 2, 6, 7 y las fechas están escritas como pruebas de ArchUnit; `SET SESSION` y
`DELETE` sobre tabla protegida, como escáner del código fuente. Se añade una **undécima**, que
sólo existe desde que hay cinco repositorios: **ningún SQL cruza la frontera de sistema** —un
`JOIN` contra una tabla de otro sistema no deja huella en el bytecode, así que la vigila un
escáner de texto y no ArchUnit—.

**Si agregas una regla, agrega también la clase de muestra que la viola**, en las `muestras/` de
`comun-verificaciones`: una regla que no puede fallar no protege nada. Y lo exige por
construcción `ReglasDeArquitecturaMuerdenTest`, un `@TestFactory` sobre todas las reglas: una
regla sin muestra sale roja sola.

Lista completa con su justificación:
[ARQ-04 — Estándares de código del backend](https://github.com/hneyra/infrastructure/blob/main/docs/30-arquitectura/estandares-de-codigo-backend.md).

## Idioma

Español en el dominio, inglés en lo técnico. **Sin tildes en identificadores**: Checkstyle lo
revisa en el backend, ESLint en el descriptor.

```java
public final class Papeleta { … }                  // dominio: español
public interface PapeletaRepository { … }          // patrón: inglés
autovaluo.calcularTotal();                         // comportamiento: español
repository.findById(id);                           // infraestructura: inglés
```

Tablas y columnas en español `snake_case`. Campos de la API JSON en español `camelCase`.
Comentarios, pruebas y mensajes de commit en español.
## El monolito se llamaba `sgtm`, y en la prosa se sigue llamando asi

El producto es **Kamayuk**. El sistema del que sale —el monolito retirado— se llamaba `sgtm`, y
ese nombre **ya no esta en el codigo**: ni en un realm, ni en una imagen, ni en un identificador, ni
en un dato de configuracion.

**Pero sigue en los comentarios, en `docs/` y en el registro de «Verificar antes de afirmar», y eso
es deliberado.** No es limpieza pendiente:

- una fila del registro que dice «copiado de `sgtm@33f329a2`» **es la medicion que se hizo**;
  reescribirla la falsifica, y borrarla pierde con que rotura se demostro;
- un comentario que dice «hasta `E` la sonda apuntaba a `sgtm`» **es el motivo por el que el codigo
  de al lado es como es**; quitar el nombre lo deja sin sujeto y hay que volver a descubrirlo;
- y varias guardas explican en su docblock **de que defecto vienen**, que es lo que impide que
  alguien las «simplifique».

**Asi que NO se hace una pasada de limpieza sobre la prosa.** Si estas aqui por un `grep sgtm` que
devuelve cientos de lineas: casi todas son de este tipo y se quedan.

**Lo que si esta prohibido es que la cadena vuelva al codigo**, y lo vigila **una sola guarda para
los seis**: `sin-el-nombre-del-monolito.test.ts` de `infrastructure`, que barre este arbol y los
cinco clones hermanos. Barre **solo codigo de produccion** —ni `docs/`, ni `*.md`, ni pruebas— y
**omite comentarios**, por lo de arriba.

Esta en un sitio y no en `comun-verificaciones` porque, medido, **del lado Java no hay nada que
vigilar**: `backend/*/src/main` de los cinco solo nombra el monolito en comentarios y en dos
`COMMENT ON COLUMN`. Anadir una prohibicion a la libreria compartida exigiria su clase de muestra y
tocaria los seis builds para vigilar el conjunto vacio.

**Dos excepciones declaradas, y las dos con su motivo dentro de la guarda.** (1) Los buckets
`sgtm-{stg,prod}-respaldos` (`infra/Pulumi.{stg,prod}.yaml`): **son el nombre de cosas que
existen**, y renombrarlos en el codigo sin renombrar el bucket manda los respaldos a un sitio que no
existe — y eso no da error hasta el dia que hay que restaurar. (2) Dos `COMMENT ON COLUMN` dentro de
un `V1__baseline.sql` **ya aplicado**: Flyway valida la suma de comprobacion de cada migracion, asi
que editar una que ya corrio hace fallar el arranque de **toda base existente**. No es que no se
quiera cambiar: **no se puede** — se corregiria con una migracion nueva, si alguna vez importa.

## Comandos

```bash
cd backend
./gradlew verificarArquitectura   # ArchUnit, escaner de fuentes, aserciones y frontera de sistema
./gradlew verificarArranque       # el artefacto levanta en los dos perfiles (C-7). Requiere PostgreSQL 16
./gradlew verificarAislamiento    # aislamiento multi-tenant. BLOQUEANTE. Requiere PostgreSQL 16
./gradlew build                   # lo anterior mas Spotless
./gradlew spotlessApply           # arregla el formato en vez de solo reprocharlo

cd ../frontend
yarn install && yarn verificar    # la interfaz: lint, tipos y pruebas. Sin navegador ni backend
yarn build                        # el bundle. No se publica todavia: no hay imagen de rentas-web
yarn dev                          # Vite en el puerto 5173

cd ../infrastructure
yarn install && yarn verificar    # el descriptor: lint, tipos y pruebas. Sin Pulumi ni cluster

# La plataforma: PostgreSQL con las cuatro bases, Keycloak con sus dos realms, Traefik y el buzon
cd ../../infrastructure
docker compose -f despliegue/plataforma.compose.yaml up -d --wait

# La guarda del registro (#711) y su autoprueba
node docs/00-gobierno/verificar-fila-del-registro.mjs
node docs/00-gobierno/verificar-las-muestras-del-registro.mjs
```

**`verificarAislamiento` no se omite sin Docker: falla.** Una prueba bloqueante que se salta a sí
misma deja el build en verde sin haber verificado nada. La salida documentada es apuntar a un
PostgreSQL 16 que ya exista, y **ninguna que omita la prueba**:

```bash
./gradlew verificarAislamiento \
  -Dkamayuk.pruebas.postgres.url=jdbc:postgresql://localhost:5432/postgres \
  -Dkamayuk.pruebas.postgres.usuario=postgres \
  -Dkamayuk.pruebas.postgres.clave=…
```

Tiene que ser **PostgreSQL 16** —el esquema no corre en 18 (`V11` falla con «text search
dictionary "unaccent" does not exist»)— y superusuario, porque la prueba crea los cuatro roles.
Cómo montarlo desde cero: [D0 — Desarrollo](docs/D0-desarrollo/README.md).
## Verificar antes de afirmar

**Ejecutar la prueba vale más que razonar sobre ella.** Y no basta con que la verificación esté
escrita: **tiene que demostrarse que puede fallar** — se rompe a propósito el código que protege,
se ejecuta, y se anota el rojo exacto que sale.

Cada issue deja aquí una fila con qué se implementó, **con qué rotura se demostró que la
verificación muerde** y qué rojo produjo. Es lo que impide volver a descubrir el mismo hallazgo
por tercera vez.

> **La tabla nace vacía, y es correcto que se vea así.** El registro anterior —288 filas, issue a
> issue— es historia de `sgtm` y **no viaja**: en un repositorio sin ese `git log` sería el
> registro de un trabajo que aquí no se hizo. Vive en
> [`sgtm/CLAUDE.md`](https://github.com/hneyra/sgtm/blob/migracion-a-microservicios/CLAUDE.md),
> que no se borra. Se consulta; no se copia.

Que la fila **exista** lo comprueba `docs/00-gobierno/verificar-fila-del-registro.mjs` en cada PR
que cierre un issue y toque código de producción. Lo que la fila **diga** —que la mutación sea
real y las cifras cuadren— no lo puede leer una máquina: eso lo lee la revisión.

| Verificación | Cómo se demostró que puede fallar | Resultado |
|---|---|---|

**Las 40 filas viven en [`docs/agent/HISTORY.md`](docs/agent/HISTORY.md)**, y ahí es donde se
escribe la siguiente. Se mudaron el 2026-09-12: eran el **91 %** de este archivo, que se carga
entero en cada sesión ([`infrastructure`#114](https://github.com/hneyra/infrastructure/issues/114)).

La tabla de arriba se deja **con su cabecera y vacía** a propósito: es la forma de la fila que hay
que escribir, y tenerla delante evita ir a buscarla.
