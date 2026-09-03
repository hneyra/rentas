# ADR-0019 — La porción sin titular identificado no se determina a nadie

| Campo | Valor |
|---|---|
| Estado | Aceptado |
| Fecha | 2026-08-28 |
| Decide | Dirección del proyecto |
| Cierra | **D-12** (GOB-02) |
| Referencias | `../srtm` NEG-05 §RT-011 (la base por contribuyente, ponderada por `% propiedad`), issue [#188](https://github.com/hneyra/sgtm/issues/188) |

## Decisión

Cuando la titularidad registrada de un predio no llega al 100 %, **se determina solo la porción
con titular identificado**. Cada contribuyente aporta a su base imponible el autovalúo del predio
ponderado por su `% propiedad` —como ya hace RT-011—, y la porción restante **no se determina a
nadie**: no se le inventa un deudor.

El esquema no cambia: ya validaba «la suma de cuotas no excede 100» —igual que el SRTM del MEF— y
ya admitía que no llegue a 100. Lo que esta decisión fija es la semántica del hueco.

## Por qué

Una determinación es deuda contra una persona. La porción sin titular identificado no tiene
persona: determinarla exige inventar una —un «titular desconocido» con deuda incobrable que
ensucia el padrón y las cifras de emisión— o cargársela a quien sí está identificado, que es
cobrarle una porción que el registro dice que no es suya. Las dos producen un importe plausible y
equivocado, que es exactamente la clase de error que este proyecto trata como el peor.

**El hueco es un dato, no un descuadre.** Que la suma de lo determinado sobre un predio sea menor
que su autovalúo es información verdadera sobre el padrón: falta identificar a alguien. Eso es
trabajo de **fiscalización** —completar la titularidad y, completada, determinar al titular nuevo
desde que la ley lo permita—, no del cálculo.

## Consecuencias

- **La emisión no espera a que el padrón esté perfecto.** Un predio con 60 % de titularidad
  identificada emite ese 60 % hoy, en vez de retener el predio entero.
- **La porción no determinada debe quedar visible, nunca silenciosa**: es señal de padrón
  incompleto y candidata natural a un listado de fiscalización (predios con titularidad < 100 %).
  Qué pantalla la enseña se decidirá con esa pantalla; lo que este ADR fija es que ninguna cifra
  agregada la disimule sumándola a nadie.
- **Cuando la titularidad se complete, la porción nueva se determina hacia adelante** por el
  camino normal —fiscalización y sus actos—, no reescribiendo determinaciones emitidas.

## Alternativas descartadas

- **Determinar el 100 % repartido entre los titulares identificados, a prorrata.** Cobra porciones
  que el registro dice que no son suyas; y al aparecer el titular que faltaba habría que anular y
  devolver.
- **Un contribuyente ficticio «titular no identificado».** Deuda que nadie puede pagar, emitida a
  nombre de nadie: infla la emisión y la morosidad a la vez y convierte el padrón en el lugar
  donde se esconde el problema en vez de verse.
- **No emitir el predio hasta completar el 100 %.** Deja de cobrar lo identificado por lo que no
  lo está, y castiga al condómino que sí declaró.
