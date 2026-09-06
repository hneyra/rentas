# `rentas`

Contribuyentes, declaraciones juradas, determinacion, cuenta corriente, valores,
fiscalizacion, coactiva, sanciones y licencias. **Es quien decide cuanto se debe.**

> **Todavia no hay una sola linea de codigo de negocio, y este README lo dice antes que nada.**
> Lo que hay es el **descriptor de infraestructura** —como se desplegaria este sistema el dia que
> exista— y las **dos barreras bloqueantes**, que se construyeron antes que el negocio a proposito.
> El negocio llega en la etapa 5 de [ADR-0029](https://github.com/hneyra/infrastructure/blob/main/docs/30-arquitectura/adr/ADR-0029-cuatro-sistemas-separados.md).

## Que hay hoy, y que falta

| Pieza | Estado |
|---|---|
| `infrastructure/` — el descriptor (ADR-0031 §2) | **Existe y verifica**: `yarn verificar` en verde, sin Pulumi, sin token y sin cluster |
| `.github/workflows/` — su CI | **Existe**, con seis flujos: el descriptor, las **dos barreras bloqueantes** del backend, la documentacion, la guarda del registro, la publicacion de las dos imagenes y el del **frontend**, que corre `yarn verificar` y `yarn build` sobre cada cambio de `frontend/**` |
| `docs/30-arquitectura/adr/` | **Existe**, con 11 ADR propio(s) y su indice ⚠ ver la nota de abajo |
| `backend/` — **17 modulos**, con el negocio dentro | **Existe entero desde P5A.** `./gradlew build` en verde: **3 756 pruebas**, 0 fallos, el mismo numero que `sgtm`. `verificarAislamiento` 223 y `verificarArquitectura` 130 |
| `V1__baseline.sql` — su esquema | **Esta aqui**, en `backend/kamayuk-rentas-esquema/src/main/resources/db/migration/`. Una sola migracion, 132 tablas |
| Su frontend (`rentas-web`, ADR-0030 §1) | **Existe el andamiaje, el vocabulario visual y sus datos de prototipo; ninguna pantalla todavia** (F-1, F-2, F-4). Vite 7 + React 19 + TypeScript 5.9 en `frontend/`, con el codigo en `frontend/src/`. `yarn verificar` en verde: **346 pruebas**, 0 fallos. Sus ocho reglas son **nueve prohibiciones de ESLint con su muestra que las viola**, sus tokens salen del artboard V6 —una prueba compara sus hex contra `frontend/diseno/RentasV6.dc.html`, que viaja vendorizado— y el **proxy de datos** de F-4 contesta trece operaciones del contrato detras de `VITE_KAMAYUK_PROXY_DE_DATOS` |
| La imagen `ghcr.io/hneyra/kamayuk-rentas` | **NO existe.** El `Deployment` del descriptor la nombra igual: es correcto, y en esta etapa no se despliega nada |

## Por donde entrar

- **Montar el entorno y ejecutarlo**: [`docs/D0-desarrollo/README.md`](docs/D0-desarrollo/README.md).
- **Contexto para agentes**, con las diez reglas y lo que este repositorio no hace:
  [`CLAUDE.md`](CLAUDE.md).

## El descriptor

```bash
cd infrastructure
yarn install
yarn verificar          # lint, tipos y pruebas. Sin Pulumi, sin token y sin cluster
```

Declara **su base y sus roles**, **su Deployment** **con sus dos perfiles, `web` y `batch`**, **su Job de migracion**, **sus
rutas bajo su prefijo `rentas/`**, **su egreso**, sus alertas, su panel y su inventario de claves.
No declara la etiqueta de su imagen: la pone `infrastructure`, y es lo que hace que una
liberacion normal no sea un `pulumi up` (ADR-0011 §5).

**Su egreso, que es su grafo de dependencias:**

```
rentas  ──▶  caja, catastro, normativa
```

Llama a `catastro` por la valuacion sellada del ejercicio (ADR-0027), a `normativa` por el
conjunto sellado —una vez por corrida, no una vez por predio (ADR-0025 §1)— y a `caja` por las
ordenes de cobro que emite.

**`ADR-0003` sigue siendo cierto DENTRO de este sistema**: un artefacto, dos perfiles. Lo que
ADR-0029 reemplaza es el monolito de los doce contextos, no la forma de este.

## Lo que este repositorio NO decide

- **La etiqueta de su imagen.** La fija `infrastructure` al componer.
- **Su namespace ni sus `PriorityClass`.** Son de alcance de cluster.
- **Como se sella un valor normativo.** Eso es de `normativa`; aqui se consume un conjunto ya
  sellado.
- **Si su descriptor se aplica.** `infrastructure` lo audita con las mismas reglas que audita los
  suyos y **se niega** si incumple: una ruta fuera del prefijo, un `Deployment` sin limites, un
  `Secret` en claro o privilegios sobre la base de otro sistema.

## De donde viene

Extraido de [`sgtm`](https://github.com/hneyra/sgtm/tree/migracion-a-microservicios), que **no se borra**: es el archivo historico y la unica copia con
`git log`. El inventario del corte —que tabla va a que repositorio, y por que— esta en
[GOB-05](https://github.com/hneyra/sgtm/blob/migracion-a-microservicios/docs/00-gobierno/inventario-del-corte.md).
