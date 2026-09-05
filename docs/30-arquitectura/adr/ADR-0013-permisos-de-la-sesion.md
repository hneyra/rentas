# ADR-0013 — La interfaz aprende sus permisos del backend, no del token

**Estado:** Aceptado
**Fecha:** 2026-08-27

## Contexto

[`ADR-0005`](https://github.com/hneyra/infrastructure/blob/main/docs/30-arquitectura/adr/ADR-0005-identidad-y-acceso.md) separó autenticación (OIDC, en Keycloak) de
autorización (el modelo del manual, en la base). El realm versionado
([`realm-sgtm.json`](https://github.com/hneyra/infrastructure/blob/main/despliegue/identidad/realm-sgtm.json)) lleva **un solo** mapeador:
`municipalidad_id`. El token dice **quién eres** y **en qué municipalidad**, y nada más.

La interfaz, sin embargo, necesita saber **qué puede hacer cada usuario** para dibujar el menú: la
barra lateral, el hub de cada módulo y la paleta de comandos leen de
[`permisos.ts`](https://github.com/hneyra/sgtm/blob/migracion-a-microservicios/frontend/packages/sesion/src/permisos.ts) —entonces en
`apps/backoffice/src/app/sesion/`; hoy en `@kamayuk/sesion` (#298)— y esconden lo que el
usuario no puede ver. Ese archivo obtenía los permisos de un claim `permisos` del token —y ese
claim **no lo producía nada**: ni el realm, ni el guion de reconciliación, ni el backend, y no
había endpoint que lo devolviera—. Un comentario en `permisos.ts` lo dejaba pendiente («que lo
traiga el token o una operación del contrato lo deciden #9 y #12»); #9 y #12 se cerraron sin
resolverlo.

El síntoma, en `prod`: el administrador de una municipalidad —con las 134 opciones y los siete
privilegios en la base tras [`ADR-0012`](https://github.com/hneyra/infrastructure/blob/main/docs/30-arquitectura/adr/ADR-0012-usuarios-y-grupos-declarativos.md) e
`ImplantarMunicipalidad`— entraba y **no veía ningún módulo**: `permisosDelClaim(undefined)` →
`NINGUNO` → menú vacío por negación por omisión. El guardia del backend
(`ComprobadorDeAccesoJdbc`, sin caché) sí honraba las filas de la base, así que las llamadas de
API pasaban; era solo el menú el que estaba ciego.

## Decisión

**La interfaz pide sus permisos efectivos a un endpoint del backend, `GET
/api/v1/seguridad/sesion/permisos`, una vez por sesión (y en cada renovación del token). El token
no lleva permisos.**

### 1. El endpoint

Devuelve la matriz del usuario en curso: `{ "<opcion>": ["lectura", "registro", …], … }`, solo las
opciones sobre las que tiene algún privilegio. La consulta (`PermisoRepository.efectivosDe`) usa
la **misma precedencia** que el guardia: la excepción de usuario decide —otorgue o niegue—, y si
no la hay manda la unión de sus grupos vigentes; vigencia y habilitación se comprueban en el
usuario, en el grupo y en la pertenencia (RF-123). Un usuario deshabilitado recibe `{}`.

Resolverlo con otra regla que la del guardia mostraría opciones que después responden 403, o
escondería opciones que sí funcionan; por eso es una consulta, no una lista aparte.

### 2. No es una opción del catálogo

El endpoint se marca `@RequiereAcceso(acceso = RequiereAcceso.SESION_PROPIA, …)`, un centinela que
el `GuardiaDeAcceso` reconoce y **no comprueba contra el catálogo**: pasa con solo un token
válido. Es la primera —y por ahora única— excepción a «todo endpoint declara un acceso del
catálogo», y es deliberada:

- Leer los permisos propios **no revela nada** que el usuario no pueda enumerar probando cada
  endpoint con `curl` (REQ-03 §5: que la interfaz oculte una opción es comodidad, no seguridad).
- El centinela **está declarado**: la regla de ArchUnit que exige `@RequiereAcceso` sigue
  mordiendo, y un endpoint sin anotación se sigue denegando. Lo que cambia es que este dice, a la
  vista, «autenticado basta».

### 3. La interfaz

`ProveedorDeSesion` pide la matriz con `pedirOperacion('permisos_de_la_sesion')` en cuanto tiene
el token, y la vuelve a pedir en cada renovación —así un cambio de permisos entra sin que el
usuario cierre sesión—. **Si la petición falla, `NINGUNO`**: negación por omisión, no un menú
completo que falla en cada pulsación. `DatosDelToken` deja de tener un campo `permisos`.

El proxy de datos ([`ADR-0010`](https://github.com/hneyra/sgtm/blob/migracion-a-microservicios/docs/30-arquitectura/adr/ADR-0010-catalogo-portado-y-proxy-de-datos.md)) publica el
endpoint con la forma del backend; en modo prototipo devuelve las 134 opciones con los siete
privilegios, porque no hay una sesión real de la que sacarlos y la demostración tiene que llegar a
todas las pantallas.

## Consecuencias

**Positivas**

- El menú refleja la base: otorgar o retirar un permiso se nota en la siguiente sesión —o
  renovación— sin tocar Keycloak.
- La autorización queda **entera en la base** (ADR-0005): ni un mapeador propio de Keycloak, ni
  acceso del proveedor de identidad a la base del padrón, ni la lógica del guardia duplicada en un
  script de Keycloak.
- Un solo sitio produce la matriz —`PermisoRepository.efectivosDe`— y lo comparten el endpoint y
  el guardia, con la misma precedencia.

**Negativas / costos aceptados**

- **Una petición más en el arranque de la sesión.** Entre el token y la respuesta de
  `/seguridad/sesion/permisos` el menú está vacío unos milisegundos.
- **Un endpoint que no comprueba el catálogo.** Es una excepción a un invariante; se acota a
  «leer la sesión propia», se declara con un centinela visible en el diff y tiene su prueba en
  `GuardiaDeAccesoTest`.

## Alternativas consideradas

- **Un mapeador de Keycloak que emita el claim `permisos`.** Un script mapper o SPI propio que
  consulte la base del padrón al emitir el token. Se descarta: mete una dependencia de la base en
  Keycloak, duplica la consulta de autorización, y cruza la frontera identidad/backend que el
  repositorio mantiene a propósito.
- **Que la interfaz muestre todo y deje que el backend responda 403.** Es lo que `permisos.ts`
  rechaza: «un menú completo que falla en cada pulsación». Y contradice REQ-03 §5 —restringir
  opciones reduce el error—.
- **Un `Secret` efímero con la matriz.** No hay dónde ponerlo sin que quede una copia que alguien
  tiene que acordarse de borrar.
- **Gatear el endpoint con una opción del catálogo (`mis_permisos`) sembrada y otorgada a todos.**
  Chicken-and-egg: la interfaz necesita permisos para saber que puede pedir permisos, y un usuario
  al que no se le otorgara esa opción se quedaría con el menú vacío para siempre.

## Enlaces

- [`ADR-0005`](https://github.com/hneyra/infrastructure/blob/main/docs/30-arquitectura/adr/ADR-0005-identidad-y-acceso.md) — OIDC autentica, la base autoriza ·
  [`ADR-0010`](https://github.com/hneyra/sgtm/blob/migracion-a-microservicios/docs/30-arquitectura/adr/ADR-0010-catalogo-portado-y-proxy-de-datos.md) — el proxy publica el endpoint ·
  [`ADR-0012`](https://github.com/hneyra/infrastructure/blob/main/docs/30-arquitectura/adr/ADR-0012-usuarios-y-grupos-declarativos.md) — los permisos del primer administrador
- REQ-03 §5 ([`docs/20-requisitos/actores-y-permisos.md`](https://github.com/hneyra/sgtm/blob/migracion-a-microservicios/docs/20-requisitos/actores-y-permisos.md))
- `SesionController#permisosDeLaSesion` · `PermisoRepository#efectivosDe` ·
  `GuardiaDeAcceso` / `RequiereAcceso.SESION_PROPIA` ·
  `frontend/packages/sesion/src/ProveedorDeSesion.tsx` ·
  `frontend/packages/sesion/src/permisos.ts` (hoy en `@kamayuk/sesion`, #298)
