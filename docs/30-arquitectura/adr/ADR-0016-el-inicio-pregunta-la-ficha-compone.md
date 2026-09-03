# ADR-0016 — El inicio pregunta y la ficha compone: las fases 3–5 de ADR-0014, sin el agregador que no hacía falta

**Estado:** Aceptado · 2026-08-28
**Decide:** los tres contratos que #296, #297 y #298 exigían decidir antes de implementar, y
corrige la premisa con que ADR-0014 los dejó «bloqueados por backend».

## Contexto

ADR-0014 §1 dejó la búsqueda transversal y la ficha 360° «bloqueadas por un endpoint agregador
del padrón que el backend aún no publica». El inventario contra el código dice otra cosa, en las
dos direcciones:

- **Lo que se esperaba ya existe.** `GET /consultas/unificada` (#25) es un agregador real:
  `@Transactional(readOnly = true)`, un solo `SET LOCAL` y un solo instante de lectura para
  deuda, pagos, altas y bajas, fraccionamientos, valores y declaraciones juradas, compuesto por
  cinco puertos ajenos solo por API pública, con `resumenDeSaldos` **sumado por el servidor** y
  cada cifra como `ImporteActualizado` con su fecha. El comentario del frontend que lo daba por
  inexistente estaba rancio.
- **Lo que había que «conservar» nunca existió.** El panel de recaudación del inicio apunta a
  `GET /indicadores/recaudacion`, que ningún controller sirve: solo el proxy. La premisa
  temporal estaba invertida.
- **La búsqueda no necesita backend nuevo.** `GET /rentas/contribuyentes` busca por código, por
  nombre **con aproximación** (`CriterioDeBusqueda`: sin tildes, apellidos invertidos, una letra
  de menos), por DNI y por RUC; `GET /consultas/vehiculos?placa=` y
  `GET /catastro/fichas?codRefCatastral=` cubren la placa y el predio. Tres lecturas, tres
  permisos distintos.

## Decisión

1. **La búsqueda transversal del inicio (#296) es un abanico de las tres lecturas publicadas,
   no un agregador.** El inicio pregunta a quién se atiende y consulta en paralelo
   `contribuyentes`, `consulta_vehiculos` y `consulta_fichas` — **cada una solo si el catálogo
   visible la ofrece** (`useCatalogoVisible`, ADR-0013). Es una decisión de fondo, no de
   conveniencia: con un agregador único, el cajero sin permiso de vehículos recibiría un 403
   que rompe la búsqueda entera o vehículos que no debe ver; con el abanico, cada resultado
   llega por el permiso que lo cubre y la ausencia de un permiso solo apaga su franja. Un
   endpoint unificado del backend queda como **optimización posible, no como requisito**, y si
   algún día existe deberá conservar esta semántica por permiso.

   El panel de recaudación no desaparece: deja de ser el inicio y queda como la opción que
   siempre fue, para quien dirige, vía lanzador — exactamente lo que ADR-0014 §1 prometía. Su
   backend sigue sin existir y su pantalla lo seguirá diciendo con el mecanismo de actos
   honestos.

2. **La ficha 360° (#297) compone opciones publicadas, pestaña a pestaña, con
   `consulta_unificada` como su columna financiera.** Cada pestaña declara de qué opción
   compone y viaja con **su** operación y **su** permiso (ADR-0014 §1 ya lo exigía; este ADR
   solo constata que las lecturas existen):

   | Pestaña | Opción / operación | Clave |
   |---|---|---|
   | Cabecera e identidad | `contribuyentes` | código, DNI, RUC, nombre aproximado |
   | Deuda, pagos, valores, convenios, DJ | `consulta_unificada` — y su `resumenDeSaldos` es **el único total consolidado con su fecha que el sistema publica**: es la cifra de la cabecera (regla 9) | código |
   | Predios (con deuda por predio) | `consulta_predios` | código |
   | Vehículos | `consulta_vehiculos` | código o placa |
   | Papeletas de tránsito | `papeletas` | **documento** del contribuyente (`documentoDelInfractor`) — la identidad la da `ContribuyenteResource.numeroDocumento` |
   | Papeletas administrativas | `adm_estado_cuenta` | código |
   | Coactiva | `coactiva_expedientes` | código |

   **Fronteras que se dicen, no se puentean:** las licencias solo se buscan por *nombre*
   (`nombreDelContribuyente`), y componer por nombre abre al homónimo — esa pestaña no se
   compone hasta que licencias publique búsqueda por código o documento (trabajo backend, con
   su issue). Las declaraciones juradas no tienen listado propio por contribuyente; llegan por
   la unificada, y eso basta.

   **Y los predios los manda `consulta_predios`, no `consulta_fichas`.** Son dos lecturas de
   dos módulos: la de rentas publica la deuda por predio y busca por código de contribuyente,
   que es lo que la ficha necesita; la de catastro busca por código de referencia catastral y
   sirve a la conciliación (ADR-0015). En la ficha manda la de rentas; `consulta_fichas` se
   queda donde está, en catastro y en el abanico del inicio.

   Las **acciones con el contexto puesto** usan el patrón `acto` del registro de composición
   (llevar a otra de las 134 con el registro en la ruta): nada nuevo que permisar, ninguna
   escritura fuera de `useEscritura`.

   Lo demás que la implementación tendría que decidir sola, decidido aquí con el criterio de
   §1 —una puerta que no publica lectura ni permiso propios no es una opción, y lo que un
   permiso niega no se dibuja—:

   - **La ficha es una ruta, no la opción 135.** Vive en `/atencion/:codigo` y no entra al
     catálogo, por lo mismo que el inicio y que el centro de reportes de ADR-0014 §5: no
     publica ninguna lectura propia ni tiene permiso que conceder — cada pestaña viaja con el
     de su opción. El segmento fijo `atencion` no choca con `/:moduloId/:ranura`: React Router
     puntúa por encima lo estático, y ningún módulo del catálogo se llama así.
   - **Una pestaña sin permiso no se dibuja.** Ni vacía, ni con error, ni deshabilitada: es
     exactamente la decisión de las franjas del inicio, trasladada — una pestaña vacía ya dice
     que ahí hay algo que mirar, y una deshabilitada invita a pedir lo que el permiso niega.
   - **Quien no tiene ninguna** ve la frase equivalente a la de §1, con el mismo reparto: si no
     tiene ninguna de las lecturas, que desde ahí no se puede componer nada; si tiene alguna
     pero no la de lo que buscaba, **cuál falta, nombrada con el rótulo del catálogo**, y por
     dónde sí puede mirar. Nunca «no existe».
   - **Las pestañas consultan al activarse, no al montar.** Siete abanicos al abrir la ficha
     tienen otro perfil de coste que el del inicio —donde son tres, cortas y sobre un texto que
     se acaba de teclear—: aquí cada una es la deuda entera, la de un padrón o el expediente
     coactivo de una persona. La cabecera y su resumen consolidado sí cargan al abrir, porque
     son la pregunta que trae a la gente a la ventanilla.

3. **El portal (#298) se separa por la tercera condición de ADR-0009, no por la primera — y su
   autenticación sigue siendo la frontera.** ADR-0009 fijó tres condiciones y conviene decir
   cuál se cumple, porque la que se cumple decide qué se puede construir:

   | # | Condición de ADR-0009 | Hoy |
   |---|---|---|
   | 1 | El flujo público pasa de una pantalla a **un recorrido con sesión propia** | **No.** Hay composición, pero no hay sesión propia: no existe realm ciudadano |
   | 2 | El contribuyente se autentica contra **un realm distinto** del de funcionarios (ADR-0005) | **No.** Es trabajo backend, con su issue |
   | 3 | El paquete del portal **arrastra código que solo usa el back-office** | **Sí.** Los ~11,5 KB de catálogo de doce módulos que midió el README del frontend (#81) — más el shell que lo dibuja — y que el ciudadano descarga para no usarlos nunca |

   La condición 3 basta —ADR-0009 pide *cualquiera* de las tres—, y es además la que describe
   el trabajo que hay: la separación barata que ADR-0009 §final anticipó. `apps/portal` consume
   los mismos paquetes **sin el shell ni el catálogo de navegación**, con su presupuesto propio
   medido y fijado a la baja. Lo que **no** se puede implementar todavía es el acceso del
   ciudadano, y por eso importa no dar por cumplida la 1: hoy el portal vive tras la puerta de
   sesión del realm de funcionarios y no existe ninguna lectura anónima. Mientras, el portal
   separado se sirve tras la misma sesión —la marcha blanca del funcionario que lo previsualiza—
   y **ninguna lectura se abre al público por el camino**.

   **La opción `portal` de las 134 sobrevive, y las 134 siguen siendo 134.** Su pantalla en el
   back-office se queda como está: es la vista del funcionario, con su id, su ruta y su permiso,
   y quitarla sería reescribir el catálogo del manual por un motivo de empaquetado.
   `apps/portal` no la sustituye — servirá al ciudadano el día que exista el realm que lo
   autentique.

## Consecuencias

- ADR-0014 §1 queda corregido en su premisa: la búsqueda y la ficha no estaban bloqueadas por
  backend; estaban bloqueadas por esta decisión. El panel de recaudación pasa de «inicio
  provisional» a opción del lanzador.
- El comentario rancio de `consultas/index.ts` ya quedó corregido, y
  `consulta_unificada`, `consulta_resumen_predial` y `consulta_valores` conectadas (#72):
  la ficha 360° las encuentra servidas.
- Backend pendiente, cada uno con su issue: búsqueda de licencias por código o documento;
  **`numeroDocumento` como filtro de `GET /rentas/contribuyentes`** — el contrato solo publica
  `codigo`, `nombreRazonSocial`, `dNI` y `rUC`, así que un carné de extranjería, un pasaporte o
  una partida no se pueden buscar por su número aunque `TipoDocumento` los admita y
  `CriterioDeBusqueda.porNumeroDeDocumento` exista en el dominio; mientras tanto el inicio no
  inventa la forma de esos documentos y a esas personas se las busca por nombre —; realm y
  lecturas del ciudadano para el portal público; y, si la operación diaria lo pide, el
  endpoint unificado de búsqueda del padrón como optimización — nunca como sustituto de la
  semántica por permiso.
- El inicio gana su entrada en el lanzador de ADR-0014 §2, la primera y sin permiso que
  comprobar: no es una opción del catálogo, es la puerta del shell, y hasta ahora la única
  vuelta era la marca de la barra lateral —que en móvil se pliega en cajón—.
