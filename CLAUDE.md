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
| `backend/` — **17 módulos** | **Existe, y la resta terminó (P5E).** `./gradlew build` en verde: **3 383 pruebas**, 0 fallos —medido el 2026-09-17 contando los `TEST-*.xml` de los diecisiete modulos; es una lectura de ESE dia y no un contrato, y la renueva quien vuelva a correr el comando—. Es el monolito modular menos la interfaz, menos los valores normativos (P5B), menos el catastro (P5C) y menos la ventanilla (P5D). De los 17 módulos, **quince son de este sistema** y dos —`kamayuk-rentas-catastro` y `kamayuk-rentas-parametros`— son **adaptadores cliente**: puertos y transporte, sin dominio y sin una sola consulta a una tabla ajena |
| `backend/kamayuk-rentas-seguridad` — la copia local de la autorización | **Ya no administra a nadie (ADR-0039, etapa 4; `identidad`#4).** Las once escrituras de administración —altas, bajas, reactivaciones y vigencias de grupos y usuarios, la afiliación y las dos matrices de permisos—, sus dos repositorios, tres controladores y **diecinueve rutas** se fueron a `identidad`, que es el dueño de la autorización; con ellas salen del catálogo las opciones `usuarios`, `grupos`, `miembros` y `permisos` (**134 → 130**, y el contrato **181 → 162** operaciones publicadas). Lo que queda es **la copia local**, y **desde la etapa 5 este sistema no siembra ni una fila de ella** (`identidad`#5): `SembradorDelCatalogo` —lo que era `SembradorDeLaCopiaLocal`— siembra sólo el **catálogo**, `modulo_sistema` y `acceso`, o sea qué pantallas existen; el grupo, el administrador, su afiliación y sus permisos **llegan por el buzón**, y `ImplantarMunicipalidad` corre esa pasada **en línea** y **falla nombrando lo que falta** si no hay buzón, si no contesta, o si contesta y no trae ni una cuenta — nunca `Complete` con cero usuarios. `AplicarUnEventoDeIdentidad` la actualiza con lo que llega por el buzón de `identidad` (`GET /identidad/api/v1/eventos/pendientes` → una transacción por evento con `SET LOCAL` → acuse DESPUÉS del commit), y `CorrerElConsumidorDeIdentidad` lo corre cada cinco minutos desde un `CronJob` —y **se aparta en el `Job` de implantación**, porque ahí la pasada ya la hizo la implantación en línea—. **Las seis lecturas de `LecturaDeLaCopiaLocalJdbc` llevan `@Transactional(readOnly = true)`, y no es decorativo**: sin transacción no hay `SET LOCAL` y la política RLS evalúa `''::bigint` — medido con las cinco aplicaciones levantadas, `GET /seguridad/modulos` y `/accesos` contestaban **500** (`identidad`#4, AC-5/AC-6). **El canal del responsable NO tiene que ser http(s)**: los ambientes declaran un correo, el aviso se registra siempre con ERROR y además se entrega por `POST` cuando se puede; y un evento pospuesto de más de **15 minutos** avisa una vez por corrida, con la corrida en rc=0. El guardia **no cambia**: sigue autorizando contra su propia base. `GET /seguridad/modulos`, `GET /seguridad/accesos`, la sesión entera y las rutas de seguridad de `YA_SERVIDAS` de la interfaz **siguen aquí**. La regla 12 se vigila desde la etapa 4, y **desde la 5 declara UN escritor y sin fecha de fin**: `AplicarUnEventoDeIdentidad`. El del sembrador se retiró con sus cuatro `INSERT`, y su rojo lo confirmó —#27 nombra la entrada que ya no exime a nadie— |
| `backend/kamayuk-rentas-parametros` | **Ya no publica ningún valor normativo**: eso es de `normativa` desde P5B (ADR-0025). Lo que queda aquí es el **cliente** —`LectorDeParametros` con la misma firma de siempre, leyendo de la copia local de un conjunto sellado (`V3`)— y las reglas puras, que todavía viven en los dos repositorios (hueco declarado: `normativa/docs/00-gobierno/P5B-extraccion.md` §7.1) |
| **Un repositorio hermano más** | Desde P5B, `./gradlew test` **no pasa sin `normativa` clonado al lado**: tres clases comprueban que la llave con que su derivado publica un valor es la que este backend pide (#192). Si no está, fallan nombrando el `git clone`; no se saltan |
| `backend/kamayuk-rentas-esquema` | **Existe, con su baseline y quince migraciones más** —`V1`…`V14`, `V17` y `V18`: la numeracion salta la 15 y la 16, y ningun archivo de este arbol las tuvo nunca—. `V1` nació con 132 tablas; `V2`, `V6` y `V7` retiran las **31** que se fueron a los otros tres sistemas, y `V3`, `V4`, `V5`, `V8`, `V12` y `V18` traen las **14** que nacen del corte —la copia sellada de normativa, la proyección de catastro, la valuación recibida, el buzón de pagos, la cola de muertos del ingestor y el consumidor de identidad—. **115 tablas vivas**, de las cuales **10 son particiones** de cinco tablas particionadas, o sea **105 lógicas**. Las dos cuentas cuadran por caminos distintos: 146 `CREATE TABLE` − 31 `DROP TABLE` sobre el texto, y 115 relaciones en `pg_class` sobre una base migrada de cero. `verificarAislamiento` corre **237 pruebas** —52 del esquema y 185 del pool—, 0 fallos (2026-09-17) |
| Las extensiones que pide su base | **Dos, y no cuatro** (P5E): `pg_trgm` y `unaccent`, las dos *trusted*. `postgis` y `btree_gist` salieron de `crear-roles.sql` porque nada del esquema final las usa —medido: ni una columna PostGIS, ni un índice GiST, ni una restricción `EXCLUDE`— y lo que las mantenía vivas era `V1`, que creaba la geometría del predio para dejarla caer en `V6` |
| `backend/kamayuk-rentas-aplicacion` | **Existe.** `verificarArquitectura` corre **264 pruebas** (2026-09-17): las barreras de la librería común más las propias de este sistema (contrato, formas, respuestas, límites de Modulith) |
| `docs/30-arquitectura/adr/` | **Existe**, 11 ADR propios más los que enlaza |
| **Código de negocio** | **Existe.** Llegó entero en P5A ([P5A](docs/00-gobierno/P5A-copia-del-backend.md)), copiado de `sgtm@0d33ad7b` con el mismo número de pruebas: 3 756 = 3 756. Lo que sigue calculando **da el mismo céntimo**: dos archivos comparados byte a byte contra el árbol anterior a P5C, con las mismas huellas que P5B y P5C publicaron ([P5E](docs/00-gobierno/P5E-cierre.md) §4) |
| **La lista de cruces de frontera** | **Vacía en los cuatro repositorios** ([P5E](docs/00-gobierno/P5E-cierre.md) §2). Es el criterio de que la separación terminó: no que los repositorios existan, sino que ninguno lea por SQL una tabla que ya no es suya. Y está medido que la regla puede fallar, repositorio por repositorio |
| Su esquema (`V1__baseline.sql`) | **Está aquí**, en `backend/kamayuk-rentas-esquema/src/main/resources/db/migration/`. Es una migración de Flyway y no un `esquema.sql` suelto (ADR-0032 §2) |
| Su frontend (`rentas-web`) | **Existe, y es la interfaz de RentasV8.** Vite 7 + React 19.3 + TypeScript 5.9 en `frontend/`, con el codigo en `frontend/src/` —y no en un monorepo, porque `verificar-fila-del-registro.mjs` declara `/^frontend\/src\//` como codigo de produccion—. `yarn verificar` encadena lint, tipos, **i18n** y pruebas: **791 pruebas en 52 archivos**, 0 fallos. `yarn e2e` corre **71 caminos en Chromium** contra el bundle construido. `yarn build`: 882 kB de JS y 41,0 kB de CSS.<br><br>**Que hay**: `src/aplicacion.tsx` monta `<Armazon>` de `@kamayuk/shell` con un catalogo **filtrado por lo que la cuenta puede abrir** —se compone de `GET /seguridad/{modulos,accesos}` y `/sesion/permisos`, y el rotulo es el del BACKEND—; el cuerpo de cada pantalla lo dibuja **el interprete de `@kamayuk/ui`** desde una de las 40 definiciones tipadas —desde #153 aqui no hay copia suya: `src/pantallas/PantallaDeRentas.tsx` le pasa la `t`, sus tres palabras y el reparto de tonos de `tono.ts`, y `el-interprete-es-de-la-libreria` vigila que no vuelva—. **Diecisiete** pantallas piden datos de verdad —las dos que habia mas las quince que conectan #167, #168, #169, #170, #179, #180 y #181—; las otras 23 **dicen por que no**, con seis frases distintas —sin conectar · sin verificar · solo escribe · pidiendo · error · no publicado— y **ninguna dice un cero**. Todo el texto pasa por `t()`: el castellano ES la clave, y un segundo idioma es un JSON.<br><br>**Y NO INSTALA SIN SU CLON HERMANO.** `frontend/package.json` declara **seis** `link:../../kamayuk-lib/paquetes/*`. Si falta: `git clone https://github.com/hneyra/kamayuk-lib ../../kamayuk-lib`. Ojo: `yarn install --frozen-lockfile` con el hermano ausente sale con **codigo 0** y deja enlaces colgantes. **Y el enlace exige tres cosas que se aprendieron por las malas**: `preserveSymlinks: true` en `tsconfig.base.json` (#88) —sin el, en CI `tsc` resuelve los `import` de la libreria contra el arbol del hermano, que alli no tiene dependencias—, `resolve.dedupe` en `vite.config.ts` **derivado de las `peerDependencies`** (`resolucion.ts`) —sin el salen dos copias de React—, y `@source` en `src/estilos.css` (#107) —sin el, Tailwind **omite `node_modules`** y mas de la mitad de las clases de la libreria no generan regla: 157 en vez de 419, con la rejilla de campos **en una sola columna** y la pantalla dibujandose igual—.<br><br>**Lo que ata la interfaz al artboard son seis guardas**: la paleta token a token; las 40 pantallas **campo por campo** contra `diseno/RentasV8.dc.html`; que toda clase de Tailwind **produzca una regla** (#91); que el interprete **no nombre ningun sistema** —desde #153 vive en `@kamayuk/ui` y la guarda lo barre alli, por el enlace, con los rotulos y las claves que solo este arbol sabe—; que **la V6 no vuelva** al arbol; y que **ninguna cadena llegue al DOM sin pasar por `t()`**, montando las 40 pantallas con un idioma que marca lo que traduce (#103). Mas el arnes, que mide lo que ninguna de las seis puede: **que la interfaz se VEA** —la paleta leida del navegador, el radio, que la rejilla se reacomode, que ninguna tabla desplace la pagina— (#107), y que **sin emisor levantado la pagina no se quede en blanco**, con sus dos ramas: la puerta que no contesta monta y se explica, y la que si contesta sigue sin montar nada (#112).<br><br>**Y se mira en local sin levantar nada** (#114): `yarn dev` siembra las tres lecturas de seguridad con la captura de `datos/seguridadMedida.ts` y esquiva la puerta, de modo que los 40 destinos se recorren **sin PostgreSQL, sin Keycloak, sin Traefik y sin backend** —medido en Chromium: 40 de 40, cero peticiones a `/seguridad/`—. Lo enciende `VITE_KAMAYUK_SIN_PLATAFORMA` en `.env.development`; contra la plataforma de verdad es `yarn dev:con-plataforma`. **Y no viaja al paquete**: la condicion lleva delante un `import.meta.env.DEV`, asi que Rollup la pliega y se lleva el `import()` dinamico entero — medido, 820 619 bytes y cero cadenas de la captura, frente a 853 141 y un trozo `sembrarElCatalogo-*.js` de 31 131 bytes si la bandera se lee en tiempo de EJECUCION.<br><br>Y **la interfaz tiene tema**: `ProveedorDeTema` de `@kamayuk/ui` envuelve la aplicacion entera —incluida la pantalla de la puerta caida—, y «Preferencias» del menu de sesion abre el mando, que son **dos ejes independientes** —tres identidades por tres modos, con «el del sistema» = quitar el atributo— guardados en el navegador con el prefijo `kamayuk.rentas`. Aqui no se inventa ninguna paleta: las seis son de la libreria y valen para los cuatro sistemas. Y **ninguna opcion del menu de sesion se queda muda** (#115): «Mi perfil» y «Cambiar la contrasena» abren, en otra pestana, la **consola de cuenta del emisor** —`{realm}/account/` y `{realm}/account/account-security/signing-in`, derivadas de `configuracion('oidcRealm')` y no escritas a mano—, porque la autorizacion es de `identidad` (ADR-0039) y la contrasena la guarda Keycloak; aqui no se dibuja ni un formulario de perfil ni uno de clave, y el contrato lo respalda: cero operaciones suyas. Las rutas estan medidas contra el codigo de `keycloak:26.0`, que es la version que el compose fija; **que una instalacion levantada las sirva NO se comprobo** —no hay motor de contenedores en el puesto—. Que no vuelva un `al: () => {}` lo vigila un escaner que parsea `aplicacion.tsx` con el compilador de TypeScript, con su centinela delante.<br><br>**⚠️ Lo que NO hay, y hay que saberlo**: (1) las **23** pantallas sin conector no piden nada y **nunca lo haran hasta que el backend publique lo que ensenan** — esta medido campo a campo en `datos/conectores.ts` —y en un archivo por modulo bajo `datos/conectores/`— por que «servida» no es «puede pintarse»; (2) **la identidad de verdad no se prueba**: entrar por el formulario de Keycloak con PKCE exige la instalacion levantada, y le faltan el compose de la plataforma, el realm, el volcado de la marcha blanca y el secreto. El arnes pasa la puerta por el tope de idas, que es un camino declarado |
| Su imagen `ghcr.io/hneyra/kamayuk-rentas` | **NO existe.** El descriptor la nombra igual, y es correcto: aquí no se despliega nada |

**Las barreras se construyeron primero, a propósito**, y el negocio entró después por encima de
ellas. Lo que hoy vigilan es real: 3 383 pruebas sobre los contextos que quedan.

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
  src/api/                        el UNICO sitio donde se puede llamar a `fetch`
  src/aplicacion.tsx              la COSTURA: monta el armazon con el catalogo de este sistema
  src/catalogo.ts                 traduce el arbol a la forma que `@kamayuk/shell` entiende
  src/permisos.ts                 lo que la cuenta no puede abrir, no se ofrece
  src/porQueNoHayDato.ts          las cinco frases con que una pantalla dice que no tiene datos
  src/preferencias/               el mando de los temas: dos ejes, y ninguna paleta propia
  src/i18n/                       el castellano es la clave; el locale se REGENERA, no se escribe
  src/estilos.css                 no define ni un color: importa la de la libreria y dice donde mirar
  src/pantallas/                  las 40 pantallas como dato y el arbol. El INTERPRETE es de `@kamayuk/ui` (#153)
  src/pantallas/definiciones/     una por modulo: 40 pantallas, 45 bloques, 302 campos, 31 tablas
  src/dominio/                    los tipos del dominio y el formato de importes y fechas
  src/datos/                      el contrato de ESTA API: `lecturas.ts`, las 32 de `servidas.ts`
                                  y los `*Medida.ts`, que son respuestas de `curl` a la instalacion
  diseno/RentasV8.dc.html         el artboard. Contra el se comparan la paleta, las 40 pantallas
                                  campo por campo, y las clases de Tailwind. Su hoja de tokens al lado
  diseno/RentasV8.dc.html         el artboard NUEVO, contra el que se reimplanta (#78)
  eslint.prohibiciones.mjs        DERIVA las nueve de `@kamayuk/verificaciones` (#137); aqui solo
                                  vive su excepcion de ruta. La lista a mano esta en la libreria
  verificaciones/                 las barreras, sus `muestras/` que las violan y `tipos/`
  e2e/                            el arnes: 71 caminos en Chromium contra el bundle construido
  desarrollo/                     la siembra del catalogo (#114). FUERA de `src/` a proposito: es
                                  lo unico que importa una captura sin ser una prueba, y nunca viaja
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
declara `@kamayuk/{api,formato,sesion,shell,ui,verificaciones}` como `link:../../kamayuk-lib/paquetes/*`, que yarn resuelve
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
yarn install && yarn verificar    # la interfaz: lint, tipos, i18n y pruebas. Sin navegador ni backend
yarn e2e                          # el arnes: construye el bundle y lo recorre en Chromium (~50 s)
yarn e2e:navegador                # descarga Chromium la primera vez (114 MB)
yarn i18n:regenerar               # el locale `es` sale del dato; no se escribe a mano
yarn build                        # el bundle. No se publica todavia: no hay imagen de rentas-web
yarn dev                          # Vite en el 5173, con el catalogo SEMBRADO: sin plataforma
yarn dev:con-plataforma           # lo mismo contra la plataforma levantada: Keycloak y backend de verdad

cd ../infrastructure
yarn install && yarn verificar    # el descriptor: lint, tipos y pruebas. Sin Pulumi ni cluster

# La plataforma: PostgreSQL con las cuatro bases, Keycloak con sus dos realms, Traefik y el buzon
cd ../../infrastructure
docker compose -f despliegue/plataforma.compose.yaml up -d --wait

# La guarda del registro (#711) y su autoprueba
node docs/00-gobierno/verificar-fila-del-registro.mjs
node docs/00-gobierno/verificar-las-muestras-del-registro.mjs

# La guarda de la rama base (#220) y su autoprueba. El PR tiene que estar abierto contra `main`,
# y si se apila sobre otra rama hay que declararlo en el cuerpo: `Apilado sobre <la-rama>`
KAMAYUK_RAMA_BASE_DEL_PR=$(gh pr view N --json baseRefName --jq .baseRefName) \
KAMAYUK_CUERPO_DEL_PR=$(gh pr view N --json body --jq .body) \
  node docs/00-gobierno/verificar-la-rama-base-del-pr.mjs
node docs/00-gobierno/verificar-las-muestras-de-la-rama-base.mjs

# Y el barrido: PR ya mezclados cuyo trabajo NO llego a `main`. Necesita `gh`; se corre a mano
node docs/00-gobierno/barrer-los-pr-mezclados-fuera-de-main.mjs
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

**Y el cuerpo del PR cierra su issue con `Closes #N`, en inglés y en el cuerpo —nunca en el
título—.** No es una excepción al idioma de la casa: es que **GitHub sólo auto-cierra con
`close(s|d)`, `fix(es|ed)` y `resolve(s|d)`**, y «Cierra #N» —que la guarda acepta, y va a seguir
aceptando— **no cierra nada**. Medido en la tanda del 2026-09-12: cinco PR con la palabra inglesa
cerraron su issue al mezclar y el que llevaba la castellana no, y nadie se enteró hasta la
auditoría —PR mezclado, CI verde, fila escrita, issue abierto—. Desde #130 eso sale **rojo** en
`Registro` nombrando el issue que se quedaría abierto, y la plantilla de PR
(`.github/pull_request_template.md`) lo dice arriba del todo. Si de verdad no quieres auto-cierre,
no lo declares: «Ref» o «Parte de» no disparan nada.

**Y ese guion no es sólo de este repositorio**: es el mismo archivo en los seis, byte a byte salvo
el bloque de `RUTAS_DE_CODIGO` —su comentario y la lista—, y lo vigila
`infra/verificaciones/las-seis-copias-de-la-guarda-del-registro.test.ts` en `infrastructure`
([#165](https://github.com/hneyra/infrastructure/issues/165)). Cambiarlo fuera de ese bloque es
cambiarlo en los seis, y `infrastructure` se mezcla el último. Lo que sí es de aquí es la
autoprueba, que ejerce la lista de este árbol.

| Verificación | Cómo se demostró que puede fallar | Resultado |
|---|---|---|

**Las 40 filas viven en [`docs/agent/HISTORY.md`](docs/agent/HISTORY.md)**, y ahí es donde se
escribe la siguiente. Se mudaron el 2026-09-12: eran el **91 %** de este archivo, que se carga
entero en cada sesión ([`infrastructure`#114](https://github.com/hneyra/infrastructure/issues/114)).

La tabla de arriba se deja **con su cabecera y vacía** a propósito: es la forma de la fila que hay
que escribir, y tenerla delante evita ir a buscarla.
