# ADR-0014 — Navegación centrada en la atención: la persona como inicio, los módulos detrás de un lanzador

**Estado:** Aceptado
**Fecha:** 2026-08-27

## Contexto

La navegación actual porta la del prototipo (FRO-03 §3): barra lateral de dos niveles, hub por
módulo y paleta de comandos. Funciona —las 134 opciones se alcanzan y la paleta se opera con el
teclado—, pero la carga se midió recorriendo la aplicación en Chromium y es real:

- **Tránsito tiene 23 opciones, 15 en un solo bloque «Documentos y reportes»**; la barra lateral
  de su nivel módulo no cabe en una pantalla de 900 px. Rentas · Registro tiene 15,
  Infracciones administrativas 13, Catastro y Coactiva 12.
- Los cuatro bloques —Registro y mantenimiento, Procesos, Consultas, Documentos y reportes—
  clasifican por **tipo técnico de pantalla**, no por la tarea del usuario. «¿Dónde estaba lo del
  descargo de una papeleta?» no se responde con esa taxonomía, y por eso cuesta recordar dónde
  estaba cada cosa.
- El flujo real de ventanilla empieza por **una persona** (un DNI, una placa, un predio), no por
  un módulo. Hoy ese flujo exige elegir módulo → opción → buscar el registro, tres decisiones
  antes de la única que importa.

Se exploraron tres alternativas con maquetas sobre los tokens de Juris PE —reagrupar por tarea,
priorizar por frecuencia, y navegar por el contribuyente— y dos mecanismos de acceso a los
módulos —el menú de la persona y un lanzador tipo Google/Microsoft—. El registro visual de la
exploración quedó en el canvas de diseño de la sesión (Artifact «Navegación SGTM», cuatro
páginas), del que derivan las capturas citadas por los issues.

## Decisión

**La atención al contribuyente pasa a ser la superficie de trabajo; los módulos completos quedan
detrás de un lanzador, reagrupados por tarea.** En concreto:

### 1. El inicio es la atención, y la ficha 360° es el norte

Al entrar, el sistema pregunta a quién se atiende: una búsqueda por DNI, nombre, placa o código
de predio. Abrir a la persona muestra su **ficha 360°**: pestañas que componen funcionalidades de
otros módulos centradas en ella (predios, vehículos, papeletas, licencias, deuda, coactiva), con
las acciones lanzadas **con el contexto puesto**. La ficha **no es un módulo**: cada pestaña y
cada acción es una de las 134 opciones, con su misma operación del contrato y su mismo permiso.
La misma composición, en solo lectura, es lo que el portal ciudadano muestra al propio
contribuyente.

Esto depende de un endpoint agregador del padrón que el backend aún no publica, y
[`ADR-0010`](https://github.com/hneyra/sgtm/blob/migracion-a-microservicios/docs/30-arquitectura/adr/ADR-0010-catalogo-portado-y-proxy-de-datos.md) prohíbe fingirlo en el proxy: la
ficha 360° se implementa **cuando su backend exista**, y hasta entonces el inicio conserva el
panel de recaudación.

### 2. Los módulos, detrás de un lanzador de nueve puntos

Un botón en la cabecera —el patrón de Google Workspace / Microsoft 365— lista los doce módulos y
abre el elegido. Se filtra con el **mismo catálogo visible** que ya filtra el menú, el hub y la
paleta (REQ-03 §5): un módulo sin opciones visibles no aparece. Las rutas no cambian: cada opción
conserva la suya, así que los enlaces compartidos y los marcadores siguen cayendo en el mismo
sitio.

### 3. El menú de la persona, para lo personal

El nombre del usuario en la cabecera abre un menú con lo suyo: cambiar el año de trabajo,
preferencias y el acceso a Seguridad —que conserva además su entrada como módulo: dos puertas,
mismo permiso, misma pantalla—. La entrada de preferencias llega cuando exista un panel de
preferencias que abrir: el menú ofrece solo lo que opera.

### 4. Dentro de cada módulo, grupos por tarea

Los cuatro bloques técnicos se sustituyen por grupos que nombran el objeto de trabajo —en
Tránsito: Papeletas, Vehículos, Cobranza, Catálogos—, definidos **módulo a módulo** en el
portador del catálogo, empezando por los cuatro diseñados (Tránsito, Rentas · Registro, Valores,
Seguridad); los demás conservan la clasificación actual hasta que se diseñe la suya. Los nombres
de las opciones **no se reescriben** (RNF-080): cambia solo la agrupación.

### 5. Los reportes de un módulo se pliegan en un centro de reportes

Las hojas de un módulo dejan de competir con sus operaciones en el menú: una sola entrada
«Reportes» abre un centro donde se elige la hoja, se dan sus criterios y se emite. Tránsito
primero —13 hojas—, con el mismo bloque de hoja parametrizado que ya comparten sus trece
reportes. Cada hoja conserva su identificador de opción, su ruta y su permiso.

#### Enmienda (2026-08-29, issue [#391](https://github.com/hneyra/sgtm/issues/391))

Plegar el menú y dibujar el carril **eran la misma marca**, y por eso un módulo sólo podía plegar un
grupo. Al unificar Catastro en cuatro superficies aparecieron grupos que quieren lo primero y no lo
segundo: sus opciones ya se navegan entre sí —el conmutador de modalidad de la ficha, las pestañas
de hoja del territorio y del cuadro—, así que un carril al lado sería una segunda forma de navegar
lo mismo. Las dos cosas se separan:

| Marca | Qué hace |
|---|---|
| `{ plegado: true }` | El menú enseña una entrada, que abre la primera opción visible. Nada más |
| `{ centro: true }` | Pliega igual **y además** dibuja el carril del centro de reportes |

> Un grupo se pliega cuando **su superficie ya sabe navegar entre sus opciones**. Si además sus
> opciones no tienen otra forma de alcanzarse entre sí, el pliegue lleva carril. Lo primero puede
> darse varias veces en un módulo; lo segundo, **una sola**.

El límite de «uno por módulo» se conserva sobre `centro`, con el motivo corregido: no era que
hubiera dos entradas homónimas «Reportes» —la barra lateral y el hub dibujan el nombre del grupo—
sino que serían dos formas de navegar lo mismo. Tránsito, Infracciones administrativas y
Autorizaciones y licencias declaran las dos marcas y no cambian de comportamiento.

Y se añade la condición que el mecanismo no comprobaba: **antes de plegar hay que demostrar que la
superficie lleva a todas las opciones del grupo**, recorriéndolas. Es lo que dejó «Predio» sin
plegar en Catastro, por un motivo que no es de interfaz: el reporte de ficha se abre por el código
del contribuyente y ninguna superficie del módulo lo tiene. El patrón entero está en
[`FRO-05`](https://github.com/hneyra/sgtm/blob/migracion-a-microservicios/docs/60-frontend/superficies-unificadas.md).

## Consecuencias

- **Las 134 opciones, sus rutas y sus permisos no se tocan**: el identificador de la opción sigue
  siendo la clave del permiso, y `useCatalogoVisible` filtra el lanzador y el menú igual que hoy
  filtra la barra, el hub y la paleta. Una puerta nueva no es una superficie de exploración
  nueva.
- La reagrupación vive en una tabla exhaustiva (`scripts/grupos-por-tarea.mjs`), importable y con
  guardas probadas; `bloqueDe` queda como respaldo de los módulos sin tabla. Las pruebas
  del catálogo que fijan los bloques se actualizan, y una prueba nueva exige que todo módulo con
  grupos por tarea asigne **cada una** de sus opciones exactamente una vez.
- La ficha 360° y la búsqueda transversal del inicio quedan **bloqueadas por backend**; se
  registran como issues dependientes, no como trabajo del frontend en solitario.
- Separar el portal del shell del backoffice —que esta decisión facilita, porque el portal no
  tiene módulos que navegar— sigue siendo la conversación que el presupuesto de paquete dejó
  abierta ([`ADR-0009`](https://github.com/hneyra/sgtm/blob/migracion-a-microservicios/docs/30-arquitectura/adr/ADR-0009-plataforma-frontend.md)); esta decisión no la cierra.

## Alternativas consideradas

- **Solo reagrupar por tarea** (mantener el módulo como inicio): arregla la sobrecarga pero no el
  flujo de ventanilla, que seguiría exigiendo tres decisiones antes de la persona.
- **Hub por frecuencia de uso**: complementaria, no excluyente; queda disponible como mejora del
  hub cuando haya telemetría local de uso, y no se decide aquí.
- **La administración solo en el menú de la persona** (sin lanzador): una puerta discreta pero
  poco descubrible, y dos clics permanentes para quien administra a diario. El lanzador es
  navegación del sistema y merece la cabecera; el menú guarda lo personal.
