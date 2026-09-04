# GOB-04 — Plan de marcha blanca del impuesto predial

**Estado:** **ejecutado en lo que dependía del código.** Los cinco issues que este plan creó
(#118…#122) y las diecisiete filas de §4 están cerrados, y la instalación se levanta entera en CI
en cada PR. Lo que falta para convocar una marcha blanca ya no es programación: es §5 y §6.
**Alcance decidido:** padrón y determinación, **sin cobranza**.
**Emisor de identidad decidido:** Keycloak.
**D-02a: cerrada el 2026-08-25** ([#200](https://github.com/hneyra/sgtm/issues/200)). El paquete
E-3 de [#116](https://github.com/hneyra/sgtm/issues/116) terminó y el mecanismo de carga existe
entero. Lo que sigue deteniendo los importes es otra cosa, y está en §5.

> **Puesta al día del 2026-08-29.** El plan se escribió el 19 de agosto de 2026 y describía un
> repositorio que ya no existe. Esta revisión **no lo reordena**: conserva lo que decía y le añade,
> sección por sección, qué pasó y con qué evidencia. El plan es también el registro de por qué se
> crearon esos cinco issues, y borrar el diagnóstico dejaría las respuestas sin pregunta.

Este documento no reordena la [hoja de ruta del backend (#58)](https://github.com/hneyra/sgtm/issues/58):
la recorta a lo que una marcha blanca del predial necesita, y **añade lo que la hoja de ruta no
tiene porque nunca fue un problema de negocio** — que el sistema se pueda desplegar, que alguien
pueda entrar y que exista una municipalidad dentro.

---

## 1. Lo primero era que no había nada que desplegar — **cerrado**

No era una opinión sobre el grado de avance. Eran cuatro hechos comprobables en el repositorio, y
cada uno por separado impedía arrancar el sistema para que lo usara una persona. **Los cuatro están
cerrados**, y la columna de la derecha dice dónde mirar para comprobarlo.

| # | El hecho, tal como se escribió | Cómo está hoy |
|---|---|---|
| **M-1** | **No hay `SecurityFilterChain` ni `issuer-uri`.** `spring-boot-starter-oauth2-resource-server` está en el classpath, pero nada configura un emisor y ningún `@Bean` define la cadena de filtros | **Cerrado.** `SeguridadWeb` (sgtm-plataforma) declara la cadena, y `application.yaml:71` el `issuer-uri`. El flujo `despliegue.yml` lo recorre entero: peldaño **4**, «la escalera de identidad, peldaño a peldaño», y peldaño **8**, «sin emisor configurado, la aplicación se niega a arrancar» |
| **M-2** | **Nadie ejecuta las migraciones.** `spring.flyway.enabled: false`, a propósito y bien: la aplicación se conecta como `sgtm_app`, que no tiene DDL | **Cerrado.** `despliegue/compose.yaml` tiene un servicio `migraciones` que corre como `sgtm_owner` y **termina** antes de que arranque la aplicación (`service_completed_successfully`). Peldaño **1**: todas las migraciones del repositorio están aplicadas; peldaño **7**: la aplicación no puede ejecutar DDL |
| **M-3** | **No hay imagen ni forma de arrancar.** Ni `Dockerfile`, ni `compose`, ni manifiestos | **Cerrado.** `backend/Dockerfile`, `frontend/Dockerfile` y `despliegue/compose.yaml` —siete servicios—, con su [README](../../despliegue/README.md). Y por encima, el clúster de `infra/`, que es otra cosa y tiene su propia épica ([#159](https://github.com/hneyra/sgtm/issues/159), abierta) |
| **M-4** | **No hay forma de dar de alta una municipalidad.** `municipalidad` la escribe solo `sgtm_owner`, y no existe endpoint, tarea ni procedimiento que la cree, ni que siembre el primer administrador | **Cerrado.** `ImplantarMunicipalidad`, en perfil `batch` —y con una prueba que se pone roja si alguien le quita ese perfil o le pone el de `web`—, más el alta declarativa de usuarios de [ADR-0012](../30-arquitectura/adr/ADR-0012-usuarios-y-grupos-declarativos.md). Peldaños **3** y **3b**: los usuarios existen y a cada uno le llega su enlace de clave |

Los cuatro compartían una causa: la hoja de ruta se ordenó por dependencia **entre contextos de
negocio**, y la implantación no es un contexto de negocio. Que aparecieran justo entonces, cuando
hubo fecha, era lo normal; que no tuvieran issue, no.

## 2. Qué hay hecho, verificado contra el tablero

Contado por la etiqueta `onda:n`, el 2026-08-29:

| Onda | Total | Abiertos | Qué queda |
|---|---|---|---|
| **Onda 0** — cimientos, despliegue e identidad | 18 | **0** | Cerrada. Incluye los cinco de §3 y el frontend de arranque (#61…#69) |
| **Onda 1** — padrón, catastro y documentos | 17 | **0** | Cerrada, #17 incluido |
| **Onda 2** — libro, cuenta corriente y rentas | 19 | 4 | Las tres mitades de cifras (#188, #189, #190) y un defecto del vehicular (#399) |
| **Onda 3** — caja, valores y coactiva | 16 | 2 | Dos mitades de cifras (#191, #193) |
| **Onda 4** — sanciones, licencias y fiscalización | 27 | 11 | Seis mitades de cifras (#194…#199) y cinco defectos o huecos del backend (#351, #358, #396, #397, #398) |
| **Onda 5** — transversal | 3 | 1 | [#57](https://github.com/hneyra/sgtm/issues/57) construido: **D-07 cerrada** por [ADR-0020](../30-arquitectura/adr/ADR-0020-la-sesion-del-ciudadano.md) y D-15 decidida (camino B). Queda [#415](https://github.com/hneyra/sgtm/issues/415), el enrolamiento del ciudadano en ventanilla, sin el cual el portal está construido y nadie puede entrar por él |
| **Frontend** | #70…#81 | **0** | Las 134 pantallas dibujadas; **72 lecturas** con conexión propia y tipada contra el contrato. Retirar el proxy de datos es [#400](https://github.com/hneyra/sgtm/issues/400) |

Es decir: **lo que este plan llamaba «la determinación no está empezada» está construido**, y lo que
queda abierto de negocio son las mitades de cifras —las que esperan a D-02b, D-02c o a lo que
quedó detrás de la firma de D-02a (§5)— más una lista corta de defectos con issue propio.

## 3. Los cinco issues que faltaban — **los cinco cerrados**

Salieron de §1, más el marcado de la instalación de demostración que el paquete E-6 de #116 ya
había identificado. Ninguno dependía de D-02, y por eso se pudieron hacer enteros.

| Issue | Qué entregó | Cómo se demostró que su verificación puede fallar |
|---|---|---|
| **[#118](https://github.com/hneyra/sgtm/issues/118) · Empaquetado y despliegue** | `Dockerfile` del artefacto único; `compose` de marcha blanca con PostgreSQL, Keycloak y el backend; **paso de migración separado**, que corre como `sgtm_owner` y termina antes de que arranque la aplicación; `crear-roles.sql` en el arranque del motor | Dando a `sgtm_app` las credenciales de `sgtm_owner` en el compose: la comprobación de que la aplicación **no** puede hacer DDL se pone roja |
| **[#119](https://github.com/hneyra/sgtm/issues/119) · Identidad con Keycloak** | `SecurityFilterChain` explícita, `issuer-uri`, realm exportado y versionado con el claim `municipalidad_id`, y el frontend apuntando a él (el PKCE de #65 ya estaba) | Tres roturas: quitar la cadena de filtros, emitir un token sin el claim, y emitir uno firmado por otro realm. Las tres tienen que dar 401 o 403 y ninguna llegar al controlador |
| **[#120](https://github.com/hneyra/sgtm/issues/120) · Implantación de una municipalidad** | Procedimiento en perfil `batch` que da de alta la municipalidad, siembra los 134 accesos con `SembradorDeAccesos`, crea el primer administrador y fija el ejercicio de trabajo. Idempotente | Ejecutándolo dos veces: la segunda no duplica accesos ni crea un segundo administrador. Y quitando la guarda del último administrador, que #12 ya demostró que muerde |
| **[#121](https://github.com/hneyra/sgtm/issues/121) · Carga inicial de vías, sectores y manzanas** | Importación masiva desde archivo de los catálogos territoriales de #16, con su rechazo por fila y su observación | Con un archivo donde una fila viola la unicidad: se rechaza esa fila, se informa cuál, y las demás entran. **Y salió una lección**: anotar `@Transactional` sobre el método que orquesta el archivo entero se lleva por delante la fila válida que seguía a la rechazada |
| **[#122](https://github.com/hneyra/sgtm/issues/122) · Instalación de demostración** | `municipalidad.es_demostracion` en migración —el hecho vive en la base, no en configuración—; todo documento emitido bajo ese tenant sale marcado | Emitiendo un documento bajo el tenant de demostración sin la marca: la comprobación de reimpresión idéntica de #55 lo detecta. Hoy son **19 pruebas**, y hay que romper el bloque de la marca en **cada renderizador por separado** para que caiga solo su formato |

**#122 es lo que hace honesta una marcha blanca**: mientras ningún conjunto del ejercicio esté
sellado con cifras reales, cualquier importe que el sistema muestre sale de parámetros de prueba.
Cerrar D-02a no cambió eso —firmar la transcripción no es cargar el dato, y dos de los tres cuadros
todavía no se pueden publicar (§5)—. Que el documento lo diga por escrito es la diferencia entre una
prueba y un valor que alguien puede intentar cobrar.

## 4. La secuencia — **las diecisiete entregadas**

Cada fila era un PR, y el orden lo fijaba la dependencia real. Todas están cerradas; la columna de
la derecha dice con qué issue.

| # | PR | Issues | Estado |
|---|---|---|---|
| 1 | Este plan, y los cinco issues creados (#118…#122) | — | Hecho |
| 2 | Empaquetado, migración como `sgtm_owner` y compose | **#118** | Cerrado |
| 3 | Identidad con Keycloak, de punta a punta | **#119** | Cerrado |
| 4 | Implantación de una municipalidad, y la marca de demostración | **#120**, **#122** | Cerrados |
| 5 | Carga inicial de catálogos territoriales | **#121** | Cerrado |
| 6 | Tablas de valuación: estructura, con la dimensión que falta | **#17** | Cerrado. La dimensión ya estaba en V18, y lo que quedó abierto es cuál de las dos lecturas de `RT-002` es la correcta (GOB-03, H-14) |
| 7 | `deudaActualizadaA(fecha)` | **#22** | Cerrado |
| 8 | Saldo proyectado, y altas y bajas de deuda | **#23**, **#24** | Cerrados |
| 9 | Beneficios y exoneraciones con vigencia | **#27** | Cerrado |
| 10 | Declaración jurada: HR, PU y PR | **#28** | Cerrado |
| 11 | Transferencias de predio | **#29** | Cerrado |
| 12 | Determinación del predial: **estructura, sin una sola cifra** | **#30** (mitad) | Cerrado; su capa web es #395 |
| 13 | El corpus de casos, con la columna `esperado` en blanco | **E-5** de #116 | Cerrado |
| 14 | Consultas: estado de cuenta y deuda por contribuyente | **#25** (parte) | Cerrado |
| 15 | Frontend: conectar las 12 opciones de catastro | **#71** | Cerrado |
| 16 | Frontend: conectar las opciones del predial en rentas | **#73** (parte) | Cerrado |
| 17 | Frontend: conectar las consultas | **#72** (parte) | Cerrado |

Los PR 2 a 5 no tocaban negocio y desbloqueaban todo lo demás. Los PR 6 a 14 eran la mitad
«estructura» de la que habla el paquete E-2 de #116: **ningún criterio de aceptación nombra un
importe**. Los tres últimos hacían visible lo construido.

**Y el trabajo siguió más allá de esta lista**, que es lo que explica §2: las ondas 3, 4 y 5 —caja,
coactiva, sanciones, licencias, fiscalización y el panel de recaudación— no estaban en este recorte
porque una marcha blanca del predial no las necesita.

### En paralelo, y sin esperar a ningún PR

**E-3 de #116 — transcribir y firmar D-02a: hecho el 2026-08-25** ([#200](https://github.com/hneyra/sgtm/issues/200)).
Un archivo por norma en [`docs/10-negocio/valores-normativos/`](../10-negocio/valores-normativos/),
con norma, artículo, fecha de publicación, ejercicios que rige, **transcriptor y verificador
distintos**, y estado. Y ya no es solo papel: `PublicarParametros` publica los valores sueltos,
`PublicarCuadros` un cuadro entero desde el manifiesto del corpus, y `AbrirConjuntoDeParametros`
([#247](https://github.com/hneyra/sgtm/issues/247) §2) abre, compone y sella.

**Lo que sigue en paralelo es lo que quedó detrás de la firma**, y es trabajo, no decisión: la
lo que le falta al cuadro de valores unitarios, que desde el 2026-08-29 ya no es la segunda firma
sino el derivado publicable, el vocabulario de partidas y las otras tres regiones (H-14)
—`PublicarCuadros` lo rechaza a propósito, nombrando el motivo, en vez de publicar un cuadro
incompleto que nadie distinguiría de uno completo— y el `% actualización` de D-11, el único de los
cuatro factores que sigue sin fuente identificada. **La tabla de depreciación salió de esa lista el
2026-08-29**: `V57` le dio su dimensión de uso y sus cuatro tablas ya se publican (H-15).

El PR 13 sirve exactamente a eso: el corpus corre con un conjunto de parámetros vacío y recoge los
`ParametroAusente`, así que **el inventario de lo que falta deja de escribirse a mano**.

## 5. Lo que la marcha blanca no va a poder hacer

Conviene que esté escrito antes, y no descubrirlo el día de la demostración:

- **No determinará importes correctos**, y no es un defecto del código. D-02a está cerrada, pero
  **ningún conjunto del ejercicio está sellado con cifras reales**: falta uno de los tres cuadros
  —los valores unitarios (H-14) de [GOB-03](plan-de-desbloqueo-D-02.md)— y el `% actualización` de
  D-11, que multiplica sobre importes y por eso omitirlo **no es neutro**. Mientras tanto el sistema calcula con parámetros de
  prueba. Por eso [#122](https://github.com/hneyra/sgtm/issues/122).
- **No cobrará**, y esto ya es solo una decisión de alcance. Cuando se escribió el plan, caja,
  recibos y cierre de caja (#33, #34, #36) y la emisión de valores (#37, #38) no existían; **hoy
  están cerrados y probados contra PostgreSQL**. Ampliar el alcance es de la Dirección; lo que este
  plan sostiene es que una ventanilla que cobre importes determinados con parámetros sin sellar
  cobra mal, y por eso el recorte sigue teniendo sentido mientras siga en pie el punto anterior.
- **No emitirá HR ni PU con validez.** La generación de documentos existe (#55) y la determinación
  los produce, pero un documento emitido bajo el tenant de demostración sale marcado como tal. La
  firma digital es D-05, y **no bloquea**: `PuntoDeFirma` es el enganche y su implementación por
  omisión devuelve el documento sin tocar.
- **No hay migración desde SQL Server** (D-04): el padrón de la marcha blanca se carga por
  [#121](https://github.com/hneyra/sgtm/issues/121), por el registro manual de las opciones del
  módulo de rentas y, para una instalación de demostración, por los nueve pasos de
  [`infra/carga-de-datos/`](../../infra/carga-de-datos/README.md), que exigen
  `municipalidad.es_demostracion = true` comprobado contra la base.
- **No tiene todavía fecha ni validador funcional.** D-01 eligió la municipalidad piloto
  —**Catacaos**, el 2026-08-24— y dejó sin nombrar a quien valide las reglas tributarias. Eso no lo
  cierra ningún PR.

## 6. Qué la da por terminada

- [x] `./gradlew build verificarAislamiento verificarArquitectura` y `yarn verificar` **corren en CI
      en cada PR** (`backend.yml`, `frontend.yml`), y son bloqueantes.
- [ ] **La marcha blanca levantada con un solo comando desde el repositorio limpio.** A medias:
      `despliegue.yml` la levanta entera en cada PR y la recorre peldaño a peldaño, pero a mano son
      cuatro pasos —`.env`, los datos de implantación, `docker compose up` y
      `reconciliar-identidades.sh`—, no uno.
- [ ] **Un usuario entra con su clave, ve solo las opciones de su perfil, registra un contribuyente,
      registra un predio con su ficha catastral, y consulta su determinación.** Las piezas están
      —la escalera de identidad la comprueba CI, los permisos los aprende la interfaz del backend
      (ADR-0013), y la determinación se pide y se lee desde #395—, pero **el recorrido completo no
      está medido de una pieza**.
- [ ] **Ese recorrido está como prueba de extremo a extremo, no como acta de una demostración.** Hay
      seis caminos en Chromium (`frontend/e2e/`), y uno de ellos es la determinación simulada con el
      teclado; corren contra el proxy de datos, no contra la instalación levantada.
- [x] **Todo documento que salga lleva la marca de demostración**, y hay pruebas que se ponen rojas
      si se le quita: 19 en el backend, y el peldaño 9 de `despliegue.yml` —«la marcha blanca se
      levanta MARCADA»— contra la instalación real.
- [x] **Los cinco issues nuevos están cerrados**, y los de la mitad «estructura» enumeran las filas
      del corpus que entregan.

## 7. Referencias

- [#58 — Hoja de ruta del backend](https://github.com/hneyra/sgtm/issues/58), de la que esto es un recorte.
- [#116 — Plan de desbloqueo de D-02](https://github.com/hneyra/sgtm/issues/116) (cerrado), paquetes E-3, E-5 y E-6; su estado de ejecución vive en [GOB-03](plan-de-desbloqueo-D-02.md) §0.
- [GOB-02 — Decisiones abiertas](decisiones-abiertas.md). Las que tocan a este plan: D-01 (el
  validador funcional), D-02b, D-02c, D-04, D-05 y D-11. D-02a, D-03a/b/c, **D-07**, D-12 y D-13 ya
  se cerraron, y D-15 nació decidida con ADR-0020.
- [`despliegue/README.md`](../../despliegue/README.md): las piezas, su orden y con qué rol se conecta cada una.
- [ARQ-03 — Estrategia multi-tenant](../30-arquitectura/estrategia-multitenant.md) §4, que es por qué la aplicación no migra.
- [ADR-0003 — Monolito modular](../30-arquitectura/adr/ADR-0003-monolito-modular.md): un artefacto, dos perfiles.
