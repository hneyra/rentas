-- ============================================================================
--  V43 — LA RESOLUCION QUE RESUELVE UN RECURSO TIENE SU PROPIO TIPO (#412)
--
--  QUE PASABA
--  ----------
--  `resolucion_gerencia` mezclaba dos actos: el que ORDENA LA COBRANZA —uno por papeleta y
--  tipo, que es lo que protegen `resolucion_gerencia_ordinaria_uq` y `_sancionadora_uq`— y
--  el que RESUELVE UN RECURSO —uno por recurso, que ya protege `_descargo_uq`—. El segundo
--  solo existia montado sobre el primero: en transito solo hay dos rutas que dictan, las
--  dos unicas por papeleta, y un recurso presentado despues de la ordinaria y la
--  sancionadora no se podia resolver por ninguna (409, 409 y 404). En la familia
--  administrativa, un recurso contra la RIS se resolvia con una segunda RGA, y la corrida
--  que busca la RGA de la papeleta con `.optional()` fallaba con dos filas.
--
--  QUE CAMBIA
--  ----------
--  Un cuarto tipo, RECURSO, sin indice por papeleta: se sostiene en `_descargo_uq` (un
--  acto por recurso) y en `resolucion_gerencia_fallo_ck` (con recurso, con fallo). Los
--  dos indices parciales no se tocan.
-- ============================================================================

ALTER TABLE resolucion_gerencia DROP CONSTRAINT resolucion_gerencia_tipo_check;
ALTER TABLE resolucion_gerencia ADD CONSTRAINT resolucion_gerencia_tipo_check
    CHECK (tipo IN ('ORDINARIA', 'SANCIONADORA', 'ADMINISTRATIVA', 'RECURSO'));

-- La que resuelve un recurso lo nombra: sin descargo no hay nada que resolver.
ALTER TABLE resolucion_gerencia ADD CONSTRAINT resolucion_gerencia_recurso_ck
    CHECK (tipo <> 'RECURSO' OR descargo_id IS NOT NULL);
