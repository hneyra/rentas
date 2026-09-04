# Carga de datos de `rentas`

Los **cuatro** archivos de `ejemplos/` son la parte de `rentas` de la siembra de la municipalidad de
demostración: el padrón de contribuyentes, el padrón vehicular, las transferencias y el saldo inicial
del libro. Los otros seis se quedaron donde vive su sistema: `vias.csv`, `sectores.csv`,
`manzanas.csv`, `fichas.csv` y `detalle-de-fichas.csv` en `catastro`, y `cajas.csv` en `caja`.

**Cada archivo está en un solo repositorio.** Hasta C-6 este directorio conservaba una copia byte a
byte de los seis ajenos —herencia de P5A, cuando todo vino aquí— y nada impedía que divergieran: la
copia que alguien edita no tiene por qué ser la que el cargador lee. `ArchivosDeEjemploDeRentasTest`
lee ahora `fichas.csv` del clon hermano de `catastro`, igual que `ArchivosDeEjemploTest` de
`catastro` lee `contribuyentes.csv` de aquí. **Este módulo no compila sus pruebas sin `catastro`
clonado al lado**, y si no está, la prueba falla nombrando el `git clone`; no se salta.

Todos los procesos corren el **mismo artefacto** que la aplicación, en el perfil `batch`, como un Job
de un solo uso (ADR-0003).

## Los cuatro pasos de `rentas`, y su sitio en la secuencia

| # global | Guion | Archivo | Necesita antes |
|---|---|---|---|
| 5 | `cargar-contribuyentes-demo.sh` | `ejemplos/contribuyentes.csv` | — |
| 8 | `cargar-vehiculos-demo.sh` | `ejemplos/vehiculos.csv` | el padrón (5) |
| 9 | `cargar-transferencias-demo.sh` | `ejemplos/transferencias.csv` | los predios de `catastro` (6) y los vehículos (8) |
| 10 | `cargar-deuda-demo.sh` | `ejemplos/deuda.csv` | 5, 6, 8 y 9 |

Los cuatro **exigen `municipalidad.es_demostracion = true`**, comprobado contra **esta** base por
cada proceso —no por el guion— antes de leer una sola fila. Un `--municipalidad-id` equivocado en un
dígito no siembra personas que no existen en el padrón de una municipalidad que ya opera, y aquí no
se borra nada (RNF-051).

**El orden completo no está aquí**, y es a propósito: los diez pasos con su dueño viven en
[`infrastructure/infra/carga-de-datos/siembra/pasos.tsv`](https://github.com/hneyra/infrastructure/blob/main/infra/carga-de-datos/siembra/pasos.tsv),
que es el único sitio desde el que se ven los tres sistemas a la vez (ADR-0031, y el mismo argumento
con que C-2 puso allí la guarda de extensiones).

```bash
../../../infrastructure/infra/carga-de-datos/siembra/sembrar-demostracion.sh \
    --ambiente stg --municipalidad-id 4 \
    --url-catastro postgresql://… --url-rentas postgresql://… --url-caja postgresql://…
```

## Los pasos 9 y 10 cruzan la frontera, y hoy no llegan

`transferencias.csv` transfiere predios y vehículos; `deuda.csv` resuelve la unidad de cada
obligación **a su fecha valor**. Las dos cosas necesitan preguntar por los predios de `catastro`, y
una de ellas —cerrar una cuota de titularidad y abrir otra— necesita **escribir** allí:

- **La lectura** sale por HTTP (`ClienteHttpDeCatastro`). En una corrida sin usuario delante no hay
  petición de la que sacar el token, así que la llamada sale sin credencial y el destino la rechaza:
  es el hueco 6 de P5C, y es deliberado.
- **La escritura** no tiene ruta y no la va a tener así: `GestorDeTitularidad.transferir` lanza
  `EscrituraSinTransaccionCompartida` porque `RegistrarTransferencia.transferirPredio` cierra la
  cuota, inserta la fila de `transferencia` y escribe su auditoría **en una sola transacción**.
  Servida por HTTP, un fallo posterior dejaría el predio cambiado de dueño **sin el acto que lo
  justifica**. Está escrito en `TitularidadHttp` con su medida (C-5, hueco 2 de P5C).

Lo que C-6 cambia no es eso —sigue abierto— sino que **deje de ser silencioso**: la comprobación de
`infrastructure` cuenta lo que la tabla tiene después de cada paso y se para en rojo diciendo cuántas
faltan, en vez de dejar pasar un «0 nuevas, N rechazadas» con código 0.

## Qué escenario cubren estos cuatro archivos

**Padrón.** 16 contribuyentes con uno, dos, tres y cuatro predios; el de cuatro existe porque la base
del predial es **por contribuyente y no por predio** (NEG-05 §1), y con un predio por persona esa
distinción no se puede ni mirar.

**Padrón vehicular.** Ocho vehículos, con los años de inscripción repartidos a propósito: `ZTR-101` y
`ZQU-880` quedan **fuera** de los tres ejercicios de afectación en 2026 y los demás dentro. La
afectación no es una columna —se deduce de `Vehiculo.afectoEn`—, así que sin esa mezcla no se puede
ver funcionando.

**Transferencias.** `fichas.csv` inscribe cada predio con **un** titular, y una segunda fila del
mismo predio se rechazaría. La copropiedad no se declara: se **produce**, con una transferencia
parcial, que es como ocurre en la realidad.

| Caso | Dónde |
|---|---|
| Venta **parcial del 40 %** → dos cuotas vivas, 40 % y 60 % | `C-000014` → `C-000010` |
| **Cadena de dos ventas** sobre el mismo predio → el titular depende de la fecha por la que se pregunte | `C-000013` → `C-000009` (feb) → `C-000010` (jun) |
| Copropiedad **al 50 %** | `C-000015` → `C-000009` |
| **Anticipo de legítima** del 25 % sobre predio rústico en sucesión | `C-000008` → `C-000004` |
| Transferencia de **vehículo** | `ZTR-101` y `ZKS-916` |

La cadena es la que da algo que mirar a la regla 9: preguntar por marzo tiene que devolver a
`C-000009` y no al último dueño.

**Deuda.** 54 obligaciones: predial por contribuyente en cuatro cuotas, arbitrios por predio,
vehicular por vehículo, y un contribuyente con deuda en **dos ejercicios**.

## El monto de `deuda.csv` no es una cifra normativa, y conviene decir por qué

No lo calcula nadie: entra como dato, igual que entraría el saldo de la base anterior el día que se
cierre D-04. No es una determinación —no hay tramo, ni alícuota, ni UIT de por medio— y por eso no
depende de D-02a ni de D-11. Es el mismo acto que la pantalla «Alta de deuda» publica (RF-043), donde
el importe lo teclea quien atiende y el sistema no lo discute. Lo que esas filas **no** hacen: no
emiten ninguna resolución de determinación, no escriben una fila de `determinacion` y no reclaman
haber aplicado ninguna regla.

**Ninguna otra cifra normativa entra por aquí.** Ni aranceles, ni valores unitarios, ni tramos, ni
valores referenciales de vehículos: esas se publican desde el corpus verificado a doble firma de
`normativa`, o no entran.
