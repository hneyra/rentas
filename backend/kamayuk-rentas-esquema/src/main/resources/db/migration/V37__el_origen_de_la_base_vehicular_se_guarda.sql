-- ============================================================================
--  V37 — EL ORIGEN DE LA BASE VEHICULAR SE GUARDA CON LA DETERMINACION (#477)
--
--  QUE PASABA
--  ----------
--  Desde #330 la base del impuesto vehicular es la del art. 32 del TUO LTM:
--  el mayor entre el valor de adquisicion del propietario al 1 de enero y la
--  tabla. El calculo publicaba de cual de los dos salio —ADQUISICION, TABLA o
--  TABLA_SIN_ADQUISICION— y ese dato no se guardaba: una determinacion
--  vehicular asentada no decia si hubo comparacion o solo el piso, ni si fue
--  el piso porque faltaba el dato. Es lo que se necesita para reclamarla, para
--  reproducir su memoria de calculo (ARQ-09 §3) y para encontrar los vehiculos
--  a los que les falta capturar la adquisicion.
--
--  QUE HACE
--  --------
--  Una columna, `origen_base`, nulable, y dos CHECK, como `modalidad` en V21:
--  los tres valores del enumerado, y que solo la lleve el VEHICULAR —el
--  predial, la alcabala y los espectaculos no tienen art. 32—.
--
--  Lo que NO hace: rellenar hacia atras. Las determinaciones vehiculares ya
--  asentadas no guardaron su origen en ninguna parte, y recalcularlo hoy no
--  diria con que dato se comparo entonces: quedan nulas, que es lo que la base
--  sabe de ellas. Por eso los dos CHECK admiten el nulo y se validan.
-- ============================================================================

ALTER TABLE determinacion ADD COLUMN origen_base varchar(30);

ALTER TABLE determinacion
    ADD CONSTRAINT determinacion_origen_base_ck
        CHECK (origen_base IS NULL
            OR origen_base IN ('ADQUISICION', 'TABLA', 'TABLA_SIN_ADQUISICION'));

ALTER TABLE determinacion
    ADD CONSTRAINT determinacion_origen_base_solo_vehicular_ck
        CHECK (origen_base IS NULL OR tributo = 'VEHICULAR');

COMMENT ON COLUMN determinacion.origen_base IS
    'De cual de los dos operandos del art. 32 salio la base vehicular (#477):'
    ' ADQUISICION, TABLA o TABLA_SIN_ADQUISICION. Nula fuera del VEHICULAR y en'
    ' las determinaciones anteriores a V37.';
