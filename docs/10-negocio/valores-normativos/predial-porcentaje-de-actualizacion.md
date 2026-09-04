# El `% actualización` del predial: qué se buscó, qué se descartó y qué queda

| Campo | Valor |
|---|---|
| Norma | **Ninguna identificada todavía.** Se transcriben aquí los dos artículos del TUO de la Ley de Tributación Municipal (D.S. N.° 156-2004-EF) que podrían darle origen —el que actualiza la base imponible del año anterior por Decreto Supremo cuando no se publican los valores, y el art. 14, que es la actualización de valores de la emisión mecanizada— y se dice cuál de los dos quedó descartado y por qué |
| Artículo | Del TUO LTM: el párrafo de actualización por Decreto Supremo (§1.1, con su número **sin confirmar**, ver §1.4) y el art. 14 (§1.2) |
| Publicada | 2004-11-15, fecha del D.S. N.° 156-2004-EF que aprueba el TUO |
| Ejercicios que rige | No aplica: este archivo **no publica ninguna cifra** |
| Filas de NEG-02 §2 | 33 |
| Transcribió | Agent, 2026-08-30 |
| Verificó | — |
| Estado | TRANSCRITO |

> **Este archivo no trae ninguna cifra, y eso es exactamente el punto.** Existe porque el
> `% actualización` llevaba desde M02 sin fila en el mapa normativo, y **un dato sin fila es un dato
> que nadie va a buscar** — es la misma lección que H-17 dejó con la deducción de Amazonía. Lo que
> trae es el estado de la búsqueda: qué se descartó, con qué evidencia, y qué queda por mirar.
>
> **Mientras no haya fuente, no hay valor por omisión.** Ni aquí ni en el código: la regla 5 vigila
> desde #437 los nombres `ACTUALIZACION` y `FACTOR`, y hay una muestra que lo demuestra
> (`MuestraDeFactorDeActualizacionCompilado`). El valor «obvio» —100 %, o sea 1, o sea ninguno— es
> el más peligroso de todos, porque escribirlo **no se siente** como inventar un dato.

## 1. La tabla tal como está en la norma

**No hay tabla, y el encabezado de esta sección es el que el corpus exige a todos sus archivos.** Lo
que hay son los dos artículos candidatos, transcritos **sin reordenar, sin parafrasear y sin
corregir un encabezado**, igual que si fueran una tabla de cifras.

### 1.1 La actualización por Decreto Supremo cuando no se publican los valores

> «Cuando en determinado ejercicio no se publique los aranceles de terrenos o los precios unitarios
> oficiales de construcción, por Decreto Supremo se actualizará el valor de la base imponible del
> año anterior como máximo en el mismo porcentaje en que se incremente la Unidad Impositiva
> Tributaria (UIT).»

Es **el único porcentaje que el TUO LTM aplica para actualizar la base imponible del predial**, y
por eso es hoy el candidato vivo. Encaja además con la posición que la columna ocupa en M02
—autovalúo × `% actualización` × `% propiedad` → base imponible— y explicaría por qué su valor
habitual sería neutro: en un ejercicio en que **sí** se publican los aranceles y los valores
unitarios, este mecanismo no se activa.

`‹NO CONFIRMADO EN FUENTE OFICIAL: que este párrafo sea el que M02 llama «% actualización». Lo que
está confirmado es su texto y que es el único porcentaje de actualización de la base imponible del
predial en el TUO LTM; que sea el mismo campo de la pantalla de M02 es una hipótesis, no una
lectura›`.

### 1.2 El art. 14, y por qué se descarta

> «La actualización de los valores de predios por las Municipalidades, sustituye la obligación
> contemplada por el inciso a) del presente artículo, y se entiende como válida en caso que el
> contribuyente no la objete dentro del plazo establecido para el pago al contado del impuesto.»

Era la hipótesis fuerte y **se descartó leyendo los manuales del SRTM del MEF**, que es donde se
confirma un campo del proceso. El acto del art. 14 está documentado ahí de punta a punta —el Proceso
Masivo (v3.1.0), el Proceso Individual (v4.0.0 §4.3), la migración de declaraciones (v6.1.0 §4.4) y
la emisión mecanizada de la *Guía para el Registro y Determinación del Impuesto Predial*— y **en
todos es una redeterminación**: se refrescan las tablas normativas del ejercicio nuevo —aranceles,
valores unitarios, depreciación, UIT— y se regeneran las declaraciones. En ninguno hay un porcentaje
que se teclee, se calcule o se parametrice.

La Guía del MEF lo dice con sus tres insumos, y ninguno es un porcentaje sobre el autovalúo:

> «A. Actualización de valores. Durante esta etapa se desarrolla la actualización de valores en las
> tablas maestras con los siguientes parámetros: 1. La Tabla de Valores Unitarios Oficiales de
> Edificaciones y de Valores Arancelarios (Ministerio de Vivienda, Construcción y Saneamiento) y la
> Tabla de Porcentajes de Depreciación. 2. La valorización de instalaciones fijas y permanentes
> (metodología publicada por el Ministerio de Vivienda, Construcción y Saneamiento). 3. Valor de la
> UIT.»

**Y la ausencia no es un punto ciego.** En las mismas pantallas del SRTM donde los otros dos factores
de D-11 sí aparecen con nombre —el incremento del 5 % y el factor de oficialización, este último con
su `0.68` visible en la declaración jurada mecanizada—, el `% actualización` no está. La
determinación que el SRTM imprime va: `VALOR UNIT. M2 · INCREMENTO 5% · DEPRECIACIÓN · VALOR
UNITARIO DEPRECIADO · ÁREA CONSTRUIDA · ÁREA COMÚN · AUTOAVALUO · CONDOMINIO-COPROPIEDAD % ·
DEDUCCIÓN · AUTOAVALUO AFECTO`. Sin ninguna columna de actualización.

### 1.3 Una determinación real del SRTM, con sus cifras

El 2026-08-30 se leyó el manual `M02-1-020` —el mismo que solo nombra la columna en un
encabezado— **como imagen**, y su captura de la pestaña «Datos» trae una determinación completa. Es
la primera vez que hay cifras.

| | |
|---|---|
| Autovalúo | `171,179.42` |
| **% actualización** | **`0.00 %`** |
| % propiedad | `80.00` |
| Base imponible | `136,943.54` |
| Base exonerada | `136,943.54` · Base afecta `0.00` · Impuesto `0.00` |

**La aritmética descarta una lectura y fija otra.** `171 179,42 × 0,80 = 136 943,54`, que es
exactamente la base imponible. Es decir:

- **el `% actualización` no multiplica como factor.** Si la secuencia fuera literalmente
  `autovalúo × % actualización × % propiedad` —como la describe NEG-05 §0.1— con `0,00` la base
  sería **cero**, y no lo es;
- **es un incremento, y su valor neutro es `0`, no `100`.** La base sale de
  `autovalúo × (1 + % actualización) × % propiedad`, o su equivalente
  `(autovalúo + % actualización × autovalúo) × % propiedad`: con `0,00 %` las dos dan lo mismo, y
  las dos coinciden con la captura.

**Y eso cambia cuál es el valor peligroso.** Este archivo decía —y la muestra de la regla 5 con
él— que el valor «obvio» era `1`, o sea 100 %. **No lo es: es `0`.** Y `0` es peor, porque
`BigDecimal.ZERO` en un campo llamado «porcentaje de actualización» se lee como «no aplica ninguno»
incluso más que un `1`.

**Lo que esta captura NO prueba**, y hay que decirlo porque es una sola:

- **qué pasa cuando no es cero.** Con `0,00 %`, `× (1 + p)` y `+ p × autovalúo` son
  indistinguibles, y también lo sería cualquier otra forma que se anule en cero;
- **quién lo fija, ni cuándo deja de ser cero.** El manual no lo dice en ninguna parte de su
  texto, y los dos manuales de «Parámetros» del SRTM —el de la CF2 y el de la CF4— tampoco: el de
  la CF2 configura la emisión masiva (año, tipo de lote, concepto y formato por lote) y el de la
  CF4, catálogos del buzón electrónico. **Ninguno tiene una pantalla para este porcentaje.**

De paso, la misma captura confirma dos cosas que ya estaban: los tramos se expresan en soles del
ejercicio —`> 0 y <= 77,250` y `> 77,250 y <= 309,000`, que son 15 y 60 UIT de 2024 (5 150)— y el
resumen lleva «Cuotas 4».

### 1.4 El artículo, ya confirmado: es el **12**

**Resuelto el 2026-08-30, mirando la página.** El párrafo de §1.1 es el **artículo 12 del TUO de la
Ley de Tributación Municipal**, y lo confirma la página 5 del PDF oficial renderizada como imagen:
tras el bloque de concordancias del artículo anterior se lee, con su rótulo delante,

> «**Artículo 12.-** Cuando en determinado ejercicio no se publique los aranceles de terrenos o los
> precios unitarios oficiales de construcción, por Decreto Supremo se actualizará el valor de la base
> imponible del año anterior como máximo en el mismo porcentaje en que se incremente la Unidad
> Impositiva Tributaria (UIT).»

y a renglón seguido «Artículo 13.- El impuesto se calcula aplicando a la base imponible la escala
progresiva acumulativa».

**Por qué la primera vez salió mal, que es lo que hay que recordar.** La extracción anterior leyó el
PDF *sin conservar la disposición del texto*, y con las dos columnas de las concordancias
entremezcladas el rótulo del artículo caía **después** de su propio cuerpo. Extraído conservando la
disposición, el rótulo vuelve a su sitio. La lección no es sobre este PDF: es que **la posición
relativa de un rótulo en una extracción plana no es evidencia de nada**, y por eso el método exige
la página renderizada. Lo que faltaba no era una fuente distinta, era poder dibujarla —el visor no
estaba instalado en la máquina, y ahora sí—.

El sha256 del PDF leído es
`31ac1e01e0a8a5f2cd29ad838b4f6aef3e48bf08cbb772a1207e82d8b92f64fd`, el mismo que
[`fuentes/README.md`](fuentes/README.md) declara para
`DS-156-2004-EF-TUO-Ley-Tributacion-Municipal.pdf`: se descargó del archivo de S3 y se comparó, de
modo que lo que se miró es el ejemplar archivado y no otra copia de internet.

### 1.5 El inventario completo de parámetros del SRTM no tiene ninguno que se llame así

**Y ese silencio es un dato.** Los dos manuales de «Parámetros» que se habían leído —el de la CF2 y
el de la CF4— no lo definían, pero eran dos de **cinco**: el módulo M21 tiene un manual por fase, y
el de la **CF1** (`M21-1-003-Parámetros`, 506 páginas) es el que administra los parámetros del
predial y los generales. Se leyó entero su índice. Los submenús que publica son:

- **Parámetros Predial** — VUO Construcción · VUO Obras Complementarias · Depreciación · Uso de
  Predio Pensionista · **Tasa Predial** · Arancel Urbano · Arancel Rústico
- **Parámetros** — Catálogo · **IPC/IPM** · Municipalidad · Tipo de Cambio · Tipo de Cambio Promedio
  · **UIT** · Concepto de Recaudación · Feriados · Distrito · Interés Moratorio · Tipo de Operación ·
  Vencimiento · Vías · Áreas Organizacionales · Zona Urbana · Sub Zona Urbana · Doc. Sustento ·
  Plazo de Presentación de Tributo · Uso Predio · Uso Predio – Depreciación · Notaría · Agencia ·
  Base legal (y otros de catálogo)
- **Infracción Tributaria**, **Configuración**, **Arbitrios** (Nivel de Afluencia · Grupo de Uso ·
  Tasa Serenazgo · Barrido de Calles · Residuos Sólidos · Parques y Jardines) y **Promedio
  Habitantes**

**Ninguno es el `% actualización`.** No hay pantalla donde teclearlo, ni por ejercicio ni por
municipalidad. Y el sistema que enseña esa columna en M02 es el mismo cuyo módulo de parámetros es
este: si fuera un valor que alguien fija, tendría que estar aquí.

Lo que sí hay es **UIT**, y eso encaja con el artículo 12: el porcentaje que ese artículo autoriza
es «el mismo porcentaje en que se incremente la UIT», que **se calcula** a partir de dos filas de un
parámetro que el módulo sí tiene. Un valor derivado no necesita pantalla.

`‹HIPÓTESIS, NO LECTURA: que M02 calcule el «% actualización» como la variación de la UIT del
artículo 12. Lo confirmado es (a) que el módulo de parámetros del SRTM no tiene ninguno con ese
nombre, y (b) que sí tiene la UIT. Falta una determinación con el porcentaje distinto de cero, que
es lo único que puede distinguir una fórmula de otra›`.

#### 1.5.1 El `IPC/IPM`, que es otra cosa y conviene no confundir

El módulo **sí** tiene un parámetro de índices financieros, y a primera vista parece el candidato.
Su pantalla —«MANTENIMIENTO DE PARÁMETROS - ÍNDICES FINANCIEROS»— la administra el **Administrador
MEF**, o sea es nacional, y guarda por fila: `Año Afectación`, `Mes`, `Índice Financiero`, `Índice`,
`Variación Mensual`, `Variación Acumulado`, `Tipo Base Legal`, `Base Legal` y `Fecha Base Legal`.
Las filas que el manual enseña son reales:

| Año | Mes | Índice financiero | Índice | Var. mensual | Var. acumulada | Base legal |
|---|---|---|---|---|---|---|
| 2018 | 1 | ÍNDICE DE PRECIOS AL POR MAYOR (IPM) | 105.740105 | 0.260000 | 0.260000 | RESOLUCIÓN JEFATURAL INEI |
| 2018 | 1 | ÍNDICE DE PRECIOS AL CONSUMIDOR (IPC) | 88.590000 | 0.130000 | 0.130000 | RESOLUCIÓN JEFATURAL INEI |
| 2018 | 2 | ÍNDICE DE PRECIOS AL POR MAYOR (IPM) | 106.137559 | 0.380000 | 0.630000 | RESOLUCIÓN JEFATURAL INEI |

**Pero no es este campo, y lo dice dónde está cada cosa.** El IPM del artículo 15 del TUO LTM
reajusta las **cuotas** —«las cuotas restantes serán reajustadas de acuerdo a la variación acumulada
del Índice de Precios al Por Mayor»—, y el `% actualización` de M02 no vive en el resumen de cuotas:
vive en la grilla **por predio**, entre el autovalúo y el `% propiedad`. Son dos actualizaciones
distintas, en dos momentos distintos del cálculo, y llamarlas la misma sería el error que este
archivo existe para no cometer.

Queda anotado aquí porque el sistema lo necesitará igual —el reajuste de cuotas del artículo 15 es
una cifra que hoy tampoco está—, y porque quien retome D-11 va a tropezar con esta pantalla y
merece encontrarse ya hecha la distinción.

## 2. Cómo entra al sistema

**No entra.** No hay cifra que publicar, y esa es la situación correcta mientras no haya fuente.

Lo que sí está decidido es **cómo se comporta el sistema mientras tanto**, y no es «aplicar 1»:

| Qué | Cómo |
|---|---|
| Si una regla lo necesita | Falla nombrando su llave, como `TASA_ANUNCIO:‹CLASE›` (#51), `BENEFICIO:‹CAMPAÑA›` (#72) y `VEHICULAR_MINIMO` (#399): **422 nombrando la llave**, nunca un importe plausible |
| Si alguien lo compila | La regla 5 lo rechaza: `ACTUALIZACION` y `FACTOR` entraron a la lista de nombres vigilados en #437, con su muestra |
| `RT-002`, `RT-005` y `RT-011` | No se implementan **ni estructuralmente** (`CLAUDE.md` §«No implementar todavía») |

## 3. Qué no cabe hoy

- **La fuente.** Es lo que falta, y este archivo existe para que la próxima búsqueda no repita las
  anteriores.

  **Y lo primero que hay que decir es lo que NO va a funcionar.** Parecía que el camino era
  conseguir el manual donde la columna se vio —`M02-1-020 «Determinación de Deuda» v1.4`,
  12-12-2025, del SRTM moderno—. **Ese manual ya se leyó, entero**: es el único de los 74 del corpus
  del SRTM cuya cobertura NEG-00 §1 declara «Completa», y todo lo que aportó sobre esta columna es
  **su nombre en el encabezado de una grilla**:

  > «Detalle de los predios (dentro de una determinación): código · ubicación · autovalúo ·
  > **% actualización** · % propiedad · base imponible · base exonerada · uso.
  > **Concepto nuevo: `% actualización`.** Un factor aplicado al autovalúo antes de la base
  > imponible. ⚠ Sin identificar; probablemente el reajuste de valores del ejercicio.»
  > — *`../srtm`, `referencia-srtm-mef.md` §5b.2*

  Ese «probablemente» es conjetura del lector, marcada con su aviso, no una lectura. **El manual no
  define la columna**, y los otros cuatro conceptos nuevos de la misma sección —la deducción de
  Amazonía, el incremento del 5 %, el factor de oficialización y el metrado redondeado— salieron
  igual: nombres de columna sin definición. Volver a ese PDF no desbloquearía nada.

  Los caminos que quedan, en orden de valor:
  1. **El módulo `M21 Parámetros`** (38 MB, sin leer). Si el `% actualización` es configurable, se
     configura ahí y no en la pantalla de determinación. Es la pregunta que M02 no podía contestar
     por ser el manual del consumidor y no el del parámetro.
  2. **Una determinación real del SRTM con su desarrollo intermedio y sus cifras.** Contesta «cuánto
     vale» aunque nadie diga «qué es», y es exactamente lo que
     [`observaciones-srtm-mef/`](../observaciones-srtm-mef/) pide.
  3. **Perseguir el párrafo de §1.1**: si esa es la fuente, la pregunta deja de ser «¿qué norma crea
     el porcentaje?» y pasa a ser «¿qué Decretos Supremos se han dictado al amparo de ese párrafo, y
     en qué ejercicios?». Y el factor no sería un valor anual sino **excepcional**: neutro cuando
     hay valores publicados, el del D.S. cuando no los hay. Lo fija el Gobierno nacional, así que
     sería `D-02a` y no ordenanza local.

  **Lo que NO se puede concluir de los trece PDF públicos del MEF**, y conviene decirlo porque
  invita a un error: buscar «% actualiz» en su capa de texto da **cero coincidencias**, y eso no
  refuta nada. Esos manuales son casi todos capturas de pantalla —de uno de 8,2 MB salen 49 530
  caracteres, y «depreciación», «valor unitario» y «arancel» dan cero en un manual que describe la
  determinación predial—. **Una columna que viva dentro de una captura es invisible a ese método.**
- **Dos cosas que NO son este factor**, y conviene dejarlas deslindadas porque se le parecen y están
  por todo el corpus:
  - el **ajuste por IPM de la alcabala** (TUO LTM art. 24), que es el campo «IPM aplicado» de la
    pantalla de alcabala y opera sobre el valor de transferencia, no sobre el autovalúo;
  - el **reajuste de cuotas y moras** por IPM (art. 15.b), que está del lado de la deuda.

  Confundir cualquiera de los dos con el `% actualización` arrastraría una lectura equivocada a
  alcabala, que es una pantalla que ya existe.

## 4. Documentos relacionados

[`decisiones-abiertas.md`](../../00-gobierno/decisiones-abiertas.md) (D-11) ·
[`plan-de-desbloqueo-D-02.md`](../../00-gobierno/plan-de-desbloqueo-D-02.md) (H-17) ·
[`marco-normativo.md`](../marco-normativo.md) §2 fila 33 ·
[`valores-unitarios-2026.md`](valores-unitarios-2026.md) §1.6 —el incremento del 5 %, el factor de
D-11 que sí quedó resuelto— ·
[`obras-complementarias-y-oficializacion-2026.md`](obras-complementarias-y-oficializacion-2026.md)
—el factor de oficialización— ·
[`predial-deduccion-amazonia.md`](predial-deduccion-amazonia.md) —el precedente de «la norma da el
mecanismo, no la cifra»—
