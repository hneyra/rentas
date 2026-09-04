# GOB-05 — Inventario del corte

| Campo | Valor |
|---|---|
| Estado | Borrador — **descriptivo, no decisorio** |
| Fecha | 2026-09-03 |
| Rama | `migracion-a-microservicios` |
| Árbol medido | `HEAD` de esta rama; 68 archivos de migración, 17 módulos Gradle, 132 tablas |
| Implementa | [ADR-0029](../30-arquitectura/adr/ADR-0029-cuatro-sistemas-separados.md) §«El orden de extracción», [ADR-0032](../30-arquitectura/adr/ADR-0032-el-esquema-nace-en-baseline.md) §1 («los cuatro baselines se generan una sola vez y por adelantado, junto al inventario del corte») |
| Depende de | ADR-0024 … ADR-0032, todos en estado `Propuesto`, y de **D-22** (¿quién opera cuatro despliegues?), que es la decisión que habilita o cancela todo esto |

Este documento dice **qué se va a dónde**. No mueve nada, no toca código y **no decide lo
dudoso**: cada casilla sin respuesta clara lleva la marca **⚠ DUDOSO** con la pregunta y con
quién la contesta. **Las nueve las contestó la dirección el 2026-09-03**, y están aplicadas a lo
largo del texto con la marca **✅ DECIDIDO**; el resumen de las nueve, con la consecuencia
mecánica que cada una deja sin cubrir, está en §8.3. Lo que sigue abierto es de
[GOB-02](decisiones-abiertas.md) y no lo levantó este inventario. Las etapas posteriores lo consumen; si una de ellas necesita una respuesta
que aquí está marcada, la decisión se toma antes y se anota, no se resuelve por el camino.

## Cómo se hizo, y qué vale de cada cifra

Todo lo que sigue está **medido contra el árbol**, no copiado de un documento previo. Los
comandos están en el cuerpo de cada sección para que se puedan repetir. Donde una fuente
anterior —un ADR, un README, `CLAUDE.md`— dice otra cosa, manda la medida y la divergencia
queda **nombrada** en §8.

Lo que **no** se pudo medir desde aquí está en §8 «Huecos declarados». El más importante:
**qué tienen aplicado hoy las bases de `stg` y `prod`** no se puede saber sin `kubectl` contra
el clúster, así que este inventario describe el **árbol**, no los ambientes.

## Los cuatro destinos, y la convención de nombres

| Repositorio | Sistema | Paquete raíz Java | Namespace k3s |
|---|---|---|---|
| `infrastructure` | La plataforma | — (TypeScript) | el del clúster |
| `rentas` | Rentas (es `sgtm` renombrado, con historial) | `kamayuk.rentas` | `kamayuk-rentas-{stg,prod}` |
| `catastro` | Catastro Fiscal | `kamayuk.catastro` | `kamayuk-catastro-{stg,prod}` |
| `normativa` | Valores normativos | `kamayuk.normativa` | `kamayuk-normativa-{stg,prod}` |
| `caja` | Caja | `kamayuk.caja` | `kamayuk-caja-{stg,prod}` |

**✅ DECIDIDO D-N1 (2026-09-03, dirección del proyecto) — el patrón es
`kamayuk-<sistema>-<contexto>`.** Con él, `sgtm-fiscalizacion` pasa a `kamayuk-rentas-fiscalizacion`
y `sgtm-catastro` a `kamayuk-catastro-catastro`. El sistema va siempre delante, incluso donde hoy
no desambigua nada, porque un módulo que se mude de sistema mañana no tiene que renombrarse a
medias.

**Y hay tres módulos donde sistema y contexto coinciden**, así que el patrón produce un nombre
repetido: `kamayuk-catastro-catastro`, `kamayuk-rentas-rentas` y `kamayuk-caja-caja` —éste último
es la mitad de `sgtm-tesoreria` que se va a la caja (§1.3)—. **No se renombra ninguno**: la
instrucción del corte es no renombrar nada de Java ni de Gradle salvo el paquete raíz, y
rebautizar el contexto `rentas` a `determinacion` para que el módulo suene mejor sería
exactamente eso. Se escribe el nombre repetido y se anota aquí para que nadie lo lea como un
error de copiar y pegar.

**Kamayuk es el nombre del producto, no el del repositorio**, y `SGTM` deja de nombrar un
repositorio para nombrar lo que la municipalidad compra (ADR-0029). Ese cambio de vocabulario
**no está aplicado en ninguna parte del árbol de hoy** y no se aplica aquí.

## Las cifras del corte, de una ojeada

| Qué | Cuánto | Dónde se mide |
|---|---|---|
| Módulos Gradle en `settings.gradle.kts` | **17** | §1 |
| Archivos de migración | **68** (numerados `V1`…`V78`; **diez números nunca se usaron**) | §2 |
| `CREATE TABLE` en total | **132** — 132 nombres distintos, **ningún `DROP TABLE`** en toda la historia | §2 |
| De esos 132, particiones declaradas | **10** (`determinacion`, `cuenta_corriente_asiento`, `auditoria`, `determinacion_predio_detalle`, `determinacion_arbitrio`, ×2 cada una) | §2 |
| Migraciones que insertan datos | **0** | §7 |
| Claves foráneas que cruzarían la frontera | **37** relaciones distintas, en **27** tablas | §2.8 |
| Cruces por SQL entre sistemas futuros | **7** archivos, **20** puntos de lectura | §6 |
| Cruce transaccional que se rompe | **1** (`CobrarDeuda`) | §6 |
| Guiones en `infra/carga-de-datos/` | **16** `.sh` (el enunciado decía 19 — ver §8) | §3, §7 |
| Archivos en `docs/` | **122** `.md` y **36** que no lo son (guiones `.mjs`/`.py`, CSV del corpus, el YAML del contrato) | §5 |
| ADR | **32**, de los cuales **9** en estado `Propuesto` (los del corte) | §4 |

---

# 1. Módulo Gradle → repositorio

Las **17** entradas de `backend/settings.gradle.kts`, en su orden. La columna «Justificación»
cita ARQ-01 ([contextos acotados](../30-arquitectura/contextos-acotados.md)) y
[ADR-0029](../30-arquitectura/adr/ADR-0029-cuatro-sistemas-separados.md).

## 1.1 Los cinco que no son contextos acotados

| # | Módulo | Destino | Justificación |
|---|---|---|---|
| 1 | `sgtm-dominio-compartido` | **librería `comun-dominio`**, consumida por los cuatro | ARQ-01 §4 regla 6: `MunicipalidadId`, `Ejercicio`, `Dinero`, `Alicuota`, `Porcentaje`, `AreaM2`, y no depende de ningún contexto. ADR-0030 §4 lo nombra con ese nombre exacto. **Quién lo publica es D-23** |
| 2 | `sgtm-esquema` | **se disuelve**: un `V1__baseline.sql` por sistema, más `MigradorTest` y `AislamientoMultiTenantTest` replicados en los cuatro | ADR-0032 §1 y §3: las migraciones no se reparten porque `V1` pertenece a dos sistemas y `V6`/`V7` a los cuatro. El detalle tabla a tabla es §2 |
| 3 | `sgtm-plataforma` | **librería `comun-plataforma`** | ADR-0030 §4 la nombra con su contenido: filtro del token, `TenantContext`, el `SET LOCAL`, la guardia del pool, `@RequiereAcceso`, `problem+json`, `CodigoDeError`, `correlacionId`. **Arrastra tres piezas que no son plataforma y salen a un artefacto aparte** (✅ D-N2) |
| 4 | `sgtm-indicadores` | **`rentas`** | El panel de recaudación (ARQ-01 §3.13). Agrega lo que otros publican y no tiene tablas. **Su grafo ya no es el que ARQ-01 describe: son seis contextos, no dos** (✅ D-N3) |
| 5 | `sgtm-aplicacion` | **se parte en cuatro**: cada sistema ensambla el suyo | Es quien monta el artefacto único de ADR-0003, que es justo lo que ADR-0029 reemplaza. Sus **verificaciones** —ArchUnit, escáner de fuentes, contrato de la API— van a `comun-verificaciones` (ADR-0030 §4), **con sus clases de muestra**, que es lo que hace que la regla pueda fallar |

**D-N2 — `sgtm-plataforma` no es sólo plataforma.** Además del camino del token lleva
tres cosas que tienen dueño funcional y que `comun-plataforma` no debería arrastrar a los
cuatro sistemas:

- `pe.gob.sgtm.documentos` — `GeneradorDeDocumentos`, `DocumentoRepositoryJdbc` (escribe
  `documento_emitido`), `RegimenDeLaInstalacionJdbc` (lee `municipalidad.es_demostracion`) y
  `PuntoDeFirma` (el enganche de D-05). Los cuatro sistemas emiten papel, así que la mecánica
  es común; **la tabla `documento_emitido` es de cada uno** (§2.3).
- `pe.gob.sgtm.auditoria` — `AuditoriaJdbc`, que escribe la tabla `auditoria`. Misma forma:
  mecánica común, tabla por sistema.
- `RecorridoPorMunicipalidades` — recorre el registro de municipalidades una a una con su
  `SET LOCAL` por rama (ADR-0020, ADR-0028 §2). Hoy sólo lo usa el portal del ciudadano, que
  se queda con `rentas` (ADR-0030 §1).

**✅ DECIDIDO D-N2 (2026-09-03, dirección del proyecto) — la capa de documentos y la de auditoría
son un artefacto aparte**, no parte de `comun-plataforma`. Es lo que evita que un cambio en el
generador de PDF obligue a subir la versión de la librería que lleva el filtro del token, y al
revés: que endurecer el filtro del token obligue a revalidar la emisión de papel de los cuatro
sistemas.

Lo que va en cada uno, medido sobre `sgtm-plataforma`:

| Artefacto | Qué se lleva |
|---|---|
| `comun-plataforma` | El camino del token entero: filtro, `TenantContext`, el `SET LOCAL`, la guardia del pool, `@RequiereAcceso` y su `ComprobadorDeAcceso`, `problem+json`, `CodigoDeError`, `correlacionId`, `RecorridoPorMunicipalidades` |
| **El artefacto nuevo** | `pe.gob.sgtm.documentos` (`GeneradorDeDocumentos`, `DocumentoRepositoryJdbc`, `RegimenDeLaInstalacionJdbc`, `PuntoDeFirma`) y `pe.gob.sgtm.auditoria` (`AuditoriaJdbc`) |

**Los dos paquetes van juntos y no en dos artefactos**, porque están acoplados por dentro: emitir
un documento audita, y `RegimenDeLaInstalacionJdbc` es lo que decide si el papel sale marcado
como de demostración (`V16`). Partirlos en dos dejaría a uno dependiendo del otro sin ganar nada.

**Y arrastra sus dos tablas, que ya estaban repartidas así** (§2.5): `documento_emitido` y
`auditoria` se replican en los cuatro. El artefacto trae la mecánica; las filas son de cada
sistema. *Cómo se llame y quién lo publica sigue siendo **D-23**.*

**D-N3 — `sgtm-indicadores` ya no ve dos contextos, ve seis.** ARQ-01 §2 y §3.13 dicen
que su `build.gradle.kts` «declara que solo ve esos dos» —`cuentacorriente` y `tesoreria`— y
que «añadir un tercero al panel cuesta una línea y se ve en el diff». Medido hoy:

```
$ grep -oE 'implementation\(project\(":sgtm-[a-z]+"\)\)' sgtm-indicadores/build.gradle.kts
coactiva  cuentacorriente  rentas  sanciones  tesoreria  valores
```

Son **seis**, no dos. La línea se añadió cuatro veces y ARQ-01 no se actualizó.

**✅ CONFIRMADO D-N3 (2026-09-03, dirección del proyecto) — son seis, y ARQ-01 §2 y §3.13 hay que
corregirlos.** Dicen hoy algo falso, y lo que dicen es justamente la garantía que el módulo
existía para dar: «añadir un tercero al panel cuesta una línea y se ve en el diff». La línea se
vio cuatro veces y nadie la leyó, así que **la garantía era la revisión y no el build** — y eso
es lo que hay que arreglar al corregirlo, no sólo el número.

Para el corte importa por dos cosas:

1. **`tesoreria` se parte**, y la mitad `caja` se lleva `AvanceDeCaja` (§1.3). El panel de inicio
   de `rentas` pasa a **depender de que `caja` conteste**, y hoy no lo dice ninguna pantalla.
2. **El invariante de ARQ-01 §3.13 —«ninguna cifra del panel se calcula en el panel»— se vuelve
   más difícil de sostener, no menos**: con `AvanceDeCaja` al otro lado de una frontera HTTP, la
   tentación de recomponer la cifra localmente cuando la llamada falla es exactamente el defecto
   que ese invariante existe para impedir. La regla `EL_PANEL_NO_HABLA_CON_LA_BASE` sigue
   valiendo; lo que no cubre es «el panel suma lo que le llegó a medias».

## 1.2 Los doce contextos acotados

| # | Módulo | Destino | Módulo destino | Justificación |
|---|---|---|---|---|
| 6 | `sgtm-contribuyentes` | **`rentas`** | `kamayuk-contribuyentes` | ADR-0029 §Lo descartado lo dice por escrito: sacarlo como quinto sistema es tentador y se descarta porque hoy es la base del grafo, no depende de nadie, y sacarlo multiplica por cuatro las llamadas de cada pantalla. **Se revisa cuando `caja` cobre a quien no es contribuyente (D-17)** |
| 7 | `sgtm-catastro` | **`catastro`** | `kamayuk-catastro` | ADR-0029: predio, ficha versionada, construcciones, titularidad, geometría, catálogo vial. **Con el `arancel`**, cuya fuente es cartográfica; las **tres** tablas de valuación nacionales van a `normativa` (✅ D-N4) |
| 8 | `sgtm-rentas` | **`rentas`** | `kamayuk-rentas` | ARQ-01 §3.3: es el único contexto que decide **cuánto se debe**. Es la fase 2 de ADR-0024 |
| 9 | `sgtm-parametros` | **`normativa`** | `kamayuk-normativa` | ADR-0025: ediciones, conjuntos sellados y el catálogo de reglas. Se parte en dos artefactos: el **servicio** (datos) y `normativa-reglas` (el motor, `ReglaTributaria`, `ReglaDeAgregacion`, `PoliticasDeRedondeoSelladas`), que es una librería que fijan `catastro` y `rentas` por versión |
| 10 | `sgtm-fiscalizacion` | **`rentas`** | `kamayuk-fiscalizacion` | ARQ-01 §3.5. Es la frontera más delicada y la que produce el cruce más caro (§6.1). ADR-0027 §4 la conserva entera: la determinación de oficio sigue siendo la única vía de vuelta al ejercicio en curso |
| 11 | `sgtm-sanciones` | **`rentas`** | `kamayuk-sanciones` | ARQ-01 §3.6. Papeletas de tránsito y administrativas, un solo modelo con dos catálogos. Asienta cargos en el libro, que se queda en `rentas` |
| 12 | `sgtm-cuentacorriente` | **`rentas`** | `kamayuk-cuentacorriente` | ADR-0026 §2: **la imputación es de rentas**. El libro es donde se aplica el orden del Código Tributario, y si `caja` imputara, la regla estaría escrita dos veces |
| 13 | `sgtm-tesoreria` | **SE PARTE** | dos módulos | §1.3, clase por clase |
| 14 | `sgtm-valores` | **`rentas`** | `kamayuk-valores` | ARQ-01 §3.9: orden de pago, resolución de determinación y resolución de multa. Es la deuda formalizada, no el cobro |
| 15 | `sgtm-coactiva` | **`rentas`** | `kamayuk-coactiva` | ARQ-01 §3.10. Su fraccionamiento coactivo pide condiciones al convenio, que también se queda (ADR-0026 §5) |
| 16 | `sgtm-licencias` | **`rentas`** | `kamayuk-licencias` | ARQ-01 §3.11. Genera deuda pidiéndosela a `cuentacorriente`. **Consume `catastro.LectorDeFichasEconomicas`**, que pasa a ser llamada HTTP (§6.5) |
| 17 | `sgtm-seguridad` | **los cuatro** | `kamayuk-<sistema>-seguridad` | ✅ D-N5: usuarios, grupos y permisos se definen en Keycloak y **cada sistema guarda su copia local**, así que su guardia resuelve sin llamar a nadie. La pantalla que los administra sigue en `rentas` (ADR-0030 §3: «los cuatro frontends leen `rentas/api/v1/sesion/permisos`») |

**✅ DECIDIDO D-N4 (2026-09-03, dirección del proyecto), y **revisado el mismo día**: las **tres**
tablas de valuación nacionales van a `normativa`; **`arancel` se queda en `catastro`**.

La primera decisión mandaba las cuatro a `normativa`. Se revisó al ver lo que el arancel es:
**su fuente es cartográfica**. `docs/10-negocio/valores-normativos/aranceles-2026.md` §1.3
documenta que el arancel de cada vía no viene en tabla de texto sino **anotado sobre un plano
gráfico**, y que la forma correcta de traerlo es importarlo «desde el sistema GIS»;
`scripts/valores-normativos/importar_arancel_via_gpkg.py` es esa importación, y el caso de uso
que la carga ya se llama `pe.gob.sgtm.catastro.aplicacion.ImportarArancel`. Una tabla que se
llavea por `via_id`, cuya fuente es un GeoPackage y cuyo importador es de catastro, es de
catastro.

| Tabla | Dónde | Por qué |
|---|---|---|
| `valor_unitario_edificacion`, `depreciacion`, `valor_referencial_vehiculo` | **`normativa`** | **Nacionales** desde `V55` (ADR-0017): una copia para todo el país, con `CHECK (municipalidad_id IS NULL)` |
| `arancel` | **`catastro`** | **Municipal** y cartográfico: se carga por vía desde el plano del MEF, y `via` es de catastro |

Cada sistema se queda una **copia local** de lo que necesita de `normativa`, por el mecanismo de
snapshot sellado de ADR-0025 §1: se resuelve el `conjuntoId` una vez al abrir la corrida, se
descarga, se verifica su `sha256` y se cachea en tabla local para siempre —lo sellado no cambia
(`V9`), así que no hay invalidación que diseñar ni TTL que ajustar—.

**Y la revisión mejora el resultado, medido:** con `arancel` en `catastro`, su clave foránea a
`via` **deja de cruzar la frontera y se crea de verdad**, y el baseline de `normativa` pasa a
tener el **diff completamente vacío**. Lo que aparece en su lugar es `arancel → conjunto_parametros`,
que sí cruza —a `normativa`— y es la referencia al conjunto sellado con que se publicó: la misma
que ya llevan `determinacion`, `prescripcion` y las demás, y que ADR-0025 §3 conserva como dato
guardado en vez de como restricción.

**✅ DECIDIDO D-N5 (2026-09-03, dirección del proyecto) — usuarios, grupos y permisos se definen
en Keycloak; cada sistema guarda una copia local en tabla y su guardia la consulta.** Con eso
`seguridad` deja de ir sólo a `rentas` (§2.6) y **D-19** queda contestada: el
`ComprobadorDeAcceso` de cada sistema pregunta a su propia tabla, no a otro sistema por HTTP.

**Esto es un cambio de dirección, no una descripción de lo que hay.** Medido:

- `despliegue/identidad/realm-sgtm.json` declara **sólo `clients`**: ni un `groups`, ni un
  `roles`. Keycloak hoy no sabe nada de permisos.
- Lo que sí declara es **un grupo por municipalidad** (`"grupo": "200105 - Municipalidad
  Distrital de Catacaos"` en `municipalidades/200105.json`) y usuarios con una marca
  `administrador: true`. Es agrupación **por tenant**, no por tarea.
- Los grupos de tarea, los miembros y los permisos viven **sólo en la base**: los crea
  `ImplantarMunicipalidad` —dos grupos, administración y seguridad— y los administran las nueve
  escrituras de `sgtm-seguridad` (`POST /grupos`, `/grupos/{g}/miembros`, `/usuarios`, sus bajas
  y reactivaciones, y las dos vigencias).

**Y lo que no cambia, que es lo que hay que decir en voz alta: los permisos siguen sin viajar en
el token.** ADR-0013 lo descartó midiéndolo —134 opciones × 7 privilegios no caben, y un cambio
de permiso obligaría a renovar sesión en los cuatro— y ADR-0030 §3 lo conserva. «Definirse en
Keycloak» es que Keycloak sea el **registro maestro**; la copia local en tabla es exactamente lo
que preserva ADR-0013, porque la comprobación sigue siendo del servidor que sirve la operación.

**Dos cosas no son de Keycloak y no se copian de él**, y conviene separarlas ahora para que la
sincronización no acabe arrastrándolas:

| Tabla | De quién es |
|---|---|
| `modulo_sistema`, `acceso` | **Del producto**: es el catálogo de opciones y módulos, lo siembra `SembradorDeAccesos` con el código, y cada sistema siembra **su parte** — que es lo que hace la copia pequeña en vez del catálogo entero |
| `sesion` | **De cada sistema**: es su bitácora de sesión, y lleva `ejercicio_trabajo`, el filtro de vista con su privilegio `ESPECIAL` sobre `cambiar_anio` |

**Lo que la decisión no fija y hay que fijar al ejecutarla:** cómo se sincroniza la copia y qué
pasa mientras está desactualizada. Es literalmente lo que **D-19** enunciaba —«caché con TTL
corto y evento de revocación, replicación del catálogo, o un servicio de autorización con
presupuesto de latencia declarado»— y la decisión elige la segunda familia sin fijar el
mecanismo. *El detalle lo escribe la fase 1.*

## 1.3 `sgtm-tesoreria` se parte: 84 clases a `caja`, 33 a `rentas`

El criterio lo fija [ADR-0026](../30-arquitectura/adr/ADR-0026-el-camino-del-dinero.md) §5 y no
es negociable: **recibo, movimiento, turno, cierre y medios de pago van a `caja`; el convenio de
fraccionamiento y el fraccionamiento coactivo se quedan en `rentas`, porque un convenio es deuda
reprogramada** —tiene interés, tiene quiebre y tiene consecuencias coactivas—. Si viaja a Caja,
Caja adquiere reglas tributarias y deja de ser reutilizable para cobrar un puesto de mercado.

Medido: `sgtm-tesoreria` tiene **122 archivos** `.java`, de los cuales **5** son `package-info`
(uno por paquete, se duplican en los dos lados) y **117** son clases.

### A `rentas` — el convenio y el fraccionamiento coactivo (33)

| Paquete | Clases |
|---|---|
| raíz (API pública del contexto) | `ConvenioCoactivo`, `ConvenioDelContribuyente`, `ConveniosDelContribuyente`, `CuotaDelConvenio`, `FraccionamientoCoactivo`, `SolicitudDeConvenioCoactivo` |
| `aplicacion` | `CerrarConvenio`, `CondicionesParametrizadas`, `ConsultaDeConvenios`, `ConveniosDelContribuyenteTesoreria`, `FormalizarConvenio`, `FraccionamientoCoactivoTesoreria`, `RegistrarPreconvenio` |
| `dominio` | `CondicionesDelConvenio`, `Convenio`, `ConvenioEnConsulta`, `ConvenioRepository`, `CriterioDeConvenios`, `Cronograma`, `CuotaDeConvenio`, `EstadoDeConvenio`, `MovimientoDeConvenio`, `MovimientoDeConvenioRepository`, `NumeroDeConvenio`, `TipoDeConvenio`, `TipoDeGarantia`, `TipoDeMovimientoDeConvenio` |
| `infraestructura` | `ConvenioRepositoryJdbc`, `MovimientoDeConvenioRepositoryJdbc` |
| `infraestructura.web` | `ConvenioController`, `ConvenioResource`, `PeticionDeCierreDeConvenio`, `PeticionDeFraccionamiento` |

Tablas que se van con ellas: `convenio`, `convenio_cuota`, `convenio_deuda`,
`convenio_movimiento`, `convenio_correlativo` (§2).

### A `caja` — recibo, ventanilla, turno, cierre, arqueo, tasas y recaudación (84)

| Paquete | Clases |
|---|---|
| raíz (API pública) | `AvanceDeCaja`, `CobrosDeTasas`, `RecaudacionDeTasa`, `RecaudadoEnCaja`, `ReciboDeTramite`, `RecibosDeTramite`, `TasaCobrada` |
| `aplicacion` | `AbrirCaja`, `AnularRecibo`, `ArqueoDeTurno`, `AvanceDeCajaTesoreria`, `CargarCajas`, `CerrarTurno`, `CobrarTasa`, `CobrosDeTasasTesoreria`, `ConsultaDeCajas`, `ConsultaDeRecaudacion`, `ConsultaDeRecibos`, `DatosDeCargaCajas`, `DuplicadoDeRecibo`, `ImportarCajas`, `ModeloDelRecibo`, `RecibosDeTramiteTesoreria`, `RegistrarCaja`, **`CobrarDeuda` ⚠** |
| `dominio` | `Area`, `AreaRepository`, `ArqueoDelTurno`, `Caja`, `CajaEnConsulta`, `CajaRepository`, `CierreDeTurno`, `CierreDeTurnoRepository`, `CriterioDeRecaudacion`, `CriterioDeRecibos`, `EstadoDeRecibo`, `EstadoDeTurno`, `FormaDePago`, `LineaDeArqueo`, `LineaDeRecibo`, `LineaDeTasaPedida`, `MovimientoDeRecibo`, `MovimientoDeReciboRepository`, `NumeroDeRecibo`, `RecaudacionDePartida`, `RecaudacionDeTributo`, `RecaudacionRepository`, `Recibo`, `ReciboDelTurno`, `ReciboEnConsulta`, `ReciboRepository`, `Tasa`, `TasaRepository`, `TipoDeMovimientoDeRecibo`, `TipoDeMovimientoDeTurno`, `TurnoDeCaja`, `TurnoDeCajaRepository`, **`TipoDePago` ⚠** |
| `infraestructura` | `AreaRepositoryJdbc`, `CajaRepositoryJdbc`, `CierreDeTurnoRepositoryJdbc`, `MovimientoDeReciboRepositoryJdbc`, `RecaudacionRepositoryJdbc`, `ReciboRepositoryJdbc`, `TasaRepositoryJdbc`, `TurnoDeCajaRepositoryJdbc`, `UsuarioDeLaSesion` |
| `infraestructura.web` | `AnulacionResource`, `ArqueoResource`, `CajaController`, `CajaEnListaResource`, `CatalogoDeCajasController`, `CierreController`, `CierreResource`, `DuplicadoResource`, `PeticionDeAnulacion`, `PeticionDeCierre`, `PeticionDeCobranza`, `PeticionDeCobroDeTasas`, `RecaudacionController`, `RecaudacionResource`, `ReciboController`, `ReciboEnListaResource`, `ReciboResource` |

Tablas que se van con ellas: `area`, `tasa`, `caja`, `recibo`, `recibo_detalle`,
`recibo_correlativo`, `recibo_movimiento`, `cierre_caja`, `cierre_turno`,
`cierre_turno_detalle` (§2).

### Las dos clases marcadas ⚠, que son las que este corte parte por dentro

**`CobrarDeuda`** es el punto exacto donde ADR-0026 §3 convierte un `COMMIT` en dos. Medido —sus
campos inyectados y sus imports— hace **tres cosas ajenas entre sí en una transacción**:

```
sgtm-tesoreria/…/aplicacion/CobrarDeuda.java:82-87
    private final AbrirCaja abrirCaja;              // caja
    private final RegistroDeAbonos abonos;          // rentas  (cuentacorriente)
    private final ReciboRepository recibos;         // caja
    private final FormalizarConvenio formalizar;    // rentas  (convenio)
    private final Auditoria auditoria;              // común
```

Y sus imports cruzan las dos fronteras a la vez: `pe.gob.sgtm.cuentacorriente.RegistroDeAbonos`,
`…SeleccionDeObligacion`, `…AbonoAsentado`, `…TributoDelLibro` (rentas), y
`…tesoreria.dominio.NumeroDeConvenio` (rentas, tras la partición). **La clase no se mueve entera
a ningún lado: se parte**, y el trozo de `rentas` es el consumidor de `PagoRegistrado`. Está en
§6.2 con su salida.

**`TipoDePago`** declara `CUOTA_CONVENIO` entre sus valores, es decir, el enumerado de la caja
nombra un concepto de rentas. Hoy no hace daño porque D-14 mantiene ese tipo **rechazado** —la
caja no admite pagos parciales, y por eso el quiebre del convenio nunca tiene que repartir nada—.
Al partir, la caja tiene que poder cobrar la cuota de un convenio **sin saber qué es un
convenio**: ADR-0026 §5 dice que lo hace «como cualquier otra orden». *Es trabajo de la fase 3;
aquí sólo queda registrado que el enumerado no viaja tal cual.*

---

# 2. Tabla del esquema → repositorio

## 2.1 Cómo se contó, y qué sale

```bash
cd backend/sgtm-esquema/src/main/resources/db/migration
ls | wc -l                                          # 68 archivos
grep -c '^CREATE TABLE' *.sql | ...                 # 132 sentencias
grep -rhoiE 'CREATE TABLE( IF NOT EXISTS)? [a-z_0-9]+' . | sort -u | wc -l   # 132 nombres, ninguno repetido
grep -rin 'DROP TABLE' .                            # (vacío)
grep -rinE '^\s*INSERT INTO' .                      # (vacío)
```

- **68 archivos**, numerados `V1` … `V78`. **Diez números nunca se usaron**: `V36`, `V38`,
  `V40`, `V42`, `V44`, `V46`, `V48`, `V50`, `V52` y `V63`. Se reservaron en ramas que no se
  integraron; **no hay ningún archivo perdido**, y conviene decirlo porque un baseline generado
  «de V1 a V78» buscaría diez que no existen.
- **132 `CREATE TABLE`**, **132 nombres distintos**, **ningún `DROP TABLE`** en toda la historia
  del esquema. Cada tabla que se creó una vez sigue existiendo.
- De las 132, **10 son particiones declaradas** (`determinacion`, `cuenta_corriente_asiento`,
  `auditoria`, `determinacion_predio_detalle` y `determinacion_arbitrio`, con `_2026` y `_2027`
  cada una). Se cuentan aparte porque el baseline las tiene que recrear con su bloque de RLS
  explícita y **sin concederles ningún privilegio** (`V6`, y la regla de `CLAUDE.md`).
- **Ninguna migración inserta una sola fila.** El esquema es DDL puro; los únicos datos que
  existen los ponen la implantación y los guiones de `carga-de-datos/` (§7).

Reparto: **15 a `catastro`, 6 a `normativa`, 10 a `caja`, 88 a `rentas` y 13 replicadas en los
cuatro** — 6 transversales (§2.5) más las 7 de seguridad, que con ✅ D-N5 también se replican
(§2.6).

## 2.2 A `catastro` — 15

| Tabla | Migración | Nota |
|---|---|---|
| `via` | `V1` | Catálogo vial. `V66` le añade `nombre_busqueda` generada, para que el prefijo llegue al índice bajo RLS |
| `sector` | `V1` | Lleva `zona`, texto libre por municipalidad — ver §6.6 |
| `manzana` | `V1` | Sin columna de estado, a propósito: su código es un tramo del código catastral de sus predios |
| `predio` | `V1` | `V61` le da `geometria` (PostGIS); `V65`, las cuatro columnas generadas del marco |
| `ficha_catastral` | `V1` | Se versiona, nunca se sobrescribe. `V72` le pone la restricción de vigencias que no se pisan |
| `construccion` | `V1` | `V53` le da su índice por ficha; `V58`, la categoría hasta la `J` |
| `otra_instalacion` | `V1` | |
| `titularidad` | `V1` | `V69` le da el índice por predio y fecha; `V72`, la exclusión de vigencias |
| `inquilino` | `V1` | **FK a `contribuyente`** (§2.4) |
| `actividad_economica` | `V13` | Ficha económica |
| `bien_comun` | `V13` | |
| `participacion_comun` | `V13` | |
| `tierra_rural` | `V13` | |
| `colindante_rural` | `V13` | |
| `arancel` | `V1` | **Municipal**, y con FK a `via`. Su fuente es un **plano gráfico** del MEF: llega en GeoPackage y lo importa `catastro.aplicacion.ImportarArancel` (✅ D-N4). `V18` lo cuelga de un conjunto sellado; `V25` le da el único parcial para el tramo nulo |

**Lo que `catastro` NO se lleva y podría parecer suyo:** `declaracion_jurada` es de `rentas`
—es el acto del contribuyente, y ARQ-01 §3.5 lo dice por escrito—, y las tres tablas de
valuación **nacionales** son de `normativa` (✅ D-N4); el `arancel`, en cambio, se queda aquí.

**Y una tabla que `catastro` va a necesitar y hoy no existe:** `valuacion_predio` /
`corrida_de_valuacion` ([ADR-0027](../30-arquitectura/adr/ADR-0027-la-valuacion-es-un-hecho-sellado.md)
§1 y §2). No están en el esquema: **el sistema no valoriza todavía** (D-11, GOB-03 H-14). El
baseline de `catastro` nace sin ellas y las crea su `V2`.

## 2.3 A `normativa` — 6

| Tabla | Migración | Nota |
|---|---|---|
| `parametro_tributario` | `V1` | La cifra con su doble firma (ADR-0007). `V55` le cuelga las tres de valuación |
| `conjunto_parametros` | `V1` | `V9` lo vuelve inmutable al sellarse; `V10` admite más de uno por ejercicio |
| `conjunto_parametro_detalle` | `V1` | La bisagra: lo que un conjunto sellado contiene |
| `valor_unitario_edificacion` | `V1` | **Nacional** desde `V55` (ADR-0017). `V59` la deja en tres partidas |
| `depreciacion` | `V1` | **Nacional** desde `V55`; `V57` le añade el uso a la clave (492 filas del Anexo I) |
| `valor_referencial_vehiculo` | `V2` | **Nacional** desde `V55`. **La lee `rentas`, no `catastro`** |


## 2.4 A `caja` — 10

| Tabla | Migración | Nota |
|---|---|---|
| `area` | `V3` | El área generadora de la recaudación. **No es un `area_m2`** (#607) |
| `tasa` | `V3` | El concepto del TUPA. **Nada en producción la escribe todavía** (#430): sus cifras son D-02b |
| `caja` | `V3` | La ventanilla. `V29` le da la serie única por municipalidad |
| `recibo` | `V3` | En `TABLAS_PROTEGIDAS` e inmutable; `V29` le retira el `UPDATE` |
| `recibo_detalle` | `V3` | **FK a `tasa`** (queda dentro de `caja`) |
| `cierre_caja` | `V3` | El turno. **Su `REVOKE UPDATE` no se pudo hacer** (DAT-01 §6): `SELECT … FOR UPDATE` lo exige, y ahí se serializa la ventanilla. ADR-0026 §Consecuencias dice que se replantea en el sistema nuevo, donde el cierre ya no comparte base con el libro |
| `recibo_correlativo` | `V29` | |
| `recibo_movimiento` | `V30` | Anulación y duplicado |
| `cierre_turno` | `V32` | |
| `cierre_turno_detalle` | `V32` | |

## 2.5 Transversales — 6, replicadas en los cuatro baselines

No se reparten: **cada sistema necesita las suyas**, con el mismo DDL y datos independientes.

| Tabla | Migración | Por qué se replica |
|---|---|---|
| `municipalidad` | `V1` | Es el **registro de tenants**, no una tabla de tenant: `V6` le da a propósito una política `FOR SELECT USING (true)`, porque los procesos masivos la recorren entera (#555, #415). Los cuatro sistemas la necesitan para su `SET LOCAL` y para el recorrido de ADR-0028 §2. **El identificador lo fija Keycloak y las cuatro bases lo reciben** (✅ D-N6) |
| `documento_emitido` | `V15` | Los cuatro emiten papel con su correlativo y su `sha256`. La **mecánica** es común y va en el artefacto aparte de ✅ D-N2; las filas son de cada uno |
| `auditoria` + `auditoria_2026` + `auditoria_2027` | `V5` | Regla 10: toda modificación exige observación del usuario, en los cuatro. Particionada por ejercicio; **sólo declara 2026 y 2027** |
| `respaldo` | `V8`, `V78` | **Una copia es del clúster, no de una municipalidad** — #558 lo midió: darle `municipalidad_id` deja la tabla vacía para todas. ✅ D-N7: **una tabla por base**, cuatro filas para el mismo respaldo físico |

**✅ DECIDIDO D-N6 (2026-09-03, dirección del proyecto) — el dueño del identificador de
municipalidad es Keycloak; la municipalidad *es* el tenant.** Y no hay que migrar ninguna: **las
municipalidades se crean todas de nuevo**, que es la misma ventana de ADR-0032 §3 aplicada al
registro de tenants.

**Esto invierte la dirección que el sistema tiene hoy, y el DDL lo dice.** Medido en `V1:35-42`:

```sql
CREATE TABLE municipalidad (
    id     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ubigeo char(6) NOT NULL UNIQUE,
    …
```

Hoy **lo genera PostgreSQL** y Keycloak lo copia: `RegistroDeMunicipalidadesJdbc:80-93` hace el
`INSERT` y devuelve el `id`, y el cruce a tres bandas de ADR-0012 / #415 comprueba que el
`municipalidadId` del archivo declarativo coincide con `SELECT id FROM municipalidad`. Con la
decisión tomada, **el registro pasa a ser Keycloak y las cuatro bases lo reciben**:

- `id` **no puede seguir siendo `GENERATED ALWAYS AS IDENTITY`** en ninguno de los cuatro
  baselines: pasa a ser un valor que se acepta al implantar. Es un cambio de DDL, y es barato
  ahora precisamente porque no hay ninguna municipalidad que conservar.
- El cruce a tres bandas **cambia de sentido**: hoy verifica que el archivo cuadra con la base;
  pasa a verificar que las cuatro bases cuadran con Keycloak. La comprobación no se pierde, se
  invierte — y sigue siendo lo que impide que un dígito equivocado siembre en el tenant vecino.
- `ubigeo char(6) UNIQUE` ya está y es la clave natural estable; sigue siendo el enlace legible
  entre el realm, los archivos de `despliegue/identidad/` y las cuatro bases.

**Lo que la decisión no fija y hay que fijar al ejecutarla: la forma del identificador.**
`municipalidad_id` es `bigint` en las 78 claves foráneas que apuntan a `municipalidad` y en todas
las políticas de RLS (`current_setting('app.municipalidad_id')::bigint`). Si Keycloak lleva ese
número, no cambia nada más; si llevara un UUID, cambia el tipo en todo el esquema de los cuatro
sistemas. *No es una decisión abierta: es el detalle que la fase 0 escribe.*

**✅ DECIDIDO D-N7 (2026-09-03, dirección del proyecto) — cada sistema guarda la información de
sus respaldos en su tabla propia.** `respaldo` se replica en los cuatro, como las demás
transversales, y cada pantalla contesta «¿cuándo se respaldó lo mío?» sin llamar a nadie.

**Y hay un hecho medido que la decisión tiene que llevar dentro: hay un solo respaldo físico y
cuatro filas que lo registran.** `Respaldo.ts:137-138` lo dice y está confirmado contra un
clúster real (#158):

> `pg_backup_start`/`stop` son del cluster entero, no de una base

`backup-push` corre con `PGDATABASE=postgres` explícito y wal-g archiva el WAL del clúster, así
que **una copia cubre las cuatro bases a la vez**. Dos consecuencias que hay que resolver al
ejecutarla, y ninguna invalida la decisión:

1. **El `CronJob` de `infrastructure` escribe cuatro filas, no una** — hoy hace
   `INSERT INTO respaldo … RETURNING id` y luego el `UPDATE` a `EXITOSO`/`FALLIDO`
   (`Respaldo.ts:122, 145, 152`). Pasa a repetirlo por base, y **la fila describe el mismo evento
   desde cuatro sitios**: si las cuatro no cuadran, el desacuerdo es del registro, no del
   respaldo.
2. **La restauración verificada de `V78` se verifica una vez y se anota cuatro.** Sus tres
   `CHECK` (`ultima_restauracion_verificada` sólo sobre un respaldo `EXITOSO`, y con su
   responsable) siguen valiendo por fila; lo que no se puede es que una base diga «verificada» y
   otra no, porque el simulacro restauró el mismo clúster.

## 2.6 Seguridad — 7, replicadas en los cuatro (✅ D-N5)

`modulo_sistema`, `acceso`, `grupo`, `usuario`, `miembro`, `permiso`, `sesion` (todas `V5`).

Con ✅ D-N5 **las siete se replican**, como las transversales de §2.5, y cada una por un motivo
distinto:

| Tabla | Qué guarda cada sistema | De dónde viene |
|---|---|---|
| `usuario`, `grupo`, `miembro`, `permiso` | **Una copia** de lo que Keycloak define | Sincronizada desde Keycloak, que es el registro maestro |
| `modulo_sistema`, `acceso` | **Su parte** del catálogo: las opciones y módulos que él sirve | La siembra `SembradorDeAccesos` con el código del propio sistema |
| `sesion` | La suya, con su `ejercicio_trabajo` y su bitácora de accesos | Propia; no se sincroniza con nada |

**Ninguna llamada HTTP en el camino del guardia**: `@RequiereAcceso` de cada sistema resuelve
contra su tabla local, que es lo que hace que un `403` no dependa de que otro sistema esté
arriba. Es la misma propiedad que ADR-0025 §1 compra para el cálculo con el snapshot sellado, y
por el mismo motivo: lo que está en el camino caliente no puede colgar de otro despliegue.

**Lo que se pierde y hay que ver**: hoy el reparto de permisos se administra por pantalla y esas
nueve escrituras (`POST /grupos`, `/grupos/{g}/miembros`, `/usuarios`, sus bajas, reactivaciones
y vigencias) escriben en la tabla que el guardia lee **en la misma transacción**. Con Keycloak
como registro maestro, esas pantallas escriben en Keycloak y la tabla llega después: **aparece
una ventana en la que quien acaba de recibir un permiso todavía no lo tiene**. Es del mismo tipo
que la de ADR-0026 §3 en el camino del dinero, y como aquélla necesita ser visible en vez de
silenciosa.

## 2.7 A `rentas` — 88

Todo lo demás. Ordenadas por nombre, con la migración que las creó:

`acta_fiscalizacion` (V4), `acto_coactivo` (V3), `anuncio` (V4), `anuncio_correlativo` (V45),
`anuncio_movimiento` (V45), `beneficio` (V2), `certificado` (V51), `certificado_correlativo`
(V51), `ciiu` (V4), `codigo_infraccion` (V4), `constancia_libre` (V47), `contacto` (V1),
`contribuyente` (V1), `convenio` (V3), `convenio_correlativo` (V31), `convenio_cuota` (V3),
`convenio_deuda` (V31), `convenio_movimiento` (V31), `corrida_predial` (V62),
`corrida_predial_observado` (V62), `costa_obligacion` (V35), `costa_procesal` (V3),
`cuenta_corriente_asiento` (V2), `cuenta_corriente_asiento_2026` (V2),
`cuenta_corriente_asiento_2027` (V2), `declaracion_jurada` (V2), `descargo` (V4),
`determinacion` (V2), `determinacion_2026` (V2), `determinacion_2027` (V2),
`determinacion_arbitrio` (V23), `determinacion_arbitrio_2026` (V23),
`determinacion_arbitrio_2027` (V23), `determinacion_predio_detalle` (V20),
`determinacion_predio_detalle_2026` (V20), `determinacion_predio_detalle_2027` (V20),
`dj_correlativo` (V54), `domicilio` (V1), `edificacion_correlativo` (V43),
`edificacion_estructura` (V43), `edificacion_movimiento` (V43), `edificacion_profesional` (V43),
`edificacion_proyecto` (V43), `edificacion_requisito` (V43), `edificacion_terreno` (V43),
`edificacion_vigencia` (V43), `espectaculo` (V2), `expediente_coactivo` (V3),
`expediente_correlativo` (V33), `expediente_movimiento` (V33), `expediente_valor` (V3),
`internamiento` (V4), `internamiento_movimiento` (V41), `licencia_correlativo` (V37),
`licencia_duplicado` (V4), `licencia_edificacion` (V4), `licencia_funcionamiento` (V4),
`licencia_giro` (V4), `licencia_movimiento` (V37), `liquidacion_correlativo` (V39),
`liquidacion_costas` (V35), `liquidacion_costas_correlativo` (V35), `liquidacion_detalle` (V39),
`liquidacion_fiscalizacion` (V39), `liquidacion_movimiento` (V39), `notificacion` (V3),
`notificacion_administrativa` (V4), `papeleta` (V4), `papeleta_cambio_numero` (V4),
`papeleta_masivo` (V47), `papeleta_masivo_item` (V47), `prescripcion` (V28),
`prescripcion_ejercicio` (V28), `prescripcion_hecho` (V28), `programa_fiscalizacion` (V4),
`programa_muestra` (V60), `resolucion_determinacion` (V49), `resolucion_gerencia` (V41),
`responsable_solidario` (V12), `saldo_proyectado` (V2), `transferencia` (V2), `valor` (V3),
`valor_correlativo` (V26), `valor_detalle` (V3), `valor_masivo` (V27), `valor_masivo_item` (V27),
`valor_movimiento` (V28), `vehiculo` (V2).

## 2.8 Las claves foráneas que cruzan la frontera — 37 relaciones, 27 tablas

Con ✅ D-N4 la de `arancel → via` **deja de cruzar** —las dos tablas quedan en `catastro`— y en
su lugar cruza `arancel → conjunto_parametros`, hacia `normativa`. El total no cambia: 37. Y
ninguna se elimina: se convierten en proyección, en llamada o en invariante, según el bloque.

Éstas son las que [ADR-0027](../30-arquitectura/adr/ADR-0027-la-valuacion-es-un-hecho-sellado.md)
convierte en proyección y las que **D-18** tiene abiertas. Medido recorriendo `CREATE TABLE` y
`ALTER TABLE … REFERENCES` de las 68 migraciones y cruzándolo con el reparto de §2.2–§2.7.

### `rentas` → `catastro` (19 relaciones, 17 tablas). La más cara

| Tabla en `rentas` | Referencia | Migración |
|---|---|---|
| `declaracion_jurada` | `predio` | `V2` |
| `declaracion_jurada` | `ficha_catastral` | `V19` |
| `determinacion` | `predio` | `V2` |
| `determinacion_predio_detalle` | `predio` | `V20` |
| `determinacion_arbitrio` | `predio` | `V23` |
| `beneficio` | `predio` | `V2` |
| `transferencia` | `predio` | `V2` |
| `acta_fiscalizacion` | `predio` | `V4` |
| `acta_fiscalizacion` | `ficha_catastral` | `V24` |
| `liquidacion_detalle` | `predio` | `V39` |
| `resolucion_determinacion` | `predio` | `V49` |
| `resolucion_determinacion` | `ficha_catastral` | `V49` |
| `programa_muestra` | `predio` | `V60` |
| `notificacion_administrativa` | `predio` | `V4` |
| `anuncio` | `predio` | `V4` |
| `licencia_funcionamiento` | `predio` | `V4` |
| `licencia_funcionamiento` | `ficha_catastral` | `V37` |
| `licencia_edificacion` | `predio` | `V4` |
| `certificado` | `predio` | `V51` |

**Y un hallazgo que corrige a ADR-0029 y a D-18.** Los dos dicen que las tres claves que dejan
de existir son `declaracion_jurada.predio_id`, `determinacion.predio_id` y
**`cuenta_corriente_asiento.predio_id`**. Medido: la tercera **no es una clave foránea y nunca
lo fue**. En `V2`:

```
backend/…/V2__rentas_y_cuenta_corriente.sql:221   predio_id            bigint,
backend/…/V2__rentas_y_cuenta_corriente.sql:222   vehiculo_id          bigint,
…:234   CONSTRAINT asiento_contribuyente_fk FOREIGN KEY (municipalidad_id, contribuyente_id)
…:235       REFERENCES contribuyente (municipalidad_id, id),
```

Son dos `bigint` sueltos, sin restricción, y lo mismo en `saldo_proyectado` (líneas 254–262).
ARQ-01 §4 regla 2 ya lo decía por escrito —«con `titularidad` y `vehiculo` **no la hay**»— y es
justamente por eso que #635 tuvo que inventar el puerto `TitularesDeLaUnidad` y #660 encontró
asientos con `predio_id` colgados. **La consecuencia para el corte es buena y hay que verla: el
libro no pierde ninguna garantía del motor al separar `catastro`, porque nunca la tuvo.** Las
que se pierden de verdad son **19**, y la lista de arriba es la lista. *Corregir D-18 y
ADR-0029 §Consecuencias.*

### `rentas` → `caja` (8 relaciones). Todas por `recibo_id`

| Tabla en `rentas` | Referencia | Migración | Qué acredita |
|---|---|---|---|
| `convenio` | `recibo` | `V3` | El recibo de la cuota inicial, que es lo que formaliza el convenio (`V31` lo exige con un `CHECK`) |
| `convenio_cuota` | `recibo` | `V3` | La cuota cobrada |
| `convenio_movimiento` | `recibo` | `V31` | |
| `licencia_funcionamiento` | `recibo` | `V4` | El derecho de trámite pagado (RF-110) |
| `licencia_duplicado` | `recibo` | `V4` | |
| `licencia_edificacion` | `recibo` | `V4` | |
| `edificacion_movimiento` | `recibo` | `V43` | |
| `certificado` | `recibo` | `V51` | |

Las ocho son el mismo hecho: **«esto se pagó antes de emitirse»**. Con la caja aparte, la
comprobación deja de ser una clave foránea y pasa a ser una llamada a
`caja.RecibosDeTramite` —el puerto ya existe (ARQ-01 §3.11)— o al `PagoRegistrado` de
ADR-0026 §3.

### `rentas` → `normativa` (6 relaciones). Todas por `conjunto_parametros`

`determinacion` (`V2`), `determinacion_arbitrio` (`V23`), `liquidacion_detalle` (`V39`),
`notificacion` (`V28`), `prescripcion` (`V28`), `descargo` (`V41`).

Es la regla de ARQ-01 §3.3 hecha columna: *toda determinación guarda el conjunto con que se
calculó*. Con `normativa` aparte, el `conjuntoId` sigue guardándose —ADR-0025 §3 lo exige, y le
añade la versión del catálogo de reglas— pero **deja de ser una FK**: pasa a validarse contra el
snapshot sellado que la corrida descargó y verificó por `sha256` (ADR-0025 §1).

### `caja` → `rentas` (1) y `catastro` → `rentas` (2)

| Tabla | Referencia | Migración | Salida |
|---|---|---|---|
| `recibo.contribuyente_id` | `contribuyente` | `V3` | **D-17**: o `caja` guarda su propio pagador y sólo enlaza cuando lo hay, o hay registro compartido |
| `titularidad.contribuyente_id` | `contribuyente` | `V1` | ADR-0027 §1 lo publica dentro del hecho sellado (`titulares[]` con `contribuyenteId`), así que catastro sigue guardando el número y **no** el padrón |
| `inquilino.contribuyente_id` | `contribuyente` | `V1` | Igual |

### `normativa` → `catastro` (1). La rara

`arancel.via_id → via` (`V1`). **Con ✅ D-N4 deja de cruzar**: el arancel se queda en `catastro`
con la `via` a la que referencia, y la FK se crea de verdad. Lo que cruza en su lugar es
`arancel → conjunto_parametros`, hacia `normativa`, que es la referencia al conjunto sellado con
que se publicó — la misma que ya llevan `determinacion` y `prescripcion`, y que ADR-0025 §3
conserva como dato guardado en vez de como restricción. **Ninguna FK sale de `normativa`.**

## 2.9 Las 68 migraciones, una a una

Ninguna se copia a ningún repositorio: ADR-0032 §1 las sustituye por un `V1__baseline.sql` por
sistema. Esta tabla dice **a qué baseline aporta DDL cada una**, que es lo que el generador de
baselines necesita saber para no dejarse nada. `C`=catastro, `N`=normativa, `K`=caja,
`R`=rentas, `∀`=los cuatro.

| Migración | Crea tablas | Aporta a | Qué es |
|---|---|---|---|
| `V1` | 19 | `∀ C N R` | Núcleo y catastro. **Pertenece a dos sistemas y por eso no se puede repartir** (ADR-0032) |
| `V2` | 13 | `R N` | Rentas y cuenta corriente. Incluye `valor_referencial_vehiculo`, que es de `normativa` |
| `V3` | 15 | `K R` | Cobranza, valores y coactiva. Parte el corte de `tesoreria` por la mitad |
| `V4` | 14 | `R` | Sanciones y licencias |
| `V5` | 10 | `∀` | Seguridad y auditoría |
| `V6` | 0 | `∀` | Row Level Security. **Aplica políticas a todo el esquema de una vez**: por eso no se puede repartir |
| `V7` | 0 | `∀` | Privilegios de todos los roles, igual |
| `V8` | 1 | `∀` | Estado de las copias de seguridad. Una tabla por base (✅ D-N7) |
| `V9` | 0 | `N` | El sellado de un conjunto es irreversible |
| `V10` | 0 | `N` | Un ejercicio puede tener más de un conjunto sellado |
| `V11` | 0 | `R` | Búsqueda de contribuyentes por aproximación (`pg_trgm`, `unaccent`) |
| `V12` | 1 | `R` | Responsables solidarios |
| `V13` | 5 | `C` | Fichas económica, de bienes comunes y rural |
| `V14` | 0 | `C` | Índices de la consulta de fichas (`text_pattern_ops`) |
| `V15` | 1 | `∀` | Documentos emitidos |
| `V16` | 0 | `∀` | `municipalidad.es_demostracion`. **Es la marca, no la siembra** (§7) |
| `V17` | 0 | `R N` | Placa normalizada, y `valor_referencial_vehiculo` cuelga del conjunto |
| `V18` | 0 | `N C` | `arancel`, `valor_unitario_edificacion` y `depreciacion` cuelgan del conjunto. Las dos últimas son de `normativa`; el `arancel`, de `catastro` (✅ D-N4) |
| `V19` | 0 | `R` | La DJ enlaza con la ficha vigente. **Estrena la FK `declaracion_jurada → ficha_catastral`** |
| `V20` | 3 | `R` | Detalle de determinación por predio |
| `V21` | 0 | `∀` | Lectura de `flyway_schema_history`. **ADR-0032 §2.2 la nombra como razón para conservar Flyway** |
| `V22` | 0 | `R` | Una sola versión vigente por código de infracción |
| `V23` | 3 | `R` | Determinación de arbitrios |
| `V24` | 0 | `R` | `acta_fiscalizacion`: de qué ficha partió, y la FK a `vehiculo` |
| `V25` | 0 | `N` | Un arancel sin tramo también es único |
| `V26` | 1 | `R` | Correlativo de valores |
| `V27` | 2 | `R` | Generación masiva de valores |
| `V28` | 4 | `R` | Notificación, prescripción y pase a coactiva |
| `V29` | 1 | `K` | El punto donde entra el dinero |
| `V30` | 1 | `K` | Anulación y duplicado de recibo |
| `V31` | 3 | `R` | El convenio de fraccionamiento. **Se queda en `rentas`** (ADR-0026 §5) |
| `V32` | 2 | `K` | Cierre del turno |
| `V33` | 2 | `R` | Expediente coactivo |
| `V34` | 0 | `R` | Actos coactivos y sus notificaciones |
| `V35` | 3 | `R` | Costas procesales |
| `V37` | 2 | `R` | Licencia de funcionamiento |
| `V39` | 4 | `R` | Liquidación de fiscalización |
| `V41` | 2 | `R` | Descargos, internamiento y resoluciones de gerencia |
| `V43` | 8 | `R` | Licencia de edificación (FUE) |
| `V45` | 2 | `R` | Anuncios y propaganda |
| `V47` | 3 | `R` | Valores masivos de papeletas y constancias |
| `V49` | 1 | `R` | Transferencia a rentas y su resolución de determinación |
| `V51` | 2 | `R` | Certificados y padrones |
| `V53` | 0 | `C` | Índice de construcción por ficha |
| `V54` | 1 | `R` | La DJ como acto: correlativo, unicidad, privilegio de columna |
| `V55` | 0 | `N` | **Las tres tablas de valuación son nacionales** (D-13, ADR-0017) |
| `V56` | 0 | `R` | Detalle de determinación: la parte exonerada del autovalúo |
| `V57` | 0 | `N` | La depreciación son cuatro tablas, no una (H-15) |
| `V58` | 0 | `C N` | La categoría constructiva llega a la `J` (ocho `CHECK` del esquema) |
| `V59` | 0 | `N C` | Las partidas del **cuadro** son tres; las de la **ficha** siguen siendo siete |
| `V60` | 1 | `R` | La muestra del programa de fiscalización |
| `V61` | 0 | `C` | La geometría del predio (PostGIS, ADR-0021) |
| `V62` | 2 | `R` | La corrida de emisión predial deja rastro |
| `V64` | 0 | `R` | Tipo de transferencia: vocabulario cerrado. **`NOT VALID` por datos** — ver §7.2 |
| `V65` | 0 | `C` | El marco del lote: las cuatro columnas generadas que sí llegan al índice bajo RLS |
| `V66` | 0 | `C` | El catálogo vial se puede buscar (columna generada + `text_pattern_ops`) |
| `V67` | 0 | `R` | Los cuatro alcances de la corrida predial |
| `V68` | 0 | `R` | El acto del asiento (`ALTA_DEUDA` / `BAJA_DEUDA`) |
| `V69` | 0 | `C` | Titularidad por predio y fecha |
| `V70` | 0 | `R` | Idempotencia del convenio |
| `V71` | 0 | `R` | La deuda declarada «de un titular anterior» deja rastro |
| `V72` | 0 | `C` | Vigencias que no se pisan (`btree_gist`, ficha y titularidad) |
| `V73` | 0 | `R` | La muestra admite el predio sin titular |
| `V74` | 0 | `R` | El tributo del libro es un vocabulario cerrado |
| `V75` | 0 | `R` | Idempotencia del alta de deuda |
| `V76` | 0 | `R` | El acta anota el uso hallado |
| `V77` | 0 | `R` | La causal de la baja. **`NOT VALID` por datos** — ver §7.2 |
| `V78` | 0 | `∀` | La restauración verificada de una copia. Se verifica una vez y se anota cuatro (✅ D-N7) |

**Números que nunca existieron:** `V36`, `V38`, `V40`, `V42`, `V44`, `V46`, `V48`, `V50`, `V52`,
`V63`.

**Las cuatro extensiones que los baselines tienen que declarar** (medido en `crear-roles.sql` y
en la guarda de #742): `pg_trgm` y `unaccent` (`V11` → `rentas` y `catastro`), `postgis`
(`V61` → **sólo `catastro`**) y `btree_gist` (`V72` → **sólo `catastro`**). Es el defecto que ya
costó dos despliegues; con cuatro baselines hay cuatro sitios donde olvidarlo.

**Los cinco hallazgos de RLS de [DAT-01 §0](../40-datos/modelo-logico-fisico.md) se copian al
encabezado de los cuatro baselines**, como ADR-0032 §Consecuencias exige. No es documentación:
el cuarto —una FK nueva sobre tabla con RLS no se puede validar— explica por qué hay `NOT VALID`
repartidos por 26 migraciones, y sin él alguien los «arreglaría» en el baseline.

---

# 3. `infra/` y `despliegue/` → `infrastructure` o descriptor

El criterio es [ADR-0031](../30-arquitectura/adr/ADR-0031-infraestructura-comun-y-propia.md) §1:
**lo que describe la plataforma es una sola cosa y no se multiplica por cuatro; lo que describe
un sistema cambia cuando cambia ese sistema y no puede vivir en un repositorio ajeno.**

En la columna «Destino», `infrastructure` = el repositorio nuevo; `descriptor` = la carpeta
`infrastructure/` de cada repositorio de sistema, publicada como `@sgtm/infra-<sistema>`
(ADR-0031 §2). `ambos` = la mecánica es común y lo que declara cada sistema es suyo.

## 3.1 Raíz de `infra/`

| Archivo | Destino | Por qué |
|---|---|---|
| `index.ts` | `infrastructure` | Compone el stack. Con cuatro sistemas, importa los cuatro descriptores y **fija su versión** |
| `config.ts`, `config.test.ts` | `infrastructure` | La configuración del ambiente, no la del sistema |
| `Pulumi.yaml`, `Pulumi.stg.yaml`, `Pulumi.prod.yaml` | `infrastructure` | **Dos stacks, no ocho** (ADR-0031 §3). `applicationBootstrapVersion` pasa de una a cuatro |
| `auditoria.ts` | `infrastructure` | **Es el `comun-verificaciones` de la infraestructura**: audita los descriptores ajenos con las mismas reglas que los propios |
| `capacidad.ts`, `herramientas/capacidad.ts` | `infrastructure` | ¿Cabe el stack en el nodo? Suma los cuatro; ninguno lo puede contestar solo |
| `package.json`, `yarn.lock`, `tsconfig*.json`, `eslint.config.mjs`, `vitest.config.ts` | `infrastructure` | Y **uno más por sistema** para su descriptor (ADR-0031 §Consecuencias lo acepta explícitamente) |
| `probar-localmente.sh` | `infrastructure` | |
| `README.md` | ambos | El de la plataforma se queda; cada descriptor lleva el suyo |

## 3.2 `infra/componentes/`

| Archivo | Destino | Por qué (ADR-0031 §1 lo decide fila a fila) |
|---|---|---|
| `BaseDeDatos.ts` | `infrastructure` | **Hay un motor.** Cada sistema declara su base y sus roles en su descriptor; la instancia es una |
| `Identidad.ts` + `identidad/reconciliar-realm.sh` | `infrastructure` | El login es común (ADR-0030 §3). Los *clients* de los cuatro frontends también, porque el realm es uno |
| `Ingreso.ts` | ambos | Traefik y TLS en `infrastructure`; **cada sistema declara sus rutas bajo su prefijo** y no puede reclamar el de otro (`catastro/api/v1/…`, ADR-0030 §2) |
| `Aplicacion.ts` | descriptor | Cambia con el sistema. `rentas` conserva sus dos perfiles, `web` y `batch` |
| `Migracion.ts` | descriptor | Cada base tiene sus migraciones y su prueba de aislamiento. **Su `sufijoDeVersion()` es lo que hace visible una deriva** (#675) |
| `Red.ts` | ambos | El *deny* por omisión es común; a quién puede llamar cada sistema lo declara él, y eso **hace visible el grafo de dependencias en el diff** |
| `Respaldo.ts` | `infrastructure` | Un motor, un respaldo, un simulacro |
| `Observabilidad.ts` | ambos | La instalación es común; las reglas de alerta y el panel de cada sistema van con él |
| `secretos.ts` | ambos | La mecánica es una; qué claves necesita cada sistema lo declara él |
| `convenciones.ts` | `infrastructure` | Un descriptor sin límites, sin sondas o con una etiqueta prohibida no pasa, venga de donde venga |
| `tipos.ts`, `fuentes.ts`, `index.ts` | `infrastructure` | Contrato del descriptor |
| `inicializacion/30-base-de-keycloak.sh` | `infrastructure` | |
| `inicializacion/40-rol-de-respaldo.sh` | `infrastructure` | El rol de respaldo es del clúster (INF-08, #155) |
| `inicializacion/50-rol-de-monitoreo.sh` | `infrastructure` | |

## 3.3 `infra/` — los guiones operativos

| Archivo | Destino | Por qué |
|---|---|---|
| `secretos/bootstrap-secretos.sh` | `infrastructure` | Habla con el API de Kubernetes por `kubectl`, nunca con `pulumi up` (ADR-0011 §3, INF-06) |
| `secretos/asignar-claves.sh` | `infrastructure` | Lleva al motor en marcha lo que el inventario declara. **Es el paso que faltaba** (#435) |
| `secretos/rotar-clave.sh`, `verificar-rotacion.sh`, `verificar-claves-distintas.sh` | `infrastructure` | El motor es uno; los roles de los cuatro sistemas viven en él |
| `respaldo/simulacro-de-restauracion.sh`, `contra-cluster.sh` | `infrastructure` | |
| `observabilidad/alertas.yml`, `dashboards/`, `verificar-alertas.sh`, `verificar-tableros.sh` | ambos | La instalación en `infrastructure`; **las reglas y el panel de cada sistema, en su descriptor** |
| `red/kind-sin-cni.yaml`, `verificar-red.sh` | `infrastructure` | |
| `vps/reservar-recursos-del-nodo.sh`, `cortafuegos.sh`, `comprobar-lo-asignable.sh` | `infrastructure` | **El VPS tiene dueño** (ADR-0031 §Consecuencias) |
| `herramientas/*` (10 archivos) | `infrastructure` | `emitir`, `declarar`, `declarar-version`, `secretos`, `completar-secreto` |
| `verificaciones/ambiente/verificar-el-ambiente.sh` | `infrastructure` | Compara el ambiente **desplegado** contra sí mismo. Con cuatro bases, pasa a comprobar cuatro |
| `verificaciones/motor/*` (3) | `infrastructure` | El motor que la verificación levanta; su `puerto.sh` ya resuelve el choque de tres motores en un runner (#731) |
| `verificaciones/deriva-de-migraciones.{ts,test.ts}` | `infrastructure` | **Con cuatro `applicationBootstrapVersion` hay cuatro derivas que vigilar**, y la guarda ya está escrita para una |
| `verificaciones/extensiones-de-las-migraciones.{ts,test.ts}` | `infrastructure` | Igual: hoy lee un directorio de migraciones; tendrá que leer cuatro (§2.9) |
| `verificaciones/*.test.ts` (10 más), `muestras/` (5), `secretos/muestras/` | `infrastructure` | Con sus clases de muestra, que es lo que hace que las reglas puedan fallar |
| `verificaciones/capacidad/verificar-contra-el-planificador.sh` | `infrastructure` | |
| `verificaciones/raiz-sellada/verificar-raiz-sellada.sh` | `infrastructure` | El init container de wal-g |
| `stacks.ts`, `stacks.test.ts` | `infrastructure` | |

## 3.4 `infra/carga-de-datos/` — se reparte

ADR-0031 §1 lo dice y lo medido lo confirma: **los guiones ya están agrupados por sistema sin
que nadie lo planeara.** Son **16** `.sh` y 11 archivos de ejemplo.

| Guión | Destino | Qué siembra (detalle en §7.3) |
|---|---|---|
| `sembrar-demostracion.sh` | `infrastructure` | **Es el único que orquesta**, y por eso se queda: llama a los diez de abajo en el único orden en que se pueden dar |
| `cargar-catalogo-vial.sh` | `catastro` | `via` |
| `cargar-sectores.sh` | `catastro` | `sector` |
| `cargar-manzanas.sh` | `catastro` | `manzana` |
| `cargar-predios.sh` | `catastro` | `predio` (desde el GeoPackage del plano) |
| `cargar-fichas-demo.sh` | `catastro` ⚠ | `predio` + `ficha_catastral` + `titularidad`. **Referencia `contribuyente`, que es de `rentas`** — ver §7.3 |
| `cargar-detalle-fichas-demo.sh` | `catastro` | Versiona la ficha con construcciones, obras, actividades, bienes comunes y tierras |
| `cargar-arancel-vial.sh` | `catastro` | `arancel`, contra un conjunto. **Se queda donde vive hoy**, en `sgtm-catastro` (✅ D-N4) |
| `publicar-parametros.sh` | `normativa` | `parametro_tributario` + `conjunto_parametro_detalle`, con la doble firma |
| `publicar-cuadros.sh` | `normativa` | `valor_unitario_edificacion`, `depreciacion`, `valor_referencial_vehiculo` |
| `abrir-conjunto-parametros.sh` | `normativa` | Abre y **sella** el conjunto del ejercicio |
| `cargar-cajas.sh` | `caja` | `area` + `caja` |
| `cargar-contribuyentes-demo.sh` | `rentas` | `contribuyente` |
| `cargar-vehiculos-demo.sh` | `rentas` | `vehiculo` |
| `cargar-transferencias-demo.sh` | `rentas` ⚠ | `transferencia` + cierra/abre `titularidad` en **catastro** — ver §7.3 |
| `cargar-deuda-demo.sh` | `rentas` | `cuenta_corriente_asiento` + `saldo_proyectado` |

Los `ejemplos/*.csv` viajan con su guión. `README.md` se parte: la orquestación queda en
`infrastructure` y el «qué escenario cubre» de cada archivo va con su sistema.

**Lo que hay que resolver antes de repartirlos, y no es un detalle**: los diez guiones de la
siembra hoy corren **el mismo artefacto** en perfil `batch` (ADR-0003), «no hay un binario de
carga aparte que pueda divergir del que atiende peticiones». Con cuatro artefactos, esa
propiedad se conserva por sistema y **se pierde para la siembra completa**, que pasa a
orquestar cuatro Jobs de tres imágenes distintas en un orden que cruza sistemas (§7.3).

## 3.5 `despliegue/` — el entorno local

ADR-0031 §4 lo parte en plataforma y sistema.

| Archivo | Destino | Por qué |
|---|---|---|
| `compose.yaml` | **se parte** | `infrastructure` publica el compose de la **plataforma** (PostgreSQL con las cuatro bases, Keycloak con sus realms, Traefik con el enrutado por prefijo). De sus 8 servicios: `base`, `identidad`, `correo` y `interfaz` (nginx) → plataforma; `migraciones`, `aplicacion`, `implantacion` y `datos` → **uno por sistema**. Un perfil `todo` levanta los cuatro |
| `.env.ejemplo` | ambos | |
| `crear-extensiones.sh` | `infrastructure` | Lee las extensiones de `crear-roles.sql` y las crea en un clúster que ya existía. **Con cuatro bases tiene que crear las de cada una** (§2.9) |
| `inicializacion-del-motor/20-asignar-claves.sh` | `infrastructure` | Sólo corre con el volumen vacío; por eso existe `secretos/asignar-claves.sh` (#435) |
| `identidad/realm-sgtm.json` | `infrastructure` | Un realm de funcionario para los cuatro (ADR-0030 §3), **con un *client* público por frontend** |
| `identidad/realm-sgtm-ciudadano.json` | `infrastructure` | El realm del ciudadano (ADR-0020). Su portal se queda con `rentas` |
| `identidad/reconciliar-identidades.sh`, `crear-usuario.sh`, `datos-de-implantacion.sh` | `infrastructure` | ADR-0012: el alta declarativa sin clave en git |
| `identidad/municipalidades/*.json` (2) | `infrastructure` | Los usuarios funcionarios declarados. **Su `municipalidadId` se cruza a tres bandas contra la base**; con ✅ D-N6 el cruce se invierte y verifica que las cuatro bases cuadran con Keycloak |
| `identidad/ciudadanos/*.json` (2) | `infrastructure` ⚠ | El enrolamiento de #415 **cruza el número de documento contra el padrón**, que es de `rentas`. Con la identidad en `infrastructure`, ese cruce pasa a ser una llamada |
| `README.md`, `identidad/README.md`, `identidad/*/README.md` | ambos | |

## 3.6 Lo que este inventario no cubre de infraestructura

`.github/workflows/` (9 archivos: `backend`, `frontend`, `infra`, `despliegue`,
`declarar-version`, `publicar-imagenes`, `escaneo-de-imagenes`, `secretos`, `documentacion`) se
multiplica por cuatro con la separación, y **ADR-0031 §3 sólo dice que «el flujo de CI se
conserva»**. Quién los escribe, si se comparten como *reusable workflows* y quién es dueño de
`publicar-imagenes.yml` no está decidido en ningún ADR. *Contesta: arquitectura, en la fase 0.*

---

# 4. ADR → repositorio

**Criterio: el ADR vive donde vive la decisión. Si aplica a todos, vive en `infrastructure` y
los demás lo enlazan.** Y un tercer caso que el criterio no nombraba y hace falta: **un ADR que
decide una frontera *entre* dos sistemas no vive en ninguno de los dos** —vive donde viven las
convenciones, que es `infrastructure`—, porque si viviera en uno, el otro tendría que pedir
permiso para cambiar su mitad.

Los 32 ADR se quedan **además** en `sgtm`, que no se borra: es la única copia con `git log`.
Lo que sigue dice dónde vive la **copia viva**, la que se sigue editando.

## 4.1 A `infrastructure` — 14, porque aplican a los cuatro

| ADR | Por qué |
|---|---|
| `ADR-0001` Plataforma del backend (Spring Boot 4, Java 25) | Los cuatro backends. Si divergen, `comun-*` deja de compilar en alguno |
| `ADR-0002` Esquema compartido con RLS | ✅ D-N8: se comparte y su decisión sigue en pie. Serán **cuatro bases, una por sistema**, y dentro de cada una un esquema compartido con RLS; el *cómo* se aísla es idéntico en los cuatro |
| `ADR-0004` PostgreSQL con particionado por ejercicio | Un motor (ADR-0031), y el particionado es la misma técnica en los cuatro |
| `ADR-0008` Auditoría con observación obligatoria | Regla 10, en los cuatro |
| `ADR-0011` Infraestructura como código | ADR-0031 lo **extiende**, no lo reemplaza: sus cuatro decisiones siguen vigentes |
| `ADR-0012` Usuarios y grupos declarativos | La identidad es común |
| `ADR-0024` La frontera del cálculo | Decide entre `catastro` y `rentas` |
| `ADR-0026` El camino del dinero | Decide entre `caja` y `rentas` |
| `ADR-0027` La valuación es un hecho sellado | Decide entre `catastro` y `rentas` |
| `ADR-0028` El tenant no cruza por HTTP | El riesgo número uno, en los cuatro. Su regla de ArchUnit viaja en `comun-verificaciones` |
| `ADR-0029` Cuatro sistemas separados | Es el ADR del corte |
| `ADR-0030` Cuatro interfaces, una sesión | Decide las librerías comunes de los cuatro |
| `ADR-0031` Infraestructura común y propia | Es el ADR de `infrastructure` |
| `ADR-0032` El esquema nace en baseline | Los cuatro baselines |

**✅ DECIDIDO D-N8 (2026-09-03, dirección del proyecto) — `ADR-0002` se comparte, y su decisión
sigue siendo la buena: cuatro bases de datos, una por sistema, y dentro de cada una un esquema
compartido por todas las municipalidades con RLS.**

Lo que cambia no es la decisión sino el número: donde decía «un esquema compartido» ahora hay
**cuatro**, y en cada uno el aislamiento entre municipalidades se sostiene igual —el
`municipalidad_id` del claim, el `SET LOCAL` y las políticas sin valor por omisión—. Su
§Consecuencias hay que ampliarla a eso, y con ella los cinco hallazgos de RLS de DAT-01 §0, que
pasan a tener cuatro sitios donde tropezar (§2.9).

**Y la decisión descarta de paso lo que ADR-0029 llama «lo peor de los dos mundos»**: una base
compartida entre los cuatro sistemas, que serían cuatro despliegues con el acoplamiento de uno y
una migración de esquema que rompe un sistema que nadie tocó.

## 4.2 A un sistema — 13

| ADR | Destino | Por qué |
|---|---|---|
| `ADR-0006` La cuenta corriente es un libro inmutable | `rentas` | ADR-0026 lo **conserva** explícitamente: el libro sigue siendo inmutable, y se queda con quien imputa |
| `ADR-0007` Parámetros versionados y sellados | `normativa` | ADR-0025 lo conserva sin cambios de fondo |
| `ADR-0013` Permisos de la sesión | `rentas` | ADR-0030 §3 lo conserva y pone la lectura en `rentas/api/v1/sesion/permisos`. **D-19 sigue abierta** |
| `ADR-0014` Navegación centrada en la atención | `rentas` | Es el shell del back-office, y el catálogo de las 134 opciones vive con la aplicación que las agrupa hoy. **Se revisa cuando las 134 se repartan** (ADR-0030 §Consecuencias) |
| `ADR-0015` Conciliación catastro↔rentas | `rentas` | Ya decide que **la sirve `rentas`**, y ADR-0030 §2 le cambia la ruta a `rentas/api/v1/fichas/conciliacion`. Enlazado desde `catastro` |
| `ADR-0016` El inicio pregunta y la ficha compone | `rentas` | La ficha 360° del contribuyente |
| `ADR-0017` Las tres tablas de valuación son nacionales | `normativa` | ADR-0025 §4 lo traslada tal cual |
| `ADR-0018` El redondeo decidido | `normativa` | Va con `normativa-reglas`: ADR-0024 §3 exige que no haya dos interpretaciones del `HALF_UP` |
| `ADR-0019` La porción sin titular no se determina a nadie | `catastro` | Es una invariante de la titularidad. **Enlazado desde `rentas`**, que es quien no determina |
| `ADR-0020` La sesión del ciudadano | `rentas` | El portal se queda con `rentas` (ADR-0030 §1). **El realm es de `infrastructure`**, así que se enlaza en las dos direcciones |
| `ADR-0021` La geometría del predio | `catastro` | Su stack propio: PostGIS, GPKG |
| `ADR-0022` El visor del plano catastral | `catastro` | |
| `ADR-0023` La muestra de fiscalización se sortea | `rentas` | Fiscalización |

## 4.3 Se quedan en `sgtm` como histórico — 3

| ADR | Estado | Por qué no viaja |
|---|---|---|
| `ADR-0003` Monolito modular con Spring Modulith | **Reemplazado por ADR-0029** | Su argumento —«el equipo que mantendrá esto en una municipalidad no opera doce despliegues»— sigue siendo el que hay que contestar (**D-22**). Se enlaza desde ADR-0029, no se copia |
| `ADR-0009` React con Vite, «una sola aplicación por ahora» | **Reemplazado en esa cláusula por ADR-0030** | El resto —React, Vite, TypeScript— pasa a `@sgtm/ui` y a las convenciones de `infrastructure` ⚠ |
| `ADR-0010` Catálogo portado y proxy de datos | **Describe algo que ya no existe** | `CLAUDE.md` lo dice con todas las letras: «ninguno de los dos existe hoy». Se cita en varios sitios como precedente («el proxy no filtra»), y esas citas hay que resolverlas al mover cada documento |

⚠ **`ADR-0005` (OIDC para autenticar; el modelo de permisos del manual para autorizar) se parte
por la mitad y no está decidido cómo.** Su primera mitad —OIDC, el claim, la validación— es de
`infrastructure` y ADR-0028 la extiende. Su segunda mitad —134 opciones × 7 privilegios, grupos
por tarea— es de `rentas` (§2.6) y **D-19** la tiene abierta. *No se decide aquí.*

---

# 5. Documento de `docs/` → repositorio

**`docs/60-frontend/` no se mueve todavía** (instrucción de este trabajo). Los 6 documentos que
contiene se quedan en `sgtm` hasta que ADR-0030 se aplique.

Medido: **122** archivos `.md` y **36** que no lo son.

| Ruta | Destino | Por qué |
|---|---|---|
| `docs/README.md` | ambos | El índice se parte: uno por repositorio |
| `docs/00-gobierno/decisiones-abiertas.md` (GOB-02) | `infrastructure` | **25 decisiones, y 14 son transversales.** Partirla por sistema deja a D-22 y D-25 sin dueño. Los sistemas la enlazan |
| `docs/00-gobierno/vision-y-alcance.md` | `infrastructure` | Es del producto, que ahora son cuatro |
| `docs/00-gobierno/glosario-tributario.md` | `infrastructure` | El vocabulario es uno; que se parta es exactamente lo que hay que evitar |
| `docs/00-gobierno/plan-de-desbloqueo-D-02.md` (GOB-03) | `normativa` | Es el plan de carga de valores normativos: H-14, H-15, el mapa de fuentes |
| `docs/00-gobierno/plan-de-marcha-blanca.md` (GOB-04) | `infrastructure` | Recorre el sistema entero |
| `docs/00-gobierno/inventario-del-corte.md` | `infrastructure` | **Este archivo**, y se queda además en `sgtm` como el estado del que se partió |
| `docs/00-gobierno/verificar-*.mjs` (2) | `infrastructure` | Verifican `CLAUDE.md`: la fila del registro y sus muestras (#711). **Con cuatro `CLAUDE.md` hay cuatro registros** |
| `docs/10-negocio/catalogo-de-opciones.md` (NEG-03) + `generar-catalogo.mjs` + `etiquetas-de-bloqueo.json` | `infrastructure` ⚠ | Las 134 opciones del manual **se reparten por sistema pero se cuentan juntas**: ADR-0030 §Consecuencias exige que sigan siendo 134, ninguna desaparece y ninguna se duplica. Esa cuenta necesita un sitio |
| `docs/10-negocio/mapa-de-macroprocesos.md` (NEG-01) | `infrastructure` | |
| `docs/10-negocio/marco-normativo.md` (NEG-02) | `normativa` | |
| `docs/10-negocio/valores-normativos/` (**60 archivos**: el corpus, sus 6 verificadores, `publicacion/`, `fuentes/`, `_muestras/`) | `normativa` | ADR-0025 §5 lo dice: «el corpus y su verificador se mudan con el servicio, porque la doble verificación empieza en el documento y no en la fila» |
| `docs/10-negocio/observaciones-srtm-mef/` (2) | `sgtm` / `rentas` ⚠ | Es el procedimiento de la campaña de observación de **D-04**, que ADR-0032 §4 deja explícitamente fuera del corte |
| `docs/20-requisitos/requisitos-funcionales.md` (RF-001…RF-133) | **se reparte** | Cada RF va con el sistema que lo cumple, y hace falta construir la tabla puente `RF → sistema` (✅ D-N9) |
| `docs/20-requisitos/requisitos-no-funcionales.md` (RNF) | `infrastructure` | RNF-051 (sin `DELETE`), RNF-055 (`BigDecimal`), RNF-075 (toda cifra con su fecha), RNF-080, RNF-082, RNF-083 son de los cuatro y están escritos como reglas de build |
| `docs/20-requisitos/actores-y-permisos.md` | `rentas` | Va con `seguridad` (§2.6) |
| `docs/30-arquitectura/contextos-acotados.md` (ARQ-01) | **se reparte** | Cada sistema documenta sus contextos. **Y hay que corregir §2 y §3.13 antes** (✅ D-N3) |
| `docs/30-arquitectura/estrategia-multitenant.md` (ARQ-03) | `infrastructure` | «Es el riesgo número uno» y ADR-0028 lo extiende a la frontera HTTP |
| `docs/30-arquitectura/estandares-de-codigo-backend.md` | `infrastructure` | Las diez reglas y sus verificaciones viajan en `comun-verificaciones` |
| `docs/30-arquitectura/adr/` (32 + README) | §4 | |
| `docs/40-datos/modelo-logico-fisico.md` (DAT-01) | **se reparte, con §0 replicado** | Sus **cinco hallazgos de RLS** se copian al encabezado de los cuatro baselines (ADR-0032 §Consecuencias) y a los cuatro DAT-01 |
| `docs/40-datos/auditoria-e-historico.md` | `infrastructure` | Regla 10, en los cuatro |
| `docs/50-api/openapi/sgtm-v1.yaml` + `generar-openapi.mjs` + `formas-de-la-api.json` + `respuestas-de-la-api.json` | **se reparte en cuatro** ⚠ | Un contrato por sistema, con el prefijo delante (ADR-0030 §2). **Y el arnés `prosa` pasa a mirar cuatro contratos** (ADR-0030 §5). Hoy son 225 operaciones en 202 rutas, un solo archivo derivado |
| `docs/60-frontend/` (6) | **no se mueve** | Instrucción de este trabajo |
| `docs/80-infraestructura/` (7: `ambientes`, `arquitectura-de-infraestructura`, `endurecimiento-del-cluster`, `entorno-local-de-desarrollo`, `gestion-de-secretos`, `observabilidad-y-alertas`, `respaldo-y-recuperacion`) | `infrastructure` | Los siete describen la plataforma, que es una |
| `docs/A0-calidad/estrategia-de-pruebas.md` | `infrastructure` | La estrategia es común; el inventario de pruebas de cada sistema, suyo |
| `docs/B0-operacion/runbooks/` (11) | `infrastructure` | Los once son del clúster, del motor, de Keycloak o del respaldo. **Ninguno es de un sistema** — comprobado uno a uno |
| `docs/D0-desarrollo/` (6) | ambos | El de la plataforma en `infrastructure`; cada repositorio necesita el suyo, porque «levantar lo mío contra la plataforma» es distinto en cada uno (ADR-0031 §4) |
| `scripts/catastro/` (2 importadores Python) | `catastro` | Plano GPKG y padrón MEF |
| `scripts/valores-normativos/` (3) | `normativa` | Archivar fuentes, importar aranceles |

**✅ CONFIRMADO D-N9 (2026-09-03, dirección del proyecto) — la tabla puente `RF → sistema` no
existe y hay que construirla.** RF-001…RF-133 están agrupados por módulo del **menú**, y ARQ-01
§1 explica por qué el menú no es el modelo: «Tránsito» e «Infracciones administrativas» son dos
módulos del menú con el mismo modelo, y «Consultas» es un módulo entero sin modelo propio.

Son 133 filas que decidir, y **varias caen en dos sistemas** —RF-080 «cobranza» toca `caja` y
`rentas`, RF-110 «el derecho de trámite pagado» toca `caja` y `licencias`—. Es trabajo de la fase
0, se hace con NEG-03 delante, y **su criterio de terminado ya está escrito en otro sitio**:
ADR-0030 §Consecuencias exige que las 134 opciones del manual sigan siendo 134 repartidas por
sistema, ninguna desaparece y ninguna se duplica. La misma cuenta vale para los RF.

---

# 6. Los cruces

Todo sitio donde un módulo lee una tabla de **otro sistema futuro** por SQL en vez de por un
puerto público. Son los que se rompen al partir la base, y **los que no aparezcan aquí
aparecerán en producción.**

## 6.0 Cómo se buscaron, para que se pueda repetir

```bash
cd backend
# 1. qué tablas nombra el SQL de cada módulo
for m in sgtm-*/; do
  grep -rhoiE '\b(FROM|JOIN|INSERT INTO|UPDATE|DELETE FROM)[[:space:]]+[a-z_][a-z_0-9]*' \
       "$m/src/main/java" | awk '{print tolower($NF)}' | sort -u
done
# 2. cruzarlo con el reparto de §2 y quedarse con lo que cambia de sistema
```

Los falsos positivos del patrón son cuatro y conviene nombrarlos para que nadie los persiga:
`JOIN LATERAL`, `UPDATE SET` (de un `ON CONFLICT … DO UPDATE SET`), `UPDATE OF` (de un
`FOR UPDATE OF`) y palabras sueltas de prosa castellana en javadoc (`FROM y`, `JOIN son`,
`UPDATE si`).

**Resultado: 7 archivos, 20 puntos de lectura, más 1 cruce transaccional que no es una consulta.**
El barrido inverso también se hizo: **`catastro` no lee ni una tabla de `rentas` por SQL**, y
`sgtm-parametros` no lee ninguna ajena.

## 6.1 `DeteccionRepositoryJdbc` — `rentas` → `catastro`. El más caro

**`backend/sgtm-fiscalizacion/…/infraestructura/DeteccionRepositoryJdbc.java:99, 100, 103, 140`**

| Qué hace | Tablas ajenas | Salida |
|---|---|---|
| El cruce del padrón de predios con las declaraciones juradas de un ejercicio, **paginado y contando lo filtrado**. La columna «Condición» —`CONFORME` / `OMISO` / `SUBVALUADOR` / `NO_UBICADO`— es un derivado de ese cruce que la pantalla **filtra**, y por eso la expresión está escrita **una sola vez** y en SQL, en el `SELECT` y en el `WHERE` (la lección de #397) | `predio` (99), `sector` (100), `ficha_catastral` (103 y 140) | **Proyección local en `rentas`, alimentada por evento** |

Es el único cruce que ADR-0029 §Consecuencias nombra por su nombre, y dice por qué la salida no
puede ser otra: **componerlo en memoria ya se probó y falló** —#631 dejó la conciliación
contestando «722 páginas, 14 422 elementos» y cero filas en todas—. Un cruce que pagina y cuenta
lo filtrado no se puede resolver con dos listas y un bucle.

Lo que la proyección tiene que llevar, medido en la propia consulta: de `predio`, el
identificador, el código de referencia catastral, el estado y el sector; de `sector`, el código;
de `ficha_catastral`, la versión vigente a la fecha de tipo `UNICA` con su área y su uso, **y la
versión que la declaración citó** (`fd`, línea 140), que no es la misma y es la que sostiene la
comparación hallado/declarado. `declaracion_jurada` **no entra**: ya es de `rentas`.

Y una guarda que no se puede perder: ARQ-01 §3.5 dice que su transcripción SQL **no se separe de
`ComparacionHalladoDeclarado`**, y eso lo sostiene una prueba que las compara caso por caso. Con
la proyección, esa prueba sigue valiendo igual —la expresión no cambia, cambia de dónde salen
las filas—. **Y `#608` añadió una segunda transcripción en la misma clase**, la de la diferencia
de área para ordenar; las dos viajan.

## 6.2 `CobrarDeuda` — `caja` ↔ `rentas`. El único que no es una consulta

**`backend/sgtm-tesoreria/…/aplicacion/CobrarDeuda.java:82-87`**

No lee ninguna tabla ajena por SQL: **lee y escribe en dos sistemas en una transacción**, que es
peor. Es el `COMMIT` que ADR-0026 §3 convierte en dos.

| Qué hace | Sistemas | Salida |
|---|---|---|
| Abre el turno si hace falta, emite el recibo con su correlativo, **asienta los abonos en el libro** (`RegistroDeAbonos`), **formaliza el convenio si toca** (`FormalizarConvenio`) y audita, todo en un `@Transactional` | `caja` (recibo, turno) + `rentas` (libro, convenio) | **La clase se parte.** Caja emite el recibo y publica `PagoRegistrado` por outbox; `rentas` imputa al recibirlo |

Con las cinco piezas que ADR-0026 §4 exige antes de encenderlo, y que hoy no existen: el estado
**pago en tránsito** visible con su hora, la cola de mensajes muertos con alerta a una persona
con nombre, el cierre de turno bloqueante, `PagoAnulado` con asiento de reversión, y treinta días
en paralelo detrás de una bandera.

**Lo que se pierde y hay que decirlo aquí, porque este inventario es donde se ve:** #33 midió que
la atomicidad de la cobranza la sostiene una transacción y un `FOR UPDATE` sobre
`saldo_proyectado`, y que sin el candado salen **2 cobros donde debe haber 1** con diez cajas y
diez series. Ese candado deja de existir cuando el recibo y el saldo están en dos bases. La
idempotencia de `recibo_idempotencia_uq` (`V29`) se conserva en `caja`; la del asiento la tiene
que dar el consumidor del evento, y **eso no está construido**.

## 6.3 `ConciliacionRepositoryJdbc` — `rentas` → `catastro`

**`backend/sgtm-rentas/…/infraestructura/ConciliacionRepositoryJdbc.java:43, 44`**

| Qué hace | Tablas ajenas | Salida |
|---|---|---|
| El **recuento** de la conciliación (#564): cuántas fichas vigentes a una fecha tienen declaración jurada del ejercicio y cuántas no. `FROM ficha_catastral f JOIN predio p ON p.id = f.predio_id`, con un `LATERAL` sobre `declaracion_jurada` | `ficha_catastral` (43), `predio` (44) | **La misma proyección local de §6.1** |

Es la mitad numérica de ADR-0015, y su población es **la de la grilla, letra por letra** —lo dice
su propio javadoc—. Así que no es un cruce distinto: es el mismo padrón proyectado, contado en
vez de paginado. Si se resuelve con dos proyecciones distintas, la grilla y su recuento pueden
decir cifras distintas del mismo día, que es exactamente el defecto que #564 midió al contar
sobre la página en vez de sobre la consulta entera.

`AcotacionPorPredio` (#631) viaja con ella: el filtro entra **en el mismo `WHERE`** y no se
aplica en memoria, por lo que aquel issue midió.

## 6.4 `ValuacionRepositoryJdbc` — `catastro` → `normativa`

**`backend/sgtm-catastro/…/infraestructura/ValuacionRepositoryJdbc.java:55, 69, 105-106, 137-138`**

| Qué hace | Tablas ajenas | Salida |
|---|---|---|
| Lee el arancel de una vía (55), **escribe** el arancel al cargarlo (69), y lee el valor unitario (105) y la depreciación (137), **los dos con `JOIN conjunto_parametro_detalle`** para no ver más que lo que el conjunto sellado contiene | `arancel`, `valor_unitario_edificacion`, `depreciacion`, `conjunto_parametro_detalle` | **Snapshot sellado, cacheado localmente** (ADR-0025 §1) para las **tres** nacionales. El `arancel` deja de cruzar: se queda en `catastro` (✅ D-N4) |

Éste es el cruce que ADR-0025 resuelve **sin llamada por red en el camino caliente**: al abrir
una corrida se resuelve una vez el `conjuntoId`, se descarga el snapshot, se verifica su
`sha256` y se cachea en tabla local **para siempre**, porque lo sellado no cambia (`V9`). Una
corrida de 300 000 predios hace una petición, no 300 000.

El `JOIN` con `conjunto_parametro_detalle` es justo lo que hace la salida posible: la consulta ya
está escrita contra «lo que este conjunto contiene» y no contra «la tabla», así que la caché
local tiene la misma forma. **Y la línea 69 —el `INSERT INTO arancel`— es la que no encaja**: es
una escritura desde `catastro` hacia una tabla que ✅ D-N4 **deja en `catastro`**, así que no
cruza ninguna frontera. Lo que sí sigue siendo cierto es ADR-0025
§5 dice que «la aplicación no tiene camino hasta el cuadro: `catastro` y `rentas` sólo leen».
§5 lo dice de los **cuadros nacionales**, y el arancel no es uno: es municipal y su fuente es un
plano. `CargarArancelVial` y su guión se quedan en `catastro`.

## 6.5 `ValorReferencialRepositoryJdbc` — `rentas` → `normativa`

**`backend/sgtm-rentas/…/infraestructura/ValorReferencialRepositoryJdbc.java:68-69, 115-116`**

| Qué hace | Tablas ajenas | Salida |
|---|---|---|
| El valor referencial de un vehículo por marca, modelo, categoría y año, resuelto **por conjunto** y no por ejercicio (el defecto que ARQ-09 §3 nombra) | `valor_referencial_vehiculo`, `conjunto_parametro_detalle` | **Snapshot sellado, igual que §6.4** |

Es la asimetría que ADR-0025 §Consecuencias anticipa: `catastro` sólo necesita las tablas de
valuación; `rentas` necesita además la UIT, los tramos, las deducciones, los plazos **y ésta**.
El snapshot se puede pedir por ámbito, pero **la identidad del conjunto es la misma para los
dos**, y eso es lo que la corrida compara.

## 6.6 `TitularPrincipalRepositoryJdbc` — `rentas` → `catastro`

**`backend/sgtm-rentas/…/infraestructura/TitularPrincipalRepositoryJdbc.java:23`**

| Qué hace | Tablas ajenas | Salida |
|---|---|---|
| «¿A quién se le cobra el arbitrio de este predio en esta fecha?» — `SELECT contribuyente_id FROM titularidad WHERE predio_id = … AND vigencia_desde <= :fecha AND (vigencia_hasta IS NULL OR vigencia_hasta >= :fecha) ORDER BY porcentaje DESC, id ASC LIMIT 1` | `titularidad` | **Puerto HTTP** — y ya existe |

Es una lectura de **una fila por un identificador**, sin `JOIN` y sin paginación: exactamente lo
contrario de §6.1. `catastro` ya publica `TitularesDelPredio` (#366) y ADR-0027 §1 mete
`titulares[]` **dentro del hecho sellado**, con la condición y el porcentaje resueltos **a la
fecha de corte** —porque «una titularidad resuelta a fecha distinta de la valuación es una base
imponible mal repartida»—.

**Cuidado con el criterio de desempate**, que es propio de esta consulta y no del puerto: *el
titular de mayor porcentaje, y a igualdad el de menor `id`*. Si la llamada devuelve la lista de
cuotas y el desempate se rehace en `rentas`, hay que llevárselo tal cual; si lo resuelve
`catastro`, esa regla de arbitrios se muda a catastro, que es lo que ADR-0024 §2 evita.

## 6.7 `CuotaDeArbitrioRepositoryJdbc` — `rentas` → `catastro`

**`backend/sgtm-rentas/…/infraestructura/CuotaDeArbitrioRepositoryJdbc.java:111`**

| Qué hace | Tablas ajenas | Salida |
|---|---|---|
| **Sólo cuando el usuario filtra por código predial**: `JOIN predio p ON p.id = d.predio_id` + `WHERE p.codigo_ref_catastral = :codigoPredial`. Es un `JOIN` para traducir un código a un identificador | `predio` | **Puerto HTTP**, o la proyección de §6.1 si ya está |

El más barato de los siete y el que más fácil se resuelve mal: la tentación es resolver el código
en la aplicación con una llamada previa, y eso está bien **porque el filtro devuelve como mucho
un predio**. Lo que no se puede hacer es lo mismo en §6.1, donde el `JOIN` es sobre el padrón
entero.

## 6.8 `ReciboRepositoryJdbc` — `caja` → `rentas`

**`backend/sgtm-tesoreria/…/infraestructura/ReciboRepositoryJdbc.java:204`**

| Qué hace | Tablas ajenas | Salida |
|---|---|---|
| Filtrar recibos por contribuyente: `AND r.contribuyente_id = (SELECT t.id FROM contribuyente t …)`. Traduce el código del padrón al identificador | `contribuyente` | **D-17**, y hasta que se decida, **puerto HTTP** |

Es el mismo caso que §6.7 —traducir un código a un identificador— y es el que **D-17** tiene
abierto: el día que la caja cobre un puesto de mercado, el pagador puede no estar en
`contribuyente`. Los dos caminos que D-17 plantea (registro compartido, o pagador propio de
`caja` que sólo enlaza cuando lo hay) cambian esta consulta de forma distinta.

## 6.9 Los que parecen cruces y no lo son

Se listan **porque van a aparecer** en cualquier barrido futuro, y perder tiempo en ellos dos
veces es peor que escribirlos una.

| Sitio | Por qué no se rompe |
|---|---|
| `sgtm-cuentacorriente/…/AsientoRepositoryJdbc.java:103, 150, 297, 332, 641` — `JOIN contribuyente` y `contribuyentePorCodigo` | `cuentacorriente` y `contribuyentes` **van los dos a `rentas`**: el cruce desaparece con el corte. Su javadoc lo justifica «contra una tabla con la que ya hay clave foránea», y esa FK (`asiento_contribuyente_fk`, `V2`) **se queda dentro de `rentas`**. ARQ-01 §4 regla 2 ya avisa de que el precedente **no vale** para `titularidad` ni `vehiculo`, donde no hay FK |
| `sgtm-tesoreria/…/ConvenioRepositoryJdbc.java:229, 256` — `FROM contribuyente` | El convenio se queda en `rentas` (ADR-0026 §5); `contribuyente` también |
| `sgtm-sanciones/…/PapeletaRepositoryJdbc.java:169, 174`, `PadronDePapeletasRepositoryJdbc.java:86, 87`, `ProcedimientoSancionadorRepositoryJdbc.java:80` — `JOIN contribuyente` | `sanciones` va a `rentas` |
| `sgtm-fiscalizacion/…/DeteccionRepositoryJdbc` — `declaracion_jurada` | `fiscalizacion` y `rentas` van los dos a `rentas` |
| `sgtm-rentas/…/VehiculoRepositoryJdbc.java:144` — `FROM auditoria` | `auditoria` se **replica** en los cuatro (§2.5): cada sistema lee la suya |
| `sgtm-seguridad/…/SesionRepositoryJdbc.java:148, 150, 163, 165` — `auditoria` y `respaldo` | Igual: las dos se replican en los cuatro (✅ D-N7 para `respaldo`) |
| `sgtm-plataforma/…/DocumentoRepositoryJdbc`, `AuditoriaJdbc`, `RegimenDeLaInstalacionJdbc`, `RecorridoPorMunicipalidades` | Tablas transversales replicadas (§2.5), con la mecánica en el artefacto aparte de ✅ D-N2 |

## 6.10 Y los cruces que **no** son por SQL: los puertos que pasan a ser HTTP

No se rompen —el compilador los sostiene hoy— pero **dejan de sostenerlos el compilador y pasan
a sostenerlos una prueba de contrato** (ADR-0030 §4: «es lo que sustituye a lo que hoy hace el
compilador cuando alguien cambia la firma de un puerto»). Medidos en los `build.gradle.kts`:

| Arista que cruza sistema | Puerto | Dónde |
|---|---|---|
| `catastro → rentas` | `contribuyentes.DirectorioDeContribuyentes` | `sgtm-catastro` depende de `sgtm-contribuyentes` |
| `catastro → normativa` | `parametros.LectorDeParametros` | `sgtm-catastro` depende de `sgtm-parametros` |
| `rentas → catastro` | `catastro.GestorDeTitularidad` (#29), `catastro.TransferenciaDeFiscalizacion` (#52), `catastro.LectorDeFichas`, `catastro.TitularesDelPredio` (#366), `catastro.LectorDeFichasEconomicas` (#19) | `sgtm-rentas`, `sgtm-fiscalizacion`, `sgtm-licencias` |
| `rentas → normativa` | `parametros.LectorDeParametros`, `ParametrosSellados`, `PoliticasDeRedondeoSelladas` | seis módulos |
| `rentas → caja` | `tesoreria.RecibosDeTramite` (RF-110), `tesoreria.AvanceDeCaja`, `tesoreria.CobrosDeTasas` | `sgtm-licencias`, `sgtm-sanciones`, `sgtm-coactiva`, `sgtm-indicadores` |
| `caja → rentas` | `cuentacorriente.RegistroDeAbonos`, `SeleccionDeObligacion`, `TributoDelLibro` | `sgtm-tesoreria` (§6.2) |

**Las dos escrituras de `rentas` hacia `catastro` son las que más cuidado piden**, porque hoy
están garantizadas **mecánicamente**: `SOLO_LA_TRANSFERENCIA_ESCRIBE_FUERA_DE_FISCALIZACION` es
una regla de ArchUnit con **dos** clases de muestra que la violan (ARQ-01 §3.5). Una regla de
ArchUnit no atraviesa una frontera HTTP. **Lo que la sustituya tiene que existir el día que la
frontera se abra**, o la garantía se pierde sin que nada se ponga rojo — que es exactamente el
modo de fallo que este proyecto lleva doscientos issues evitando.

---

# 7. Lo que ya está contestado, comprobado

## 7.1 El dato de partida

**No hay datos reales en ningún ambiente**: ni en `prod`, ni en `stg`, ni en el compose local.
Todo lo que hay es la instalación de demostración y lo que siembran los guiones de
`carga-de-datos/`, y todo se reconstruye desde cero. Es lo que **D-04** ya decía por escrito —«el
piloto arranca con padrón nuevo»— y lo que
[ADR-0032](../30-arquitectura/adr/ADR-0032-el-esquema-nace-en-baseline.md) fija como dato de
partida. **No es una pregunta abierta**, y es lo que abarata todo el corte: ninguna extracción
necesita plan de migración de datos ni conciliación de saldos.

El trabajo de esta sección era **comprobar que ningún guión, ninguna migración y ningún documento
asume lo contrario**. Lo que se encontró:

### Lo que lo confirma

- **Ninguna de las 68 migraciones inserta una sola fila.** `grep -rinE '^\s*INSERT INTO'` sobre
  el directorio de migraciones vuelve vacío. El esquema es DDL puro.
- **Ningún `DROP TABLE` en toda la historia**, así que no hay ninguna reconstrucción de datos
  escondida en una migración.
- **`D-04` sigue diciendo lo mismo** y está enmarcada como trabajo de implantación de un
  municipio concreto, no del corte. ADR-0032 §4 lo repite: «existiría igual sin el corte».
- **Los guiones de demostración se protegen contra el escenario contrario, no lo asumen.** Los
  pasos 5 a 10 exigen `municipalidad.es_demostracion = true` **comprobado contra la base por cada
  proceso**, para que un `--municipalidad-id` equivocado en un dígito no siembre personas
  inventadas en un padrón que ya opera. Es una guarda que se anticipa a que la ventana se cierre.
- **`simulacro-de-restauracion.sh --contra-cluster` sólo corre contra `stg`**, «porque prod no se
  ensaya con datos reales de nadie». Misma clase: **guarda correcta cuya premisa hoy no aplica.
  No se toca.**
- **Repetir un paso no duplica**: las filas ya cargadas se rechazan una a una por violar su
  unicidad, y `--desde N` retoma una siembra interrumpida. La reconstrucción es idempotente.

### Lo que sí lo asume, y hay que nombrarlo

**Son tres migraciones, y las tres razonan sobre un padrón que no se puede medir ni tirar.** No
estaban equivocadas cuando se escribieron —desde dentro de una migración no se puede saber qué
hay en la base de otro ambiente—, pero **su razonamiento no vale al generar los baselines**, y
copiarlo tal cual sería arrastrar una restricción sin validar sin ningún motivo.

| Migración | Qué asume, textual | Qué pasa en el baseline |
|---|---|---|
| **`V64`** §2 | «el motivo es de datos: **no se puede medir qué hay hoy en la columna** de las instalaciones desplegadas […] validado, ese `ALTER TABLE` falla con *is violated by some row* y deja la instalación sin migrar» | El `CHECK` del tipo de transferencia **nace validado**: la tabla nace vacía. Comprobado que la semilla ya no lo violaría — `ejemplos/transferencias.csv` escribe hoy los nueve valores buenos, y `COMPRAVENTA` sólo sobrevive en un comentario del propio archivo que cuenta que se corrigió |
| **`V77`** §3(c) | «una instalación en marcha **ya tiene bajas escritas por `V68`** con la causal dentro de la observación, de modo que un `ALTER TABLE` validado fallaría» | `asiento_baja_con_causal_ck` **nace validado**. Y con él se va la asimetría que obligaba a excluir la reversión, que en el baseline pasa a ser una decisión libre y no una consecuencia |
| **`V68`** | «la inflación que ya tiene el panel de una instalación en marcha **no se repara sola**» | No hay inflación que reparar: las bajas nacen con su `acto`. La advertencia se queda como historia en `sgtm` |

Un cuarto caso, menor y del mismo tipo: **`V75`** apoya el predicado de
`asiento_alta_unica_uq` en que «la columna la estrenó `V68` y en toda fila anterior es nula, así
que ninguna fila previa puede violar el índice». En el baseline no hay filas previas y el
predicado se puede reconsiderar — **pero no se toca sin medir**, porque además excluye la
reversión por un motivo que sigue siendo válido (`Asiento#reversionDe` copia el acto).

### Y una precisión sobre la premisa misma

**ADR-0032 §Contexto dice «todo lo que hay es la instalación de demostración (`V16`)», y `V16` no
siembra nada.** Medido: `V16` es un `ALTER TABLE municipalidad ADD COLUMN es_demostracion
boolean NOT NULL DEFAULT false` con su `COMMENT`, y ni un `INSERT`. La municipalidad la crea
`ImplantarMunicipalidad` (perfil `batch`, `sgtm-seguridad`) y los datos los ponen los guiones. No
cambia la conclusión —sigue sin haber datos reales— pero **el generador de baselines no debe
buscar en `V16` unas filas que no están**.

### El único dato publicado de verdad, y por qué tampoco cierra la ventana

`stg` tiene valores normativos publicados **de verdad**: 22 parámetros y 492 filas de
depreciación el 2026-08-29 (#435), y 10 filas más el 2026-09-02 (#438), con el conjunto de 2026
en **33** filas de `parametro_tributario`. No son datos inventados: salen del corpus verificado a
doble firma.

Y aun así **no cierran la ventana**, por dos motivos que conviene tener escritos:

1. **Son reconstruibles con un comando**: `publicar-parametros.sh` + `publicar-cuadros.sh` desde
   el derivado de `docs/10-negocio/valores-normativos/publicacion/`, y el verificador comprueba
   en cada PR que cada cifra esté letra por letra en su archivo `VERIFICADO`.
2. **El conjunto de 2026 no está sellado**, y a propósito: le faltan los valores unitarios
   (H-14), el `% actualización` (D-11) y todo lo de ordenanza local (D-02b). Sellarlo sí sería
   irreversible dentro de la base —`conjunto_sellado_uq` admite uno solo por ejercicio y el
   disparador de `V9` no deja añadirle una cifra más— y hoy la única salida de un sello prematuro
   es **rehacer la base**, que es exactamente la ventana que ADR-0032 §3 dice que hay que gastar
   antes de que se cierre.

### Lo que hay en `stg` y `prod`, y por qué ya no hace falta medirlo

**La dirección lo declaró innecesario el 2026-09-03, y es coherente con ✅ D-N6**: las
municipalidades se crean todas de nuevo y no se migra ninguna, así que lo que las bases
desplegadas tengan dentro no condiciona ni el reparto de este inventario ni los baselines, que
salen del **árbol**. Lo único que había que saber es si el árbol y la versión desplegada declaran
el mismo esquema, y eso sí se midió — es lo que #675 vigila:

```
$ git ls-tree -r --name-only c755de2149… -- backend/sgtm-esquema/…/db/migration | wc -l   → 68
$ git ls-tree -r --name-only origin/main -- backend/sgtm-esquema/…/db/migration | wc -l   → 68
```

`applicationBootstrapVersion` es `c755de21…` en los dos stacks y trae las mismas 68 migraciones
que `origin/main`: **hoy no hay deriva declarada.**

Lo que sigue valiendo de `verificar-el-ambiente.sh` es otra cosa y no es un hueco de este
inventario: es la comprobación de rutina que ADR-0031 conserva, y su lugar natural es **antes de
comparar padrones** en la guarda de CI de ADR-0032 §3 —si un ambiente estuviera atrás, el «mismo
céntimo» se compararía contra otro esquema—. Con las bases reconstruibles, la salida ante esa
diferencia es rehacer, no investigar.

## 7.2 La guarda que sustituye a la migración de datos

ADR-0032 §3 lo dice y este inventario lo confirma tabla por tabla: **la comparación de padrones
deja de ser una prueba de aceptación y pasa a ser una guarda de CI**, porque el padrón se
reconstruye desde una semilla. Para que eso funcione hay que saber exactamente de qué se compone
la semilla, y es §7.3.

**El riesgo que aparece al repartir los guiones, y que hoy no existe:** los diez pasos corren
**el mismo artefacto** en perfil `batch`, «no hay un binario de carga aparte que pueda divergir
del que atiende peticiones» (ADR-0003). Con cuatro artefactos, la siembra pasa a orquestar
**tres imágenes distintas** en un orden que cruza sistemas —catastro necesita contribuyentes de
rentas antes del paso 6— y esa propiedad se pierde para el conjunto. `sembrar-demostracion.sh`
se queda en `infrastructure` justamente por eso (§3.4).

## 7.3 Qué siembra cada guión, y en qué orden

Medido en `sembrar-demostracion.sh:75-85` (la lista `PASOS`) y en el proceso `batch` que atiende
a cada uno. **Son diez pasos, no nueve** — el README de `carga-de-datos/` dice «Nueve pasos» en
su prosa y lista diez en su tabla; la prosa quedó rancia cuando #430 añadió `cargar-cajas.sh`
como paso 4 (§8).

### Antes de los diez: la implantación

| Proceso | Módulo | Escribe | Depende de |
|---|---|---|---|
| `ImplantarMunicipalidad` (`SGTM_IMPLANTACION_*`) | `sgtm-seguridad` | `municipalidad`, `modulo_sistema`, `acceso`, `grupo`, `permiso`, `usuario` (el administrador inicial, **uno solo**) | La migración, que espera consultando `flyway_schema_history` (`V21`) |

Con ✅ D-N6 este paso deja de **generar** el `id` y pasa a **recibirlo** de Keycloak, el mismo en
las cuatro bases. `municipalidad.id` deja de ser `GENERATED ALWAYS AS IDENTITY` en los cuatro
baselines.

### Los diez pasos de la siembra de demostración

| # | Guión | Proceso `batch` | Módulo hoy | Sistema | Escribe | Depende de |
|---|---|---|---|---|---|---|
| 1 | `cargar-catalogo-vial.sh` | `CargarCatalogoVial` | `sgtm-catastro` | `catastro` | `via` | — |
| 2 | `cargar-sectores.sh` | `CargarSectores` | `sgtm-catastro` | `catastro` | `sector` | — |
| 3 | `cargar-manzanas.sh` | `CargarManzanas` | `sgtm-catastro` | `catastro` | `manzana` | el sector (paso 2) |
| 4 | `cargar-cajas.sh` | `CargarCajas` / `ImportarCajas` | `sgtm-tesoreria` | **`caja`** | `area`, `caja` | — |
| 5 | `cargar-contribuyentes-demo.sh` | `CargarContribuyentesDeDemostracion` | `sgtm-contribuyentes` | **`rentas`** | `contribuyente` (16), y sus `domicilio`/`contacto` | — |
| 6 | `cargar-fichas-demo.sh` | `CargarFichasDeDemostracion` / `ImportarFichas` | `sgtm-catastro` | `catastro` ⚠ | `predio` (23), `ficha_catastral` (una versión), `titularidad` | sector, manzana, vía **y contribuyente** — **cruza de `rentas` a `catastro`** |
| 7 | `cargar-detalle-fichas-demo.sh` | `CargarDetalleDeFichasDemostracion` | `sgtm-catastro` | `catastro` | **Versiona** la ficha (23 más, total 45) con 22 `construccion`, 5 `otra_instalacion`, 5 `actividad_economica`, 3 `bien_comun` con su `participacion_comun`, 5 `tierra_rural` y 7 `colindante_rural` | la ficha del paso 6 |
| 8 | `cargar-vehiculos-demo.sh` | `CargarVehiculosDeDemostracion` | `sgtm-rentas` | `rentas` | `vehiculo` (8) | el contribuyente (paso 5) |
| 9 | `cargar-transferencias-demo.sh` | `CargarTransferenciasDeDemostracion` | `sgtm-rentas` | `rentas` ⚠ | `transferencia` (7), **y cierra/abre `titularidad`** por `catastro.GestorDeTitularidad` | el predio y el vehículo — **escribe en `catastro`** |
| 10 | `cargar-deuda-demo.sh` | `CargarDeudaDeDemostracion` | `sgtm-rentas` | `rentas` | `cuenta_corriente_asiento` (54 obligaciones) y `saldo_proyectado` | contribuyente, predio y vehículo |

**Los pasos 5 a 10 exigen `es_demostracion = true`; los pasos 1 a 4 no**, porque un catálogo
vial, un sector y una ventanilla son estructura real y ése es el mismo camino por el que entrará
el catálogo de verdad.

**Las dos ⚠ del orden son las que hacen caro repartir la siembra**: el paso 6 (catastro) necesita
que el 5 (rentas) haya terminado, y el paso 9 (rentas) **escribe en catastro**. Con una base es
un orden; con cuatro es un orden **y** dos llamadas entre sistemas dentro de la semilla.

### Fuera de la siembra: el padrón real y los valores normativos

| Guión | Proceso | Módulo hoy | Sistema | Escribe | Orden |
|---|---|---|---|---|---|
| `cargar-predios.sh` | `CargarPredios` | `sgtm-catastro` | `catastro` | `predio` desde el GeoPackage del plano, **sin ficha** (la cola de saneamiento) | Antes de las fichas. **No exige `es_demostracion`** |
| `abrir-conjunto-parametros.sh` | `AbrirConjuntoDeParametros` | `sgtm-parametros` | `normativa` | `conjunto_parametros` (abre; y **sella**, irreversible) | 1.º y 4.º de la secuencia normativa |
| `publicar-parametros.sh` | `PublicarParametros` | `sgtm-parametros` | `normativa` | `parametro_tributario` + `conjunto_parametro_detalle`, como `rol_carga_parametros` | 2.º |
| `publicar-cuadros.sh` | `PublicarCuadros` | `sgtm-parametros` | `normativa` | `valor_unitario_edificacion`, `depreciacion`, `valor_referencial_vehiculo` | 2.º |
| `cargar-arancel-vial.sh` | `CargarArancelVial` | **`sgtm-catastro`** | `catastro` (✅ D-N4) | `arancel`, contra un conjunto | 3.º, y **necesita las vías cargadas** para traducir `viaCodigo` → `via_id` |

**Y aquí está lo que decidió ✅ D-N4:** `CargarArancelVial` vive **en `sgtm-catastro`**, no en
`sgtm-parametros`. Es la única carga de valores normativos que no está en el módulo de
parámetros, y está ahí **porque el arancel se llavea por vía y su fuente es un plano**. Esa
anomalía no era un descuido: era el dato. La semilla **no cambia**, y el paso sigue necesitando
las vías cargadas antes.

**Antes del paso 2, en un ambiente que ya existía**, hace falta `secretos/asignar-claves.sh`: la
credencial de `rol_carga_parametros` la asigna `20-asignar-claves.sh` **al inicializar el motor**,
así que en un clúster creado antes de que ese rol existiera el `Secret` está y la base no sabe
nada (#435). Con cuatro bases y un motor, ese paso se multiplica por los roles de los cuatro.

---

# 8. Huecos declarados, y lo que este inventario contradice

## 8.1 Lo que no se pudo medir, y por qué

| Hueco | Por qué |
|---|---|
| **Si el reparto de las 134 opciones del menú cuadra con el reparto de sistemas** | ADR-0030 §Consecuencias exige que sigan siendo 134, ninguna desaparece y ninguna se duplica. Cruzar NEG-03 contra §1 y §2 es un trabajo propio y no cabía aquí |
| **La tabla puente `RF-001…RF-133 → sistema`** | ✅ D-N9 confirma que hay que construirla. Son 133 filas y varias caen en dos sistemas |
| **El reparto de `frontend/`** | `docs/60-frontend/` no se mueve todavía (instrucción de este trabajo), y con él tampoco el árbol de `frontend/` |
| **El reparto de `design/`** | El prototipo navegable del que se derivó la interfaz. Misma razón |
| **Cuánto pesa cada baseline** | Se sabrá al generarlos. Lo que sí se sabe es cuántas tablas lleva cada uno (§2) |
| **Este documento no está enlazado desde `docs/README.md`** | Este trabajo podía escribir **un solo archivo nuevo** y no tocar ninguno existente. La línea del índice la tiene que añadir quien integre |

## 8.2 Lo que este inventario contradice, medido

Cinco afirmaciones de documentos vigentes resultaron falsas al medirlas. **No se corrigen aquí**
—este archivo es el único que se escribe— pero hay que corregirlas antes de la fase 0, porque las
etapas siguientes las van a leer.

| Dónde dice | Qué dice | Qué se midió |
|---|---|---|
| **ADR-0029** §Consecuencias y **D-18** | «tres claves foráneas dejan de existir (`declaracion_jurada.predio_id`, `determinacion.predio_id`, **`cuenta_corriente_asiento.predio_id`**)» | La tercera **no es una clave foránea y nunca lo fue** (`V2:221-235`): es un `bigint` suelto, igual que en `saldo_proyectado`. ARQ-01 §4 regla 2 ya lo decía. Las que se pierden de verdad son **19** (§2.8) |
| **ARQ-01** §2 y §3.13 | `indicadores` «solo ve esos dos» —`cuentacorriente` y `tesoreria`— y «añadir un tercero al panel cuesta una línea y se ve en el diff» | Su `build.gradle.kts` declara **seis**: `coactiva`, `cuentacorriente`, `rentas`, `sanciones`, `tesoreria`, `valores`. La línea se añadió cuatro veces (✅ D-N3 lo confirma) |
| **ADR-0032** §Contexto | «todo lo que hay es la instalación de demostración (`V16`)» | `V16` **no siembra nada**: es un `ALTER TABLE … ADD COLUMN es_demostracion` y su `COMMENT`. La municipalidad la crea `ImplantarMunicipalidad`; los datos, los guiones (§7.1) |
| **`infra/carga-de-datos/README.md`** y **`CLAUDE.md`** | «Nueve pasos, en el único orden en que se pueden dar» | Son **diez** desde #430. La misma tabla de ese README lista diez filas, y `sembrar-demostracion.sh:75-85` tiene diez entradas. `CLAUDE.md` dice las dos cosas en párrafos distintos |
| **El enunciado de este trabajo** | «los 19 guiones de `infra/carga-de-datos/`» | Son **16** `.sh`, más 11 archivos de ejemplo y 2 `README.md` (28 archivos en total). Los 16 están en §3.4 y §7.3 |

Y dos contradicciones entre documentos vigentes que **no** se resuelven aquí porque son
decisiones, no erratas: **ARQ-01 §3.2 contra ADR-0025 §4** sobre las tablas de valuación
—resuelta por ✅ D-N4 a favor de ADR-0025— y **ADR-0005**, partido por la mitad entre
`infrastructure` y `rentas`, que sigue sin resolver (§4.3).

## 8.3 Índice de lo dudoso

Nacieron nueve casillas **⚠ DUDOSO**. **Las nueve se decidieron el 2026-09-03** y están aplicadas
a lo largo del documento. **No queda ninguna abierta de las que este inventario levantó.**

| # | Decisión | Consecuencia mecánica que la decisión no cubre, y hay que escribir al ejecutarla |
|---|---|---|
| **✅ D-N1** | El patrón de módulo Gradle es `kamayuk-<sistema>-<contexto>` | Tres módulos donde sistema y contexto coinciden dan nombre repetido —`kamayuk-catastro-catastro`, `kamayuk-rentas-rentas`, `kamayuk-caja-caja`— y **no se renombra ninguno** (§1.1) |
| **✅ D-N2** | La capa de documentos y la de auditoría son **un artefacto aparte** de `comun-plataforma` | Los dos paquetes van **juntos** en él, porque emitir audita y `RegimenDeLaInstalacionJdbc` decide si el papel sale marcado. Cómo se llame y quién lo publique sigue siendo **D-23** (§1.1) |
| **✅ D-N3** | Confirmado: `indicadores` ve **seis** contextos, no dos. ARQ-01 §2 y §3.13 hay que corregirlos | Lo que hay que arreglar no es el número: la garantía era la revisión y no el build. Y con `AvanceDeCaja` en `caja`, el invariante «ninguna cifra del panel se calcula en el panel» se vuelve más difícil, no menos (§1.1) |
| **✅ D-N4** | Las **tres** tablas de valuación **nacionales** van a `normativa`; **`arancel` se queda en `catastro`**, porque su fuente es cartográfica. Cada sistema guarda copia local de lo nacional por el snapshot sellado de ADR-0025 §1 | La FK `arancel → via` **deja de cruzar**, y `normativa` pasa a no tener ninguna que salga. En su lugar cruza `arancel → conjunto_parametros` (§1.2, §2.8) |
| **✅ D-N5** | Usuarios, grupos y permisos se definen en **Keycloak**; cada sistema guarda copia local y su guardia la consulta. **Contesta D-19** | Los permisos siguen **sin viajar en el token** (ADR-0013 intacto). `modulo_sistema`/`acceso` y `sesion` **no** se copian de Keycloak. Y aparece una ventana entre otorgar el permiso y poder usarlo (§1.2, §2.6) |
| **✅ D-N6** | El dueño del identificador de municipalidad es **Keycloak**. No se migra ninguna: se crean todas de nuevo | `municipalidad.id` deja de ser `GENERATED ALWAYS AS IDENTITY` en los cuatro baselines, y el cruce a tres bandas de #415 **se invierte**. Falta fijar la forma del identificador: `bigint` en 78 FK y en todas las políticas RLS (§2.5) |
| **✅ D-N7** | Cada sistema guarda sus respaldos en **su tabla propia** | Hay **un solo respaldo físico** y cuatro filas que lo registran (#158): el `CronJob` escribe cuatro veces, y la restauración de `V78` se verifica una vez y se anota cuatro (§2.5) |
| **✅ D-N8** | `ADR-0002` se comparte y su decisión sigue: **cuatro bases, una por sistema**, con esquema compartido y RLS dentro de cada una | Su §Consecuencias hay que ampliarla al número, y con ella los cinco hallazgos de RLS de DAT-01 §0, que pasan a tener cuatro sitios donde tropezar (§4.1) |
| **✅ D-N9** | Confirmado: la tabla puente `RF → sistema` hay que construirla | 133 filas, y varias caen en dos sistemas (RF-080, RF-110). Su criterio de terminado es el de ADR-0030 §Consecuencias: nada desaparece y nada se duplica (§5) |

### Lo que sigue abierto, y no lo levantó este inventario

Las de [GOB-02](decisiones-abiertas.md), que este documento **confirma que bloquean donde dice que
bloquean**: **D-17** (§6.8), **D-18** (§2.8, con su enunciado corregido), **D-20** (§6.2),
**D-21** (§1.2), **D-22** —la que habilita o cancela todo—, **D-23** (§1.1, ahora con un
artefacto más que colocar), **D-24** y **D-25**.

**D-19 queda contestada por ✅ D-N5** y hay que anotarlo en GOB-02: cada sistema resuelve el
acceso contra su copia local, no por HTTP contra otro. Lo que la decisión deja sin fijar —cómo se
sincroniza esa copia y qué pasa mientras está desactualizada— es el detalle de la fase 1, no una
decisión nueva.

## 8.4 Lo que el inventario deja listo para la etapa siguiente

1. **Los cuatro baselines se pueden generar ya**, y ADR-0032 §1 pide que se generen «una sola vez
   y por adelantado, junto a este inventario». §2.2–§2.7 dice qué tabla va a cuál, §2.9 qué
   migración aporta a cuál, y §2.9 al final las cuatro extensiones y los cinco hallazgos de RLS
   que van en el encabezado de cada uno. **Con las nueve decididas no queda nada que decidir
   antes**, y hay tres cambios que los baselines tienen que llevar dentro desde el primer día:
   `municipalidad.id` **sin `IDENTITY`** (✅ D-N6), el arancel llaveado por **`viaCodigo`** y sin
   el `arancel` **en `catastro`** con su FK a `via` intacta (✅ D-N4), y las siete tablas de
   seguridad **replicadas en los cuatro** en vez de
   sólo en `rentas` (✅ D-N5).
2. **La semilla está inventariada** (§7.3): diez pasos con su proceso, su módulo, lo que escribe
   y de qué depende, más los cinco de fuera de la siembra. Es lo que la guarda de CI de
   ADR-0032 §3 necesita para reconstruir y comparar.
3. **Los cruces están nombrados con archivo y línea** (§6), con su salida propuesta cada uno.
   Siete por SQL y uno transaccional; y las seis aristas que hoy sostiene el compilador y pasarán
   a sostener una prueba de contrato (§6.10).
4. **Lo que se rompe sin ruido está señalado**: la regla de ArchUnit
   `SOLO_LA_TRANSFERENCIA_ESCRIBE_FUERA_DE_FISCALIZACION` no atraviesa una frontera HTTP (§6.10),
   y el `FOR UPDATE` que sostiene la atomicidad de la cobranza deja de existir con dos bases
   (§6.2). Las dos son garantías que hoy no se pueden perder por descuido y mañana sí.
