-- ============================================================================
--  V12 — LA COLA DE MENSAJES MUERTOS DEL INGESTOR DE CATASTRO (C-8, ADR-0026 §4)
--
--  QUE FALTABA
--  -----------
--  `V4` dejo el buzon de lo APLICADO (`catastro_evento_aplicado`) y `V9` le puso la
--  procedencia. Lo que no habia es donde poner lo que NO SE PUEDE APLICAR — y con un
--  consumidor que acusa lo que consume, eso no es un detalle: un evento que no se
--  puede aplicar y no se acusa se vuelve a servir para siempre y BLOQUEA LA COLA
--  DETRAS DE EL. La proyeccion se quedaria congelada en el hecho anterior, sin un solo
--  error visible, que es exactamente el modo de fallo que la anti-entropia existe para
--  encontrar tarde.
--
--  LOS DOS FALLOS NO SON EL MISMO, Y SOLO UNO LLEGA AQUI
--  -----------------------------------------------------
--  Es la misma distincion que `caja` hizo entre `NoContesta` y `Rechazado`, leida
--  desde el lado del receptor:
--
--    NO SE PUDO AHORA   la base no contesta, la conexion se cayo. NO se acusa y NO se
--                       escribe nada aqui: la vuelta siguiente lo vuelve a intentar,
--                       porque el emisor lo sigue teniendo pendiente. Escribirlo aqui
--                       lo mataria por un motivo que iba a arreglarse solo.
--
--    NO SE PUEDE NUNCA  el cuerpo no se puede leer, o el emisor esta reescribiendo un
--                       hecho sellado, o el ejercicio ya tiene una valuacion de ese
--                       predio de otra corrida. Ninguno cambia solo. Se escribe aqui,
--                       SE ACUSA para que deje de servirse, y se avisa a una persona
--                       con nombre.
--
--  POR QUE NO ES UN ESTADO DE `catastro_evento_aplicado`
--  ----------------------------------------------------
--  Porque esa tabla significa «este hecho ESTA aplicado» y `V9` colgo de ella cuatro
--  claves foraneas: cada fila proyectada nombra el evento que la escribio. Una fila
--  con estado MUERTO ahi seria un evento que nada escribio, y las claves foraneas
--  dejarian de significar lo que dicen — «una procedencia que no se puede seguir hasta
--  el hecho que la produjo no es procedencia».
--
--  NO SE BORRA NINGUNA FILA (regla 4, RNF-051). Un evento muerto se EXPLICA: alguien
--  se hace cargo por escrito, igual que `pago_evento.explicacion` en `caja`.
-- ============================================================================

CREATE TABLE catastro_evento_muerto (
    municipalidad_id bigint       NOT NULL REFERENCES municipalidad (id),
    evento_id        uuid         NOT NULL,
    secuencia        bigint       NOT NULL,
    tipo             varchar(40)  NOT NULL,
    predio_id        bigint,
    ejercicio        ejercicio,
    cuerpo           text         NOT NULL,
    huella           char(64)     NOT NULL,
    motivo           varchar(400) NOT NULL,
    recibido_en      timestamptz  NOT NULL,
    explicacion      varchar(400),
    explicado_en     timestamptz,

    CONSTRAINT catastro_evento_muerto_pk PRIMARY KEY (municipalidad_id, evento_id),
    CONSTRAINT catastro_evento_muerto_secuencia_ck CHECK (secuencia >= 0),
    -- Un motivo en blanco es un muerto sin causa: quien lo mire tendria que ir a
    -- buscarla al registro del servidor de esa noche.
    CONSTRAINT catastro_evento_muerto_motivo_ck CHECK (length(btrim(motivo)) >= 5),
    -- Explicado es un hecho CON HORA y CON TEXTO, o no lo es. Es la misma forma que
    -- `pago_evento_explicacion_ck` de `caja`.
    CONSTRAINT catastro_evento_muerto_explicacion_ck CHECK (
        (explicacion IS NULL AND explicado_en IS NULL)
        OR (explicado_en IS NOT NULL AND length(btrim(explicacion)) >= 5))
);

CREATE INDEX catastro_evento_muerto_sin_explicar_ix
    ON catastro_evento_muerto (municipalidad_id, recibido_en)
    WHERE explicacion IS NULL;

COMMENT ON TABLE catastro_evento_muerto IS
    'Los hechos de `catastro` que este sistema NO PUEDE aplicar (C-8, ADR-0026 §4). Solo llegan '
    'aqui los fallos permanentes: los transitorios no se acusan y se reintentan solos. Cada fila '
    'dispara una alerta a una persona con nombre, porque mientras este aqui la proyeccion del '
    'padron esta incompleta y ninguna cifra lo dice.';
COMMENT ON COLUMN catastro_evento_muerto.cuerpo IS
    'El evento entero tal como llego, y es `text` Y NO `jsonb` A PROPOSITO. Lo encontro la primera '
    'ejecucion: una de las tres causas de muerte es precisamente que el cuerpo NO SEA JSON —el '
    'emisor mando otra cosa, o un proxy contesto HTML—, y con `jsonb` el INSERT que guarda ese '
    'hecho falla con «invalid input syntax for type json». O sea que la unica tabla que existe para '
    'guardar lo que no se pudo leer no podria guardar exactamente lo que no se pudo leer. Se guarda '
    'porque es lo unico que permite volver a aplicarlo a mano una vez arreglada la causa: el emisor '
    'ya lo dio por entregado.';
COMMENT ON COLUMN catastro_evento_muerto.motivo IS
    'Por que no se pudo aplicar, en las palabras del ingestor. Es lo que separa «el cuerpo no se '
    'puede leer» de «el emisor reescribio un hecho sellado», que se arreglan de maneras distintas.';
COMMENT ON COLUMN catastro_evento_muerto.explicacion IS
    'Quien se hizo cargo y que hizo. Nulo mientras nadie lo haya mirado. No hay DELETE (regla 4): '
    'un hecho que no se pudo aplicar se explica, no se borra.';

-- ----------------------------------------------------------------------------
--  RLS. Sin valor por omision: sin contexto de tenant, la consulta FALLA.
-- ----------------------------------------------------------------------------

ALTER TABLE catastro_evento_muerto ENABLE ROW LEVEL SECURITY;
ALTER TABLE catastro_evento_muerto FORCE ROW LEVEL SECURITY;
CREATE POLICY catastro_evento_muerto_tenant ON catastro_evento_muerto FOR ALL TO PUBLIC
    USING (municipalidad_id = current_setting('app.municipalidad_id')::bigint)
    WITH CHECK (municipalidad_id = current_setting('app.municipalidad_id')::bigint);

-- ----------------------------------------------------------------------------
--  Privilegios
--
--  `kamayuk_app` LEE y no escribe, igual que en las cuatro proyecciones de `V4` y `V5`:
--  quien escribe es el ingestor. Lee porque quien explique un muerto lo hara desde una
--  pantalla, y porque una cifra de operacion tiene que poder contarlos.
--
--  El ingestor recibe UPDATE ademas de INSERT: la explicacion se escribe encima. No
--  recibe DELETE, y no lo va a recibir (regla 4).
-- ----------------------------------------------------------------------------

GRANT SELECT ON catastro_evento_muerto TO kamayuk_app;
GRANT SELECT, INSERT, UPDATE ON catastro_evento_muerto TO rol_ingestor_catastro;
GRANT SELECT ON catastro_evento_muerto TO kamayuk_readonly;
