# Decisiones de arquitectura (ADR)

Las decisiones de **Rentas**: la determinacion, el libro de asientos, los valores, la fiscalizacion, la coactiva, las sanciones y las licencias.

Aloja tambien las **tres decisiones de frontera que toma rentas** —la conciliacion (0015), la frontera del calculo (0024) y el camino del dinero (0026)—: viven donde vive la decision, y catastro y caja las enlazan.

Un ADR registra una decision con su contexto y sus consecuencias. **No se editan una vez
aceptados**: si una decision cambia, se escribe otro ADR que declare obsoleto al anterior. El
historial de por que se hizo algo vale mas que la coherencia del documento.

## Los de este repositorio

| # | Decision | Estado |
|---|---|---|
| [0003](ADR-0003-monolito-modular.md) | Monolito modular con Spring Modulith | Aceptado |
| [0006](ADR-0006-cuenta-corriente-libro-de-asientos.md) | La cuenta corriente es un libro de asientos inmutable | Aceptado |
| [0013](ADR-0013-permisos-de-la-sesion.md) | La interfaz aprende sus permisos del backend, no del token | Aceptado |
| [0014](ADR-0014-navegacion-centrada-en-la-atencion.md) | Navegación centrada en la atención: la persona como inicio, los módulos detrás de un lanzador | Aceptado |
| [0015](ADR-0015-conciliacion-catastro-rentas.md) | La conciliación catastro↔rentas: un derivado que publica rentas, no un estado que guarda catastro | Aceptado · 2026-08-28 |
| [0016](ADR-0016-el-inicio-pregunta-la-ficha-compone.md) | El inicio pregunta y la ficha compone: las fases 3–5 de ADR-0014, sin el agregador que no hacía falta | Aceptado · 2026-08-28 |
| [0019](ADR-0019-titularidad-parcial.md) | La porción sin titular identificado no se determina a nadie | Aceptado |
| [0020](ADR-0020-la-sesion-del-ciudadano.md) | El ciudadano tiene sesión propia, y su consulta recorre el registro de municipalidades | Aceptada |
| [0023](ADR-0023-la-muestra-se-sortea.md) | La muestra de fiscalización se sortea; la detección aporta sus filtros | Aceptado |
| [0024](ADR-0024-la-frontera-del-calculo.md) | La frontera del calculo: catastro valoriza el predio, rentas determina la obligación | Propuesto |
| [0026](ADR-0026-el-camino-del-dinero.md) | El camino del dinero: dos transacciones, un outbox, y la imputación en rentas | Propuesto |

## Los que enlaza, y no copia

Viven en el repositorio de quien toma la decision. **Aqui solo esta el enlace**: una
copia seria un segundo ADR el dia que alguien edite uno de los dos.

| # | Decision | Vive en | Por que le importa a este repositorio |
|---|---|---|---|
| [0001](https://github.com/hneyra/infrastructure/blob/main/docs/30-arquitectura/adr/ADR-0001-plataforma-backend.md) | Plataforma del backend: Spring Boot 4 sobre Java 25 | `infrastructure` | la plataforma del backend que corre |
| [0002](https://github.com/hneyra/infrastructure/blob/main/docs/30-arquitectura/adr/ADR-0002-estrategia-multi-tenant.md) | Esquema compartido con Row Level Security | `infrastructure` | el aislamiento, que es el riesgo numero uno |
| [0004](https://github.com/hneyra/infrastructure/blob/main/docs/30-arquitectura/adr/ADR-0004-almacenamiento-de-datos.md) | PostgreSQL, con particionado por ejercicio | `infrastructure` | el motor y su particionado por ejercicio |
| [0005](https://github.com/hneyra/infrastructure/blob/main/docs/30-arquitectura/adr/ADR-0005-identidad-y-acceso.md) | OIDC para autenticar; el modelo de permisos del manual para autorizar | `infrastructure` | quien autentica; su modelo de permisos lo conserva ADR-0013 |
| [0007](https://github.com/hneyra/normativa/blob/main/docs/30-arquitectura/adr/ADR-0007-parametros-versionados.md) | Parámetros tributarios versionados y sellados por ejercicio | `normativa` | el conjunto sellado con que determina |
| [0008](https://github.com/hneyra/infrastructure/blob/main/docs/30-arquitectura/adr/ADR-0008-auditoria-heredada-del-manual.md) | Auditoría con observación obligatoria, como en el sistema original | `infrastructure` | la observacion obligatoria (regla 10) |
| [0018](https://github.com/hneyra/normativa/blob/main/docs/30-arquitectura/adr/ADR-0018-el-redondeo-decidido.md) | El redondeo, decidido: escala ratificada, `HALF_UP`, y ningún SRTM que imitar | `normativa` | el redondeo, que aplica al determinar |
| [0027](https://github.com/hneyra/catastro/blob/main/docs/30-arquitectura/adr/ADR-0027-la-valuacion-es-un-hecho-sellado.md) | La valuación es un hecho sellado del ejercicio, no un estado del predio | `catastro` | la valuacion que recibe de catastro y con la que determina |
| [0028](https://github.com/hneyra/infrastructure/blob/main/docs/30-arquitectura/adr/ADR-0028-el-tenant-no-cruza-por-http.md) | El contexto de municipalidad no cruza por HTTP: token delegado, jamás una cabecera | `infrastructure` | el tenant no cruza por HTTP |
| [0029](https://github.com/hneyra/infrastructure/blob/main/docs/30-arquitectura/adr/ADR-0029-cuatro-sistemas-separados.md) | Cuatro sistemas separados: `catastro`, `rentas`, `normativa` y `caja` | `infrastructure` | por que hay cuatro sistemas |
| [0030](https://github.com/hneyra/infrastructure/blob/main/docs/30-arquitectura/adr/ADR-0030-cuatro-interfaces-una-sesion.md) | Cuatro interfaces, una sesión, y las librerias comunes que impiden que sean cuatro productos | `infrastructure` | su frontend, y el portal del ciudadano |
| [0032](https://github.com/hneyra/infrastructure/blob/main/docs/30-arquitectura/adr/ADR-0032-el-esquema-nace-en-baseline.md) | El esquema de cada sistema nace en un baseline; la historia se queda en `sgtm` | `infrastructure` | su baseline |

El reparto entero, con su criterio, esta en [GOB-05 §4](https://github.com/hneyra/sgtm/blob/migracion-a-microservicios/docs/00-gobierno/inventario-del-corte.md).

Decisiones **pendientes**: [GOB-02](https://github.com/hneyra/sgtm/blob/migracion-a-microservicios/docs/00-gobierno/decisiones-abiertas.md).

## Plantilla

```markdown
# ADR-000X — Titulo

**Estado:** Propuesto | Aceptado | Obsoleto (reemplazado por ADR-000Y)
**Fecha:** AAAA-MM-DD

## Contexto
## Decision
## Consecuencias
## Alternativas consideradas
```

El estado tambien puede ir como fila de una tabla de metadatos (`| Estado | Aceptado |`), que es
la forma de ADR-0017 en adelante; lo que no cambia es el vocabulario: **Propuesto**, **Aceptado**
u **Obsoleto**, siempre con esa letra.

## La numeracion NO se reinicia

El ADR nuevo de este repositorio es el **0033**, no el 0001. Los treinta y dos existen y estan
repartidos; empezar de nuevo daria dos `ADR-0001` distintos en el mismo producto, y el dia que
alguien cite «ADR-0004» habria que preguntar de cual habla.
