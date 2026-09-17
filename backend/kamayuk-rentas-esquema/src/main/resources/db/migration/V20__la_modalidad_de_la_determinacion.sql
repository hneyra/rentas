-- ============================================================================
--  V20 — LA DETERMINACION DICE BAJO QUE CRONOGRAMA SE EMITIO (#234)
--
--  QUE FALTABA, Y POR QUE NADIE PODIA VERLO
--  ----------------------------------------
--  `POST /rentas/predial/calculo-individual` devuelve `modalidad` y `cuotas[]`, y
--  los dos salian de un dato que NO SE GUARDABA EN NINGUNA PARTE: la modalidad
--  llegaba en el cuerpo de la peticion y, si no venia, se suponia TRIMESTRAL.
--  La unica tabla del esquema que la guardaba era `corrida_predial`, que es de la
--  emision MASIVA.
--
--  Asi que la fila que quedaba escrita no decia bajo que cronograma se determino.
--  Todo lo demas de esa determinacion SI es reproducible —la base y el monto estan
--  guardados, y la UIT, los tramos, el minimo y el derecho de emision salen del
--  `conjunto_id` sellado que la fila fija—, y eso es lo que `GET
--  /rentas/predial/determinaciones` publica desde #207. El cronograma no.
--
--  Es la regla 6 sin cumplir para la individual: «recalcular 2027 en 2037 debe dar
--  el mismo centimo» valia para el impuesto y no para las cuotas, porque dos anios
--  despues nadie podia decir si aquel contribuyente pago al contado o en cuatro
--  trimestres — y la unica salida era suponer el valor por omision, que es lo que
--  la regla 5 prohibe.
--
--  LO QUE SE GUARDA ES LA MODALIDAD, Y NO LAS CUOTAS
--  -------------------------------------------------
--  Con la modalidad escrita, el cronograma vuelve a ser DERIVABLE del conjunto
--  sellado que la fila ya fija: las fechas son `PREDIAL_VENCIMIENTO:‹clave›` de ese
--  conjunto y el importe es el reparto del monto determinado, que tambien esta.
--  Guardar las cuotas calculadas dejaria DOS VERDADES sobre el mismo hecho —la fila
--  y el conjunto—, que es lo que #214 acaba de retirar del acta de fiscalizacion.
--
--  LAS FILAS ANTERIORES A V20 SE QUEDAN EN NULO, Y ES UNA DECISION
--  --------------------------------------------------------------
--  NULO significa «esta fila es anterior a V20», nunca «se pago al contado» ni «fue
--  trimestral». No se rellena hacia atras, y no es por no poder: `kamayuk_app` tiene
--  UPDATE sobre esta tabla. Es que NO HAY NADA CIERTO QUE ESCRIBIR — la modalidad la
--  elige el contribuyente y de aquellas filas no consta en ningun sitio—, y escribir
--  'TRIMESTRAL' por omision repetiria dentro de la base el defecto exacto que esta
--  migracion existe para cerrar: un cronograma supuesto indistinguible de uno
--  elegido. Es el mismo trato que `V10` le dio a `motivo_anulacion` y `V14` a
--  `zona_del_territorio`.
--
--  La consecuencia esta escrita en la lectura de #207 y se prueba: una determinacion
--  con `modalidad` nula publica `modalidad: null` y `cuotas: []` — el cronograma en
--  blanco, no el trimestral supuesto.
--
--  POR QUE EL `CHECK` DE VALORES **SI** SE VALIDA, Y EL DE `V10` NO PODIA
--  ---------------------------------------------------------------------
--  `V10` emitio su `CHECK` `NOT VALID` porque no se podia saber que habia en
--  `pago_recibido` de una instalacion en marcha y una anulacion anterior lo habria
--  violado. Aqui no hay ese riesgo y no hace falta el escape: la columna NACE en esta
--  migracion, asi que TODA fila existente la tiene nula y ninguna puede violar un
--  `CHECK` que admite el nulo. Emitirlo `NOT VALID` sin necesidad dejaria una
--  restriccion que nadie valida nunca.
--
--  LO QUE LA BASE **NO** PUEDE VIGILAR, Y QUIEN LO VIGILA
--  ------------------------------------------------------
--  Que una determinacion predial NUEVA no pueda volver a quedarse sin modalidad. No
--  hay `CHECK` que lo diga: una restriccion no distingue una fila insertada hoy de
--  una anterior a V20, y `NOT NULL` dejaria sin migrar toda base con determinaciones.
--  Lo vigila el DOMINIO, donde si es estructural: `Determinacion.nuevaPredial` exige
--  la modalidad y no hay otra forma de construir una cabecera predial nueva.
--  `DeterminarPredial` dejo de suponerla, y el borde contesta 422 nombrandola.
-- ============================================================================

ALTER TABLE determinacion ADD COLUMN modalidad varchar(20);

-- Las dos formas del articulo 15 del TUO LTM y ninguna mas. Antes de #234 la
-- modalidad viajaba como texto libre hasta `vencimientos`, que compara contra
-- 'CONTADO' y trata cualquier otra palabra como fraccionada: 'MENSUAL' devolvia
-- las cuatro fechas trimestrales con esa etiqueta encima. Escrito en una columna,
-- eso es una fila que nadie puede interpretar dentro de dos anios.
ALTER TABLE determinacion
    ADD CONSTRAINT determinacion_modalidad_ck
        CHECK (modalidad IS NULL OR modalidad IN ('CONTADO', 'TRIMESTRAL'));

-- El cronograma de cuotas es del PREDIAL. El vehicular, la alcabala y los
-- espectaculos comparten esta tabla y no tienen ninguno que resolver aqui: una
-- modalidad en esas filas seria un dato que ninguna lectura sabe leer.
ALTER TABLE determinacion
    ADD CONSTRAINT determinacion_modalidad_solo_predial_ck
        CHECK (modalidad IS NULL OR tributo = 'PREDIAL');

COMMENT ON COLUMN determinacion.modalidad IS
    'Bajo que cronograma del articulo 15 se determino: CONTADO —una cuota— o TRIMESTRAL —cuatro—. '
    'Con ella el cronograma vuelve a ser derivable del `conjunto_id` sellado que la fila fija, sin '
    'guardar las cuotas, que serian una segunda verdad sobre el mismo hecho. NULA significa «fila '
    'anterior a V20» y NO «al contado»: de aquellas determinaciones la modalidad no consta en '
    'ningun sitio, y la lectura de #207 publica su cronograma en blanco en vez del trimestral '
    'supuesto (#234).';
