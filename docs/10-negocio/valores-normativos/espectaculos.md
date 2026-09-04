# Espectáculos públicos no deportivos: alícuotas por tipo

| Campo | Valor |
|---|---|
| Norma | TUO de la Ley de Tributación Municipal (D.S. N.° 156-2004-EF); artículo 57 con el texto sustituido por la Ley N.° 29168, Ley que promueve el desarrollo de espectáculos públicos no deportivos |
| Artículo | 54 a 59 (57 según el texto sustituido por la Ley N.° 29168) |
| Publicada | 2004-11-15, El Peruano (TUO); art. 57: Ley N.° 29168 publicada 2007-12-20, El Peruano |
| Ejercicios que rige | 2007– |
| Filas de NEG-02 §2 | 17 |
| Transcribió | JNA, 2026-08-24 |
| Verificó | HNA, 2026-08-25 |
| Estado | VERIFICADO |

## 1. La tabla tal como está en la norma

**Sin reordenar, sin convertir unidades, sin corregir un encabezado.**

| Elemento | Artículo | Contenido |
|---|---|---|
| Hecho gravado | 54 | El monto que se abona por presenciar o participar en espectáculos públicos no deportivos, en locales y parques cerrados |
| Sujetos pasivos | 55 | Las personas que adquieren entradas para asistir a los espectáculos. Los organizadores son responsables tributarios en calidad de agentes perceptores, y el conductor del local donde se realiza el espectáculo es responsable solidario |
| Base imponible | 56 | El valor de entrada para presenciar o participar en el espectáculo, entendiéndose que no incluye el propio impuesto; cuando el valor de la entrada incluye otros servicios, la base imponible no podrá ser inferior al 50% de ese valor total |

**Tasas del impuesto (artículo 57), texto sustituido por la Ley N.° 29168.** Confirmado en al menos
tres fuentes independientes que coinciden entre sí (una reproducción del texto consolidado de la
norma, un análisis de un despacho tributario sobre la propia Ley 29168, y jurisprudencia del
Tribunal Constitucional sobre espectáculos taurinos):

| Tipo de espectáculo | Tasa |
|---|---|
| Espectáculos taurinos, siempre que el valor promedio ponderado de la entrada sea superior al 0.5% de la UIT | 10% |
| Espectáculos taurinos, en los demás casos (valor promedio ponderado de la entrada no superior al 0.5% de la UIT) | 5% |
| Carreras de caballos | 15% |
| Espectáculos cinematográficos | 10% |
| Conciertos de música en general | 0% |
| Espectáculos de folclor nacional, teatro, zarzuela, conciertos de música clásica, ópera, opereta, ballet y circo | 0% |
| Otros espectáculos públicos | 10% |

> El borrador previo de este archivo recordaba de memoria un 15% para "otros espectáculos". Se
> verificó explícitamente ese dato contra el texto del artículo 57 modificado por la Ley 29168 en
> tres fuentes independientes (transcripción del texto legal, un análisis jurídico de la propia Ley
> 29168, y una nota sobre jurisprudencia del TC que cita el mismo artículo) y **las tres coinciden
> en que "otros espectáculos" tributa 10%, no 15%**. El 15% sí es la tasa correcta, pero para
> **carreras de caballos**, no para "otros". Se corrige aquí el borrador con esa base.

| Elemento | Artículo | Contenido |
|---|---|---|
| Obligaciones de los agentes perceptores | 58 | Deben presentar una declaración jurada previa que detalle el espectáculo y el monto de las entradas, y depositar una garantía, en el caso de espectáculos temporales o eventuales, equivalente al 15% del impuesto calculado sobre la capacidad o aforo del local |
| Plazo de pago | 59 | Espectáculos permanentes: el segundo día hábil de cada semana, por los ingresos de la semana anterior. Espectáculos temporales o eventuales: el segundo día hábil siguiente a su realización. La recaudación y administración corresponde a la Municipalidad Distrital donde se realiza el espectáculo |

## 2. Cómo entra al sistema

| Qué | Dónde |
|---|---|
| Tipo | `parametro_tributario` (tipo `ESPECTACULO_ALICUOTA`, una fila por tipo de espectáculo de la tabla del artículo 57) |
| Clave | `ESPECTACULO_ALICUOTA-<código de tipo>` (p. ej. `TAURINO-SUPERIOR-0.5-UIT`, `TAURINO-RESTO`, `CARRERAS-CABALLOS`, `CINEMATOGRAFICO`, `MUSICA-GENERAL`, `FOLCLOR-TEATRO-ZARZUELA-OPERA-BALLET-CIRCO`, `OTROS`), con el ejercicio como parte de la clave compuesta |
| Ámbito | nacional |
| Vigencia | 2007–, desde la sustitución del artículo 57 por la Ley 29168, sin modificación posterior conocida a la fecha de esta transcripción |

**No se carga con este archivo.** Se carga con el derivado de [`publicacion/`](publicacion/), que es lo que `PublicarParametros` lee (#188); este archivo es su fuente, no su entrada. D-13 se cerró el 2026-08-28 y ya no bloquea nada.

## 3. Qué no cabe hoy

- La tasa de los espectáculos taurinos no depende solo del **tipo** de espectáculo, sino de una
  condición sobre el **precio de la entrada** ("valor promedio ponderado de la entrada superior o
  no al 0.5% de la UIT"). Eso no es un parámetro fijo por ejercicio: es una regla de cálculo que
  compara el precio real de cada evento contra un umbral en UIT. `parametro_tributario` puede guardar
  el umbral (`0.5% UIT`) y las dos tasas (10% y 5%), pero decidir cuál de las dos aplica a un
  espectáculo taurino concreto exige conocer el precio de sus entradas — eso es lógica de
  liquidación, no un valor transcribible aquí.
- El artículo 58 exige una garantía del 15% del impuesto **calculado sobre el aforo o capacidad del
  local** para espectáculos temporales o eventuales — un dato que depende del local, no de la norma
  nacional, y que este archivo no fija.
- La distinción entre espectáculo **permanente** y **temporal o eventual** (artículo 59) cambia el
  plazo de pago, pero esa clasificación es una característica de cada espectáculo concreto, no un
  valor normativo por transcribir.
