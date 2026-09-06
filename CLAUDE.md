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
| `backend/kamayuk-rentas-parametros` | **Ya no publica ningún valor normativo**: eso es de `normativa` desde P5B (ADR-0025). Lo que queda aquí es el **cliente** —`LectorDeParametros` con la misma firma de siempre, leyendo de la copia local de un conjunto sellado (`V3`)— y las reglas puras, que todavía viven en los dos repositorios (hueco declarado: `normativa/docs/00-gobierno/P5B-extraccion.md` §7.1) |
| **Un repositorio hermano más** | Desde P5B, `./gradlew test` **no pasa sin `normativa` clonado al lado**: tres clases comprueban que la llave con que su derivado publica un valor es la que este backend pide (#192). Si no está, fallan nombrando el `git clone`; no se saltan |
| `backend/kamayuk-rentas-esquema` | **Existe, con su baseline y ocho migraciones más.** `V1` nació con 132 tablas; `V2`, `V6` y `V7` retiran las **31** que se fueron a los otros tres sistemas, y `V3`…`V5`, `V8` y `V9` traen las **11** que nacen del corte —la copia sellada de normativa, la proyección de catastro, la valuación recibida, el buzón de pagos y su procedencia—. **113 tablas vivas.** `verificarAislamiento` corre **222 pruebas** —45 del esquema y 177 del pool—, 0 fallos |
| Las extensiones que pide su base | **Dos, y no cuatro** (P5E): `pg_trgm` y `unaccent`, las dos *trusted*. `postgis` y `btree_gist` salieron de `crear-roles.sql` porque nada del esquema final las usa —medido: ni una columna PostGIS, ni un índice GiST, ni una restricción `EXCLUDE`— y lo que las mantenía vivas era `V1`, que creaba la geometría del predio para dejarla caer en `V6` |
| `backend/kamayuk-rentas-aplicacion` | **Existe.** `verificarArquitectura` corre **130 pruebas**: las barreras de la librería común más las propias de este sistema (contrato, formas, respuestas, límites de Modulith) |
| `docs/30-arquitectura/adr/` | **Existe**, 11 ADR propios más los que enlaza |
| **Código de negocio** | **Existe.** Llegó entero en P5A ([P5A](docs/00-gobierno/P5A-copia-del-backend.md)), copiado de `sgtm@0d33ad7b` con el mismo número de pruebas: 3 756 = 3 756. Lo que sigue calculando **da el mismo céntimo**: dos archivos comparados byte a byte contra el árbol anterior a P5C, con las mismas huellas que P5B y P5C publicaron ([P5E](docs/00-gobierno/P5E-cierre.md) §4) |
| **La lista de cruces de frontera** | **Vacía en los cuatro repositorios** ([P5E](docs/00-gobierno/P5E-cierre.md) §2). Es el criterio de que la separación terminó: no que los repositorios existan, sino que ninguno lea por SQL una tabla que ya no es suya. Y está medido que la regla puede fallar, repositorio por repositorio |
| Su esquema (`V1__baseline.sql`) | **Está aquí**, en `backend/kamayuk-rentas-esquema/src/main/resources/db/migration/`. Es una migración de Flyway y no un `esquema.sql` suelto (ADR-0032 §2) |
| Su frontend (`rentas-web`) | **NO existe** |
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
infrastructure/         el descriptor de despliegue en TypeScript, con yarn
docs/                   ADR propios, hallazgos de RLS y esta guía de desarrollo
```

El backend **no compila sin `infrastructure` clonado al lado**: las barreras se consumen como
*composite build* desde `../../infrastructure/librerias-backend`. `settings.gradle.kts` lo
comprueba antes y falla diciendo qué `git clone` falta, en vez de dejar reventar a Gradle sobre un
directorio que no está.

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
| D-11 | El **`% actualización`**: sigue sin fuente. Su valor neutro es **cero**, no uno | `RT-002`, `RT-005`, `RT-011` |
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
## Comandos

```bash
cd backend
./gradlew verificarArquitectura   # ArchUnit, escaner de fuentes, aserciones y frontera de sistema
./gradlew verificarArranque       # el artefacto levanta en los dos perfiles (C-7). Requiere PostgreSQL 16
./gradlew verificarAislamiento    # aislamiento multi-tenant. BLOQUEANTE. Requiere PostgreSQL 16
./gradlew build                   # lo anterior mas Spotless
./gradlew spotlessApply           # arregla el formato en vez de solo reprocharlo

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
| **P5B — la extracción de `normativa`: `LectorDeParametros` conserva su firma y cambia de fuente** (7 pruebas de frontera contra PostgreSQL real, con el cliente HTTP de verdad y un puerto que nadie escucha; 1 comparación de padrones entre dos árboles de git) | Cinco roturas, cada una sola y restaurada por copia comparada con `diff -r`: que el recálculo vuelva a llamar por red —quitando la comprobación de caché en las **dos** capas—; que el cliente no verifique la huella antes de cachear; que el repliegue diga `EjercicioSinSellar` en vez de `NormativaInalcanzable`; quitar la resolución de vigencia de #659; y recortar a dos decimales la precisión del valor leído | **5 de 7**, 1, 1 en rojo; y las dos últimas contra el padrón: la de #659 **ni llega a escribir el archivo** —falla con `VigenciasQueSeSolapan` porque las cinco filas de `UIT` colisionan, que es mejor que un diff: el sistema se **niega** en vez de elegir— y la de la precisión da un diff de seis líneas. **El padrón antes y después es el mismo archivo**, mismo `sha256`, corriendo la misma clase en un *worktree* de `rentas@772a8d7`. **Y lo que no sujeta ninguna prueba queda escrito**: que no haya una consulta por parámetro dentro de un bucle lo sostiene que `PublicadorDeNormativa` tenga **dos** métodos y ninguno sepa contestar por partida; el día que alguien añada `uitDe(ejercicio)`, la propiedad se pierde sin que nada se ponga rojo. Y hay **dos copias de las reglas puras** —47 pruebas duplicadas— sin nada que impida que diverjan: unificarlas es sacar `kamayuk.rentas.dominio` a una librería compartida, **938 archivos** medidos con `grep`. Los dos huecos están en `normativa/docs/00-gobierno/P5B-extraccion.md` §7.1 |
| **P5D — la mitad de `CobrarDeuda` que se quedo aqui: pedir que se cobre** (`EmitirOrdenDeCobro`, `POST /rentas/api/v1/ordenes-de-cobro`; 11 pruebas del caso de uso y 5 del borde) | Cinco roturas sobre `src/main`, cada una sola y restaurada **por copia comprobada con `cmp`**: que la referencia pierda su fecha; quitar la guarda de la obligacion repetida; quitar `NadaQueCobrar`; tomar el `insoluto` en vez del total; y releer el libro dentro del bucle | **2**, 1, 1, 1 y 1 en rojo. **El defecto no era un hueco sino una regresion que la propia extraccion introdujo**, y la delato contar las pruebas metodo a metodo: `CobrarDeuda` leia el libro y cobraba en un solo acto, la resta la borro y **solo se reescribio la mitad de cobrar** —de modo que la ventanilla podia cobrar una tasa de punta a punta y una deuda tributaria no, porque nadie emitia su orden—; los siete metodos huerfanos de `CobrarEnVentanillaTest` eran la medida exacta de eso. **La primera rotura ensena la decision**: la fecha va DENTRO de la referencia porque es la regla 9 aplicada a la identidad de la orden —dos emisiones del mismo dia son un reintento y devuelven la que ya estaba; dos de dias distintos son dos importes y son dos ordenes—, y sin ella la primera emision congelaria el importe para siempre y el interes devengado despues no se podria cobrar por ninguna via, sin que nada lo dijera. Y **la peticion no tiene componente para un importe ni para una campana de beneficio**, afirmado sobre los componentes del `record` y no sobre un comentario |
| **C-1 — los dos desajustes con `caja`, y los tres que este lado pagaba con `catastro`** (`V10`: la anulación dice por qué y cuándo; 3 pruebas nuevas contra PostgreSQL real, 3 del lado de la petición y 2 de la lectura del cuadro) | Seis roturas, cada una aplicada **sola** sobre `src/main` y restaurada **por copia comparada con `cmp`**: que `PeticionDePago` deje de declarar `motivo` y `fecha`; que la reversión vuelva a asentarse con la fecha del recibo original; que la observación de la reversión vuelva a componerse sin el motivo de la caja; que el adaptador vuelva a mandar `?aLaFecha=` con el contrato ya diciendo `fecha`; y que vuelva a iterar `contenido` sobre la respuesta del cuadro | **2 en rojo** en `ContratoConCajaTest`, nombrando los dos campos: «falta el campo «(el cuerpo).fecha», que el consumidor manda». **1** la de la fecha, con las dos fechas dentro: «Expecting ArrayList: [2026-03-16] to contain only: [2026-07-16]» — anular en julio un recibo de marzo escribía la reversión en marzo, y un estado de cuenta al 30 de abril recalculado después cambiaba de respuesta. **1** la del motivo: «Expecting actual: "Reversion del pago … por la anulacion del recibo 001-C1-G" to contain: "ERROR EN EL IMPORTE COBRADO"». **Y las dos últimas enseñan lo que faltaba**: revertir sólo el adaptador —sin tocar el contrato comprometido— deja el CI del PROVEEDOR en **VERDE**, porque compara el archivo y el archivo no cambió; lo cazan sólo las dos guardas nuevas de este lado (`PeticionesACatastroTest`, «expected [… «fecha» …] but was [«aLaFecha» …]», y la ida y vuelta del cuadro, «Expected size: 1 but was: 0»). **Y una premisa del registro de P6 resultó falsa al medirla**: decía que el motivo «sobrevive solo dentro de la columna `cuerpo`, que es jsonb». No sobrevivía en ninguna parte — `PagoController.congelar` reserializa el `record`, así que lo que se guarda es exactamente lo que el `record` declara |
| **C-6 — los cuatro pasos de la siembra que son de `rentas`, con su guion, y `fichas.csv` leido del clon hermano** (`ArchivosDeEjemploDeRentasTest`) | Devolver la copia de `fichas.csv` a este repositorio, y —del lado de `infrastructure`— devolver `cargar-transferencias-demo.sh` a `catastro` | 2 en rojo en `siembra-de-la-demostracion.test.ts`: «fichas.csv: rentas, catastro» y «rentas/fichas.csv» sin paso que lo cargue. **Este directorio conservaba SEIS CSV ajenos**, herencia de P5A, byte a byte identicos a los de sus duenos y sin nada que impidiera que divergieran: la copia que alguien edita no tiene por que ser la que el cargador lee. Ahora `fichas.csv` se lee del clon de `catastro`, igual que `catastro` lee `contribuyentes.csv` de aqui (hueco 5 de P5C), con el mismo costo escrito en su javadoc. **Y los cuatro guiones de `rentas` no estaban en `rentas`**: estaban en `infrastructure`, donde no vive ninguno de sus cuatro cargadores. Las 3 121 pruebas siguen en 3 121, 0 fallos, con `--rerun-tasks` contra PostgreSQL 16.15 real. Lo que sigue sin poder correr —los pasos 9 y 10, que necesitan los predios de `catastro`— esta medido con su error exacto en [C-6](https://github.com/hneyra/infrastructure/blob/main/docs/00-gobierno/C-6-la-siembra-orquestada.md) §6 |
| **C-7 — `rentas` arranca, en los dos perfiles** ([C-7](https://github.com/hneyra/infrastructure/blob/main/docs/00-gobierno/C-7-que-arranquen.md): 15 archivos de `src/main` a Jackson 3, la prueba de arranque y `verificarArranque`, una barrera nueva) | Dos roturas sobre el artefacto de verdad, restauradas por copia comparada con `cmp`: devolver un cliente HTTP entero a Jackson 2 —el estado exacto anterior a C-7, con su dependencia—; y la variante a medias, el `ObjectMapper` de Jackson 2 con el `JsonNode` de Jackson 3 | **4 de 4 en rojo** la primera, con el mensaje que C-6 midió letra por letra: «required a bean of type `com.fasterxml.jackson.databind.ObjectMapper`». La segunda **no compila**, y esa es la guarda más fuerte: retirar la dependencia de Jackson 2 de los siete `build.gradle.kts` hace que el defecto no se pueda escribir. **Y las formas del JSON se midieron byte a byte antes de afirmar que no cambian**: serializando los mismos objetos —el árbol del evento de pago, `PeticionDePago` y `SnapshotResource`, con las clases reales de los jars— con el `ObjectMapper` de Jackson 2 y con el `JsonMapper` de Jackson 3, las tres cadenas salen **idénticas**; el tercero importaba más que los otros dos porque el `ETag` del snapshot sellado **es el sha256 de esos bytes** (ADR-0025). `desajustesVivos()` sigue vacío y las nueve pruebas de contrato entre repositorios, en verde |
| **D — quien publica las dos imagenes de `rentas`** (`publicar-imagenes.yml`: `kamayuk-rentas` y `kamayuk-rentas-migrador`, etiquetadas con el `sha` de este repositorio, mas el trabajo que le pregunta al registro si la etiqueta se puede pedir) | La rotura no hubo que provocarla: **el estado de partida era el defecto**. Medido contra `ghcr.io` el 2026-09-05 con un token emitido por `https://ghcr.io/token`, las dos etiquetas que el manifiesto de `infrastructure` pide contestaban `404 MANIFEST_UNKNOWN` | Ninguno de los cinco repositorios publicaba una sola imagen —`publicar-imagenes.yml` se quedo en `sgtm`, el archivo historico, y lo que los cinco tienen se llama `registro.yml` y es la guarda de #711—, asi que un `pulumi up` habria dejado los pods en `ImagePullBackOff` **sin que nada lo predijera**: el manifiesto es valido y el planificador ubica el pod. **Dos decisiones con su motivo.** (1) La etiqueta es el `sha` de ESTE repositorio y no `applicationBootstrapVersion` —que es un `sha` de `sgtm`, una revision que ni siquiera existe en este clon—: una etiqueta que no resuelve contra ningun `git log` no identifica nada, y entonces «que corre en la municipalidad» deja de tener respuesta. (2) **Sin filtro `paths`**, al reves que el flujo del monolito, para que valga la equivalencia que la guarda de `infrastructure` necesita: *todo commit de `main` tiene sus dos imagenes*. Con filtro, un merge de solo documentacion deja un `sha` de `main` sin imagenes y «esta en la historia de main» deja de implicar «se puede desplegar», en silencio. **Y el trabajo `comprobar` no sobra**: un `build-push-action` en verde dice que el `push` no dio error; que la etiqueta se pueda PEDIR es otra afirmacion, y es la que decide si el pod arranca. Distingue los tres desenlaces a proposito, porque el tercero engaña: `200` existe, `404` no existe, y `403 DENIED` —lo que recibe un PAT de escritorio sin `read:packages`, comprobado— **no permite concluir nada** y por eso tambien falla, en vez de dar por buena cualquier respuesta que no sea 404 |
| **T-0 — las dos consultas de texto libre que ninguna guarda veia** (la exencion declarada de `busquedasDeTextoLibreConMotivo()` en `ConfiguracionDelSgtm`, para la regla nueva de [ADR-0034](https://github.com/hneyra/catastro/blob/main/docs/30-arquitectura/adr/ADR-0034-el-marco-y-el-operador-espacial.md) §3) | **No hubo que provocar el defecto: llego al correr la guarda nueva.** Y despues, una rotura: quitar `CodigoInfraccionRepositoryJdbc` de la lista de exenciones | **2 en rojo** sin tocar nada, en `NotificacionAdministrativaRepositoryJdbc` y `CodigoInfraccionRepositoryJdbc`: las dos escriben `ILIKE :param` con el comodin antepuesto **en Java** (`put("texto", "%" + t + "%")`), o sea un «contiene» sobre una tabla de tenant que recorre las filas del inquilino por construccion. **El primer diagnostico fue el equivocado y hubo que corregirlo**: leidas solo en el SQL, las dos parecen busquedas por PREFIJO y la guarda les pedia un rango —que es lo que arregla el tercer hallazgo de RLS—, cuando un comodin por delante **no tiene forma de rango**: no llega a ningun indice b-tree, con RLS o sin ella. Con el patron que mira el `"%"` del lado de Java, el mensaje pasa a ser el suyo: «un LIKE con el comodin por delante recorre el padron entero y no tiene forma de rango». **No se arreglan**, y el motivo esta escrito en la lista: cerrarlas no es cambiar una consulta sino decidir que hace esa pantalla —hoy ofrecen «el motivo contiene…» y «la descripcion contiene…»—, y eso es del dueno de `sanciones`. La lista **es la lista de trabajo pendiente**: quitarle una entrada da 1 en rojo nombrando la clase. `./gradlew build` en verde con las dos declaradas **Cifras, con la linea base medida en el mismo entorno**: `catastro` **999 -> 1 011**, `rentas` **3 150 -> 3 161**, `normativa` **623 -> 634** y `caja` **693 -> 704**, 0 fallos los cuatro contra PostgreSQL 16.13 + PostGIS 3.4.2 real. Los **+11** son los mismos en los cuatro y salen de la libreria compartida —nueve pruebas nuevas del escaner mas las dos reglas de ArchUnit, que `ReglasDeArquitecturaMuerdenTest` cuenta una por regla—; el **+12** de `catastro` es esa docena mas el caso del marco en la prueba de aislamiento. `yarn verificar` no se mueve: 38 rojas antes y 38 despues, las mismas una a una. |
| **Las catorce tablas de `catastro` que este reparto no nombraba, y la regla 11 dejaba de revisar** (`DE_CATASTRO` pasa de 16 a **30**: `catastro_evento` —el buzón de salida, sin nombrar desde C-8—, las cuatro de `V7`, las tres de `V8`, las cinco de `V9` y `frente_derivacion` de `V10`) | **La rotura es un contraste de dos mitades, y hay que hacer las dos**: escribir en `src/main` de `caja` un cruce de verdad —`SELECT r.id FROM recibo r JOIN catastro_evento e ON e.predio_id = r.id`— y correr la frontera **con** la tabla en el reparto y **sin** ella. Los dos archivos restaurados por copia comparada con `cmp` | **1 en rojo con la tabla nombrada, y `BUILD SUCCESSFUL` sin ella.** El rojo dice el defecto entero: «la tabla «catastro_evento» es de «catastro» y esto es «caja»: el dia que la base se parta, esta consulta deja de funcionar en produccion y no antes. Se pide por un puerto, o se registra como cruce consentido con el issue que lo cierra: `JOIN catastro_evento`». **Y el verde es la mitad que importa**: es el MISMO cruce, en el MISMO archivo, y pasa sólo porque la tabla no estaba en el mapa — el reparto se consulta con `getOrDefault(tabla, SISTEMA_REPLICADO)` y «replicado» significa «no está a ningún lado de la frontera», así que una tabla que **falta** no da un cruce: **deja de revisarse**. Es la lección de R-N por el eje de las tablas, y no es hipotética: `catastro_evento` llevaba **sin nombrar desde C-8**, o sea que el buzón de salida de `catastro` no lo vigilaba nadie en ninguno de los cuatro sistemas. Lo destapó el censo que `catastro#7` escribió del lado del dueño (`ningunaTablaDelEsquemaSeQuedaFueraDelReparto`), que **allí** encontró cinco huecos; contadas contra los tres repartos de aquí eran **catorce**. Nombrar de más no cuesta nada —ningún archivo de este repositorio menciona ninguna de las catorce, medido— y es exactamente lo que hace que el cruce, si llega, se vea. **Y se comprobó que ninguna colisiona**: ninguno de los tres esquemas crea una tabla con esos nombres (`acta` es la del hallazgo de `catastro`; la tributaria de `rentas` se llama `acta_fiscalizacion` y sigue siendo suya) |
| **#9 — los cuatro puertos nuevos hacia `catastro`: puertos y transporte, cero dominio** (los catorce tipos de `kamayuk.rentas.catastro` —`ZonificacionDelPredio`, `RiesgoYItseDelPredio`, `FrentesDelPredio`, `HallazgosDelPredio` y sus diez `record`—, sus cuatro adaptadores HTTP, `NoConstaEnCatastro` en `ClienteHttpDeCatastro`, el contrato comprometido de **9 a 14 operaciones**, y `determinacion_arbitrio` en `TABLAS_PROTEGIDAS` **e** `INMUTABLES`; **3 161 → 3 182** pruebas) | Ocho roturas, cada una **sola** sobre `src/main` y restaurada **por copia comparada con `cmp`**: (1) un `DELETE FROM determinacion_arbitrio` en un repositorio de `nucleo`; (2) el mismo sitio con `UPDATE determinacion_arbitrio SET`; (3) que el adaptador de la zona lea `zonaCodigo` donde el contrato dice `codigo`; (4) que mande `?fecha=` donde ESA operacion lee `aLaFecha`; (5) que pida con `pedir` y no con `pedirHechoDelTerritorio`; (6) una `Dinero multaSugerida` en `HallazgoCatastral`; (7) que una longitud sin unidad se lea como metros lineales en vez de rechazarse; y (8) del lado del proveedor, declarar que se lee un `nivelMaximo` que `catastro` no publica | **1, 1, 1, 1, 1, 2, 1 y 1 en rojo**, cada uno nombrando lo suyo. (1) «no se borra deuda, pagos, recibos, valores, papeletas, asientos ni auditoria: se anula, se da de baja o se reversa (RNF-051): `DELETE FROM determinacion_arbitrio`» y (2) «un asiento no se corrige en el sitio y la auditoria no se edita: se agrega otro registro (ADR-0006, ADR-0008): `UPDATE determinacion_arbitrio SET`» — y el hueco era medible antes de tocar nada: el baseline concede `INSERT, SELECT` sobre esa tabla, **sin `UPDATE` ni `DELETE`**, o sea que la base ya habia decidido lo que el escaner no vigilaba. (3) «expected: "RDM" but was: ""», que es el sintoma MUDO de C-1: `asString("")` sobre un nodo que falta no da error, da una cadena vacia. (4) «expected: `["aLaFecha", "predioId"]` but was: `["fecha", "predioId"]`». (5) «Expecting actual throwable to be an instance of `NoConstaEnCatastro` but was `CatastroInalcanzable`: No se pudo leer la zona del predio 11 al 2026-06-30 (contesto 404)» — y esa es **la decision de diseno de este trabajo**: las cuatro lecturas de C-5 se hicieron para que la ausencia viajara como campo, asi que ahi un 4xx es una averia; las de `catastro`#4 y #5 contestan **422** cuando el predio esta y no tiene poligono y **404** cuando ningun plan lo cubre, y lo hacen a proposito porque un 200 con la zona nula seria indistinguible de «este predio esta en zona nula», que no admite ningun giro. Colapsarlas en «catastro no responde» borraria de este lado justo la distincion que el proveedor construyo, y mandaria a mirar un despliegue cuando lo que falta es cargar un plano o aprobar una ordenanza. (6) **da 2 en rojo y el segundo no existia**: el recorrido por el TIPO caza `-> kamayuk.rentas.dominio.Dinero: no es un hecho del territorio ni un record de la API de este modulo`, y el de los NOMBRES no cazaba nada porque «multa» no estaba en la lista — se anadio, y un campo de texto llamado asi ya no pasa. (7) «Expecting code to raise a throwable»: `catastro` publica `"18.50 ML"` con la unidad dentro, el barrido se determina sobre metros LINEALES y el recojo sobre CUADRADOS, y suponerla no falla: cobra otra cosa. (8) en el CI del PROVEEDOR, «GET /grd/riesgo: falta el campo «nivelMaximo», que el consumidor lee. Este endpoint declara `[aLaFecha, fajasMarginales, hayRiesgoNoMitigable, predioId, zonas]`», con el contrato devuelto despues **byte a byte** (`cmp` vacio). <br><br>**Y el doble de las pruebas se bajo un escalon, porque medir enseno que tapaba codigo de produccion**: `CatastroQueNoContesta` sustituia `pedir`, de modo que lo que `pedir` DECIDE —que un 200 se lee, que un cuerpo ilegible es «catastro no contesta lo que dice contestar» y, desde aqui, que un 4xx con codigo es un hecho— no lo ejercia ninguna prueba. Ahora sustituye `enviar`, que es lo unico que habla por la red; las diecinueve idas y vueltas que ya existian siguen midiendo lo mismo y ademas pasan por esa interpretacion. <br><br>**Cuatro puertos y CINCO operaciones, y hay que decirlo**: el issue decia 13 y el contrato queda en **14**. `RiesgoYItseDelPredio` pide por dos rutas porque `catastro` publica dos —`/grd/riesgo` y `/grd/itse`—, con dos transacciones y dos respuestas; declarar una sola dejaria a la otra sin nadie que comprobara su forma en el CI del proveedor, que es lo unico que este archivo existe para conseguir. Se conserva **un** puerto porque el motivo para preguntarlas es uno: quien evalua una licencia necesita las dos a la vez, y partirlo dejaria que un invocador autorizara sobre media respuesta. <br><br>**Dos cosas quedan declaradas y no cerradas, las dos medidas.** (1) **`catastro` no publica una lectura de hallazgos POR PREDIO**: lo unico que publica es la pagina de una campania, y sus otras seis operaciones abren campania, detectan, verifican, adjuntan evidencia y levantan acta —ninguna es de `rentas`—. Por eso `HallazgosDelPredio.de(predioId)` **lanza** `SinRutaEnCatastro` nombrando la ruta que lo serviria, en vez de faltar: sin el, quien necesitara los hallazgos de un predio recorreria la campania y filtraria aqui, y sobre cuatro mil candidatos eso devuelve lo que cupo en la primera pagina — plausible, incompleto y mudo. (2) **`GET /grd/riesgo` no admite fecha**: resuelve con el reloj de `catastro` y devuelve la que uso, asi que desde aqui no se puede preguntar por el riesgo de un dia pasado. Mandarle un `aLaFecha` que no lee seria el defecto de C-1 al reves, asi que no se manda; cerrarlo es publicar el parametro, y eso es del dueno de `grd`. <br><br>**Cifras, medidas en el mismo entorno** (PostgreSQL 16.13 en el puerto 5433, `./gradlew build --continue --rerun-tasks`, sumando los XML de `test` y `pruebaDeArranque` de los 17 modulos): **3 161 → 3 182**. Las 21 nuevas son 8 idas y vueltas en `LecturaDeCatastroTest`, 5 de parametros en `PeticionesACatastroTest`, 5 de `HechosQueNoConstanTest` y 3 de `PuertosDelTerritorioSinImporteTest`. `verificarArquitectura` y `verificarAislamiento`, **verdes los dos**. <br><br>**Y la linea base traia TRES rojos que no son de este issue, medidos antes de escribir una linea.** Dos son el defecto del *worktree* que `catastro` y `normativa` ya cerraron: `ClonesHermanosDelWorkflowTest` buscaba la raiz con `Files.isDirectory(".git")` y en un worktree ese `.git` es un **archivo**, asi que el recorrido subia hasta `/` y moria con «No se encontro la raiz del clon desde /home/user/w1/rentas/backend/kamayuk-rentas-aplicacion» — no es un rojo que hable de lo que la guarda vigila: es que **no se puede correr**. Se cierra con `Files.exists`, y las dos pasan sobre contenido real. El tercero **no se cierra aqui y se dice con su causa exacta**: `IngestionDeCatastroJdbcTest` muere con «ERROR: value too long for type character varying(300)» sobre `INSERT INTO valuacion_predio`, porque `ProyeccionDeCatastroJdbc` recorta a 400 el motivo de `catastro_evento_muerto` (`recortar(...)`, con su javadoc) y **no recorta el de `valuacion_predio`, cuya columna es `varchar(300)`** (`V5__valuacion_recibida.sql:74`). Es reproducible sobre `origin/beta` sin tocar nada; arreglarlo es decidir entre truncar un motivo —que es el dato que dice por que un predio no se pudo valorizar (#48)— o ampliar la columna con una migracion, y esa decision es del dueno de la ingestion de `catastro` (C-8), no de un PR de cuatro lecturas. |
