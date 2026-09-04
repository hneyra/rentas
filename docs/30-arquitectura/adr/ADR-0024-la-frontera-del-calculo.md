# ADR-0024 — La frontera del calculo: catastro valoriza el predio, rentas determina la obligación

| Campo | Valor |
|---|---|
| Estado | **Aceptado** |
| Fecha | 2026-09-03 |
| Decide | Dirección del proyecto |
| Depende de | [ADR-0029](https://github.com/hneyra/infrastructure/blob/main/docs/30-arquitectura/adr/ADR-0029-cuatro-sistemas-separados.md) |
| Abre | D-21 (el `% propiedad`) |


> Aceptado el 2026-09-04 por la direccion del proyecto, al contestar **D-22**: el producto lo opera un equipo central, no cada municipalidad. Ver [`D-22`](https://github.com/hneyra/infrastructure/blob/main/docs/00-gobierno/D-22-quien-opera-cuatro-despliegues.md) en `infrastructure`.

## Contexto

«El Catastro Fiscal es dueño del calculo del Valor Fiscal» es cierto y es ambiguo, porque
**«autovaluo» nombra dos cosas distintas** y el enunciado corriente no las separa:

- el **valor de un predio**: terreno + construcción + obras complementarias;
- la **base imponible de un contribuyente**: la suma de sus predios ponderada por el `% propiedad`,
  sobre la que se aplican los tramos progresivos.

La regla que `../srtm` NEG-05 §1 marca como crítica decide cual es cual, y este proyecto la tiene
copiada como innegociable en `CLAUDE.md`:

> El impuesto predial **no se calcula por predio, sino por contribuyente**. La base imponible es el
> conjunto de sus predios en la jurisdicción, y sobre ese total se aplican los tramos progresivos.
> Confundir esto produce un **error sistematico a la baja en todo el padrón**.

Catastro **puede** calcular el valor de un predio: es una función de datos que posee —área, arancel,
material, antiguedad, estado— y de tablas de valuación. Catastro **no puede** calcular el impuesto:
para eso hace falta saber todos los predios de una persona, su condición de pensionista y sus
deducciones, que son de Rentas.

Y el motor que ya existe en `sgtm-parametros` lo dice en su propia estructura, sin que nadie lo
diseñara para esto: tiene `ReglaTributaria` —«el calculo de una partida: un predio, un vehículo»— y
`ReglaDeAgregacion` —«una regla que no opera sobre un predio sino sobre todos los del
contribuyente»—. El javadoc de `MotorDeReglas` lo llama por su nombre: «El calculo por contribuyente
son dos fases, en el orden que NEG-05 §1 fija».

## Decisión

**La frontera entre `catastro` y `rentas` es la frontera entre las dos fases del motor**, y lo que
la cruza es un concepto del grafo con su importe, no una fila de una tabla.

```
catastro  ─ fase 1 ─►  VALOR_DEL_PREDIO  ─► fase 2 ─  rentas
```

1. **Cada regla declara su ambito**, y es dato del catálogo, no convención:

   | Ámbito | Quien la ejecuta | Qué produce |
   |---|---|---|
   | `VALUACION` | `catastro` | `VALOR_TERRENO`, `VALOR_CONSTRUCCION`, `VALOR_OBRAS`, `VALOR_DEL_PREDIO` |
   | `OBLIGACION` | `rentas` | ponderación, agregación, tramos, deducciones, mínimo, cuotas |

   Una regla sin ambito no compila. Una regla de `OBLIGACION` invocada desde `catastro` falla **al
   arrancar**, no en mitad de una corrida de padrón. Es una verificación, no una intención.

2. **El `% de propiedad` lo aplica `rentas`**, aunque la cuota de titularidad sea dato de `catastro`
   y aunque hoy el javadoc de `ReglaDeAgregacion` diga que se aplica «antes, en el grafo por
   partida». El motivo es la frontera de datos, no la del calculo: catastro pública *cuánto vale el
   predio y quienes lo tienen*; decir *cuánto le toca a cada uno* ya es una base imponible, y una
   base imponible es de quien determina.

   Esto **cambia el ambito de varias `RT-xxx`** y por eso queda como **D-21**: no se implementa nada
   hasta que la lista este revisada contra NEG-05 §1 fila a fila.

3. **La misma libreria en los dos lados.** `normativa-reglas` ([ADR-0025](https://github.com/hneyra/normativa/blob/main/docs/30-arquitectura/adr/ADR-0025-normativa-servicio-y-libreria.md))
   trae el motor, las reglas y el redondeo de [ADR-0018](https://github.com/hneyra/normativa/blob/main/docs/30-arquitectura/adr/ADR-0018-el-redondeo-decidido.md). No hay
   dos implementaciones del `+5 %` ni dos interpretaciones del `HALF_UP`: hay un artefacto con
   versión, fijado por los dos.

4. **La salida de la fase 1 se sella.** No es un valor consultable que cambie: es un hecho
   inmutable con la identidad de todos sus insumos. Lo decide
   [ADR-0027](https://github.com/hneyra/catastro/blob/main/docs/30-arquitectura/adr/ADR-0027-la-valuacion-es-un-hecho-sellado.md).

## Consecuencias

- **El corte es verificable.** Si `catastro` alguna vez necesita una regla de obligación, el build lo
  dice el día que alguien lo intenta. Sin el ambito, se descubriria tres años después, cuando dos
  padrónes ya divergieron y nadie recuerda cual es el bueno.
- **`catastro` no ve ni una deducción.** No sabe quién es pensionista, no sabe qué es la Amazonía, no
  sabe cuantos predios tiene una persona. Es lo que permite que su API se abra a la gerencia de
  desarrollo urbano sin abrir con ella el padrón tributario.
- **Un predio con tres copropietarios se valoriza una vez.** La ponderación ocurre después, en
  rentas, y el `34,5 %` del padrón de Catacaos sin titularidad vigente (#586) sigue teniendo
  valuación aunque no tenga a quien determinarsele — que es lo que
  [ADR-0019](ADR-0019-titularidad-parcial.md) ya decidió.
- **D-11 sigue bloqueando igual.** El `% actualización` sin fuente identificada multiplica importes
  en la fase 1; partir el calculo no lo desbloquea ni un poco.

## Lo descartado, y por qué

- **Qué `catastro` calcule el impuesto entero.** Es la lectura literal de «el catastro es dueño del
  autovaluo», y produce el error sistematico a la baja de NEG-05 §1 si se hace predio por predio, o
  obliga a que catastro lea el padrón de contribuyentes si se hace bien. Las dos son peores que la
  frontera.
- **Qué `rentas` calcule también la valuación, leyendo la ficha por API.** Convierte la ficha en un
  contrato de decenas de campos —construcciones por piso, categorías constructivas, obras
  complementarias— y deja a catastro sin razón para existir como sistema. Y ata la emisión a que
  catastro este arriba.
- **Duplicar el motor en los dos repositorios.** Dos implementaciones del redondeo es dos
  determinaciones distintas del mismo predio, y la que este mal se descubre en una reclamación.
- **Marcar el ambito por convención de paquete** en vez de como dato de la regla. Una convención no
  falla el build; y la regla que alguien coloque en el paquete equivocado se veria bien.
