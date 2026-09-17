# `rentas`

Contribuyentes, declaraciones juradas, determinacion, cuenta corriente, valores,
fiscalizacion, coactiva, sanciones y licencias. **Es quien decide cuanto se debe.**

> **El negocio ya esta aqui, y este README lo dice antes que nada.** Llego entero en la etapa 5 de
> [ADR-0029](https://github.com/hneyra/infrastructure/blob/main/docs/30-arquitectura/adr/ADR-0029-cuatro-sistemas-separados.md)
> ([P5A](docs/00-gobierno/P5A-copia-del-backend.md)), y entro **por encima** del **descriptor de
> infraestructura** y de las **dos barreras bloqueantes**, que se construyeron antes a proposito.
> Lo que sigue sin existir es la **imagen**: aqui no se despliega nada todavia.

## Que hay hoy, y que falta

| Pieza | Estado |
|---|---|
| `infrastructure/` — el descriptor (ADR-0031 §2) | **Existe y verifica**: `yarn verificar` en verde, sin Pulumi, sin token y sin cluster |
| `.github/workflows/` — su CI | **Existe**, con seis flujos: el descriptor, las **dos barreras bloqueantes** del backend, la documentacion, la guarda del registro, la publicacion de las dos imagenes y el del **frontend**, que sobre cada cambio de `frontend/**` corre `yarn verificar` y `yarn build`, y en un segundo trabajo el **arnes** (`yarn e2e`) en Chromium |
| `docs/30-arquitectura/adr/` | **Existe**, con 11 ADR propio(s) y su indice ⚠ ver la nota de abajo |
| `backend/` — **17 modulos**, con el negocio dentro | **Existe entero desde P5A**, y **estas cuatro cifras se midieron el 2026-09-17** —PostgreSQL **16.10** nativo (en este puesto no hay motor de contenedores: el cluster se levanto con `initdb` y `pg_ctl`), JDK 25.0.4.1, `./gradlew build verificarArquitectura verificarAislamiento verificarArranque --continue`, **`BUILD SUCCESSFUL in 15m 59s`**—: `./gradlew build` **3 325 pruebas, 0 fallos, 0 errores, 0 omitidas**, sumando los XML de `test` de los diecisiete modulos mas los de `pruebaDeArranque`. `verificarArquitectura` **264**; `verificarAislamiento` **231** —**52** del esquema y **179** del pool—; `verificarArranque` **5**. **Es una lectura de ESE dia y no un contrato**: la renueva quien vuelva a correr esos cuatro comandos, y nada la vigila. Lo que decia aqui hasta #126 —3 756, 223 y 130, «el mismo numero que `sgtm`»— describia el arbol **anterior** a P5B/P5C/P5D/P5E, cuando todavia no habian salido los valores normativos, el catastro y la ventanilla. **Y una trampa del entorno, medida y anotada porque cuesta media hora encontrarla**: con `trust` en `pg_hba.conf` la corrida da **1 fallo** —`ProvisionamientoCompartidoTest > un 28P01 nombra la causa`—, y no es un defecto del codigo sino de como se levanto el cluster: sin autenticacion por clave, una clave equivocada no produce un 28P01 y esa prueba no tiene nada que medir. Con `scram-sha-256` pasa |
| `V1__baseline.sql` — su esquema | **Esta aqui**, en `backend/kamayuk-rentas-esquema/src/main/resources/db/migration/`, y **no esta solo**: contadas sobre ese directorio son **dieciseis migraciones** —`V1`…`V14`, `V17` y `V18`; la numeracion **salta la 15 y la 16**, y ningun archivo de este arbol las tuvo nunca—. `V1` nace con **132** tablas; `V2`, `V6` y `V7` **retiran 31** —las que se fueron a `normativa`, a `catastro` y a `caja`— y `V3`, `V4`, `V5`, `V8`, `V12` y `V18` **traen 14**. **Medido sobre una base migrada de cero con el propio `migrar`** —no contando prosa—: **115 tablas vivas**, de las cuales **10 son particiones** de cinco tablas particionadas —`auditoria`, `cuenta_corriente_asiento`, `determinacion`, `determinacion_arbitrio` y `determinacion_predio_detalle`, cada una con su tramo de 2026 y el de 2027—, o sea **105 tablas logicas**, mas `flyway_schema_history`. Las dos cuentas cuadran por caminos distintos: 132 − 31 + 14 = 115 sobre el texto, y 115 relaciones en `pg_class` sobre la base. Lo que decia aqui hasta #126 —«una sola migracion, 132 tablas»— era el estado del baseline antes de que `V2`, `V6` y `V7` existieran, y era la mas enganosa de las cuatro cifras viejas: afirmaba que hay **una** migracion cuando tres de ellas existen precisamente para retirar lo que ya no es de este sistema |
| Su frontend (`rentas-web`, ADR-0030 §1) | **Existe, y es la interfaz de RentasV8: las 40 pantallas estan dibujadas.** Vite 7 + React 19 + TypeScript 5.9 en `frontend/`, con el codigo en `frontend/src/`. **Leido el 2026-09-13, y es una lectura de ese dia y no un contrato**: caduca en el siguiente PR que toque `frontend/`, y la renueva —con los tres comandos que se nombran aqui mismo— la misma pasada que renueva los contadores de `CLAUDE.md` al mezclar. No la vigila una guarda, y el motivo esta medido en la fila de #135 del registro: de los 20 commits de un dia sobre `frontend/`, 19 tocan un archivo de prueba, asi que saldria roja en el 95 % de los PR y siempre sobre codigo bueno. `yarn verificar` —lint, tipos, i18n y pruebas— en verde, **567 pruebas en 38 archivos**, 0 fallos; `yarn e2e` recorre **67 caminos en Chromium**, en 6 archivos, contra el bundle construido, 0 fallos; `yarn build` produce **824,86 kB de JS y 35,03 kB de CSS**. Sus ocho reglas son **nueve prohibiciones de ESLint con su muestra que las viola**, y sus tokens salen del artboard **V8** —una prueba compara sus hex contra `frontend/diseno/RentasV8.dc.html` y su hoja de tokens, que viajan vendorizados, y `pantallas-del-artboard.test.ts` compara las 40 pantallas **campo por campo**, en 96 pruebas: 45 bloques, 302 campos y 31 tablas—. El **proxy de datos** de F-4 **ya no existe**: salio en #90 con las pantallas de V6 contra las que contestaba, y una guarda impide que la V6 vuelva al arbol. Hoy piden datos de verdad **dos** hojas —`panel` y `coa-panel`— y las otras **38 dicen por que no**, sin ensenar un cero |
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
