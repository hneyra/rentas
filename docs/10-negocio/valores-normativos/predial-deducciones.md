# Deducciones del predial: pensionista y adulto mayor no pensionista

| Campo | Valor |
|---|---|
| Norma | TUO LTM art. 19, con el cuarto párrafo incorporado por la Ley N.° 30490, Ley de la Persona Adulta Mayor |
| Artículo | 19, párrafos 1.º a 3.º (pensionista); 19, cuarto párrafo, incorporado por la Primera Disposición Complementaria Modificatoria de la Ley 30490 (adulto mayor no pensionista) |
| Publicada | 2004-11-15, El Peruano (TUO LTM, D.S. 156-2004-EF); 2016-07-21, El Peruano (Ley 30490) |
| Ejercicios que rige | 2004– (pensionista); 2016– (adulto mayor no pensionista, desde la publicación de la Ley 30490) |
| Filas de NEG-02 §2 | 3, 4 |
| Transcribió | JNA, 2026-08-24 |
| Verificó | HNA, 2026-08-25 |
| Estado | VERIFICADO |

## 1. La tabla tal como está en la norma

**Sin reordenar, sin convertir unidades, sin corregir un encabezado.**

| Deducción | Monto (en UIT) | Requisitos |
|---|---|---|
| Pensionista | 50 UIT | Propietario de un solo predio, a nombre propio o de la sociedad conyugal, destinado a vivienda de los mismos, y cuyo ingreso bruto esté constituido por la pensión que recibe y ésta no exceda de 1 UIT mensual |
| Adulto mayor no pensionista | 50 UIT | Propietario de un solo predio, a nombre propio o de la sociedad conyugal, destinado a vivienda de los mismos, y cuyos ingresos brutos no excedan de 1 UIT mensual |

> Texto literal del artículo 19, primer párrafo (TUO LTM, D.S. 156-2004-EF): «Los pensionistas
> propietarios de un solo predio, a nombre propio o de la sociedad conyugal, que esté destinado a
> vivienda de los mismos, y cuyo ingreso bruto esté constituido por la pensión que reciben y ésta
> no exceda de 1 UIT mensual, deducirán de la base imponible del Impuesto Predial, un monto
> equivalente a 50 UIT.» El TUO añade que, si el pensionista posee más de un predio, el beneficio
> solo se aplica al que constituye su vivienda.
>
> Texto literal del cuarto párrafo incorporado por la Primera Disposición Complementaria
> Modificatoria de la Ley 30490 (2016-07-21): «Lo dispuesto en los párrafos precedentes es de
> aplicación a la persona adulta mayor no pensionista propietaria de un sólo predio, a nombre
> propio o de la sociedad conyugal, que esté destinado a vivienda de los mismos, y cuyos ingresos
> brutos no excedan de una UIT mensual.» Es decir: el mismo monto (50 UIT) y los mismos requisitos
> del pensionista, salvo que no exige recibir pensión.
>
> El Decreto Supremo N.° 401-2016-EF (publicado 2016-12-29, El Peruano) reglamenta cómo se
> acredita cada requisito del adulto mayor no pensionista (edad ≥ 60 años al 1 de enero del
> ejercicio, según el DNI; no estar declarado como pensionista en ningún sistema previsional
> peruano; única propiedad —admite además una cochera—; destino a vivienda, con uso parcial
> productivo si la municipalidad lo permite; ingresos brutos propios o de la sociedad conyugal que
> no excedan de 1 UIT mensual), pero no cambia el monto de 50 UIT ni el artículo que lo fija: es
> reglamento de procedimiento, no de la norma que fija la cifra, así que no se usa como `Norma` de
> la cabecera.

## 2. Cómo entra al sistema

| Qué | Dónde |
|---|---|
| Tipo | `parametro_tributario` (tipo `DEDUCCION_PENSIONISTA`, `DEDUCCION_ADULTO_MAYOR`) |
| Clave | `DEDUCCION_PENSIONISTA` y `DEDUCCION_ADULTO_MAYOR`, una fila cada una — el monto es el mismo (50 UIT), la clave distingue el requisito que valida el sistema (pensión vs. no pensión) |
| Ámbito | nacional |
| Vigencia | `DEDUCCION_PENSIONISTA`: 2004–. `DEDUCCION_ADULTO_MAYOR`: 2016–, sin fecha de corte conocida |

**Se carga desde `publicacion/parametros-2026.csv`**, el derivado publicable de este
archivo, con `infra/carga-de-datos/publicar-parametros.sh` (#188, #247 §4). Las dos firmas
de la cabecera de arriba son las que llegan a `usuario_carga` y `usuario_aprueba`: la doble
verificación de ADR-0007 ocurrió aquí, y la herramienta la transporta.

## 3. Qué no cabe hoy

El esquema necesita distinguir **qué requisito** valida cada solicitud (pensión vs. edad + no
pensión) para poder auditar por qué se aceptó una deducción; ese campo no es parte del valor
transcrito aquí (el monto es el mismo, 50 UIT, para ambas) y queda para cuando se diseñe la tabla
de beneficios del contribuyente. El monto exacto en que empieza a regir el beneficio del adulto
mayor no pensionista para efectos prácticos de cobranza —si es el ejercicio 2016 o el 2017, dado
que el reglamento (D.S. 401-2016-EF) recién se publicó en diciembre de 2016— no se pudo confirmar
contra una fuente oficial que lo declare expresamente; se transcribe aquí la fecha de publicación
de la Ley 30490 (2016-07-21) como la fecha cierta, sin resolver la pregunta de vigencia práctica
por ejercicio.
