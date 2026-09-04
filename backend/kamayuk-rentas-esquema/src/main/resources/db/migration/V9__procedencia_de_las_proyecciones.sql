-- ============================================================================
--  V9 — LA PROCEDENCIA DE CADA FILA PROYECTADA (P5E, ADR-0027 y ADR-0029)
--
--  QUE FALTABA, Y POR QUE NO SE VEIA
--  ---------------------------------
--  `V4` y `V5` dejaron las cuatro proyecciones con MEDIA procedencia cada una, y
--  ninguna con la mitad que le faltaba a la otra:
--
--    - `predio_ref` y `ficha_ref` llevan `secuencia` y NO dicen de que evento salieron
--      ni con que contenido.
--    - `valuacion_predio` lleva `evento_id` y `huella` y NO dice en que orden llego.
--    - `valuacion_corrida` lleva la huella AGREGADA de la corrida y ninguna de las dos.
--    - `catastro_evento_aplicado` —el buzon— dice que evento se aplico y en que orden,
--      y NO con que contenido.
--
--  El sintoma de esa falta no es un error: es una fila plausible. Desde P5C estas
--  tablas son la UNICA referencia que `rentas` tiene de lo que ya no esta en su base,
--  asi que la pregunta que hay que poder contestar delante de un contribuyente —«por
--  que esta ficha dice 180 m2»— se contesta con el evento que la escribio, y solo si
--  la fila lo nombra. Sin eso, la respuesta es «porque asi esta proyectado», que no es
--  una respuesta.
--
--  LAS TRES COLUMNAS, Y QUE DECIDE CADA UNA
--  ----------------------------------------
--    `evento_id`  DE QUE HECHO salio la fila. Con clave foranea al buzon, para que sea
--                 comprobable y no decorativa: una fila que nombra un evento que nunca
--                 se aplico es peor que una sin nombrar, porque parece trazable.
--    `secuencia`  EN QUE ORDEN llego. Es lo que impide que un hecho VIEJO que llega
--                 tarde pise a uno nuevo ya aplicado — el defecto que no se ve porque
--                 la fila queda plausible, dicho ya en la cabecera de `V4`.
--    `huella`     CON QUE CONTENIDO. Es la del cuerpo del evento TAL COMO LO EMITIO el
--                 otro sistema, y NO se recalcula aqui sobre lo proyectado: eso
--                 comprobaria que lo que tenemos es igual a lo que tenemos, que es
--                 literalmente lo que `V5` ya dice de `valuacion_corrida.huella`.
--                 Con ella, una reentrega del MISMO `evento_id` con OTRO contenido
--                 —el emisor reescribiendo un hecho sellado— se puede ver; sin ella,
--                 la deduplicacion por `evento_id` la da por buena y la descarta.
--
--  POR QUE `NOT NULL` SIN `DEFAULT`, Y POR QUE ESO ES SEGURO AQUI
--  -------------------------------------------------------------
--  Un `DEFAULT` inventaria una procedencia: un uuid de relleno diria «este predio salio
--  de este evento» siendo falso, y una huella de relleno diria «el emisor mando esto».
--  Es el mismo criterio con que `valuacion_predio` prefiere un motivo a un cero (#48) y
--  con que `V64` se nego a reescribir filas viejas: una marca falsa es peor que ninguna.
--
--  Asi que estas cinco sentencias FALLAN si hay una sola fila, y eso es lo que tienen
--  que hacer. Que hoy no la haya no se supone, se mide: `V4` y `V5` son de este
--  repositorio, no hay instalacion suya desplegada, y el UNICO rol que puede escribir
--  estas tablas —`rol_ingestor_catastro`— no tiene todavia ningun proceso que lo use
--  (P5C, hueco 3: «el ingestor de eventos no existe»). En `src/main` no hay un solo
--  `INSERT` sobre ninguna de las cinco; los que hay son fixtures de prueba, y esas
--  bases nacen vacias en cada corrida.
--
--  LAS CLAVES FORANEAS VAN `NOT VALID`, Y NO POR PRUDENCIA
--  ------------------------------------------------------
--  Es el cuarto hallazgo de DAT-01 §0: una clave foranea nueva sobre una tabla con RLS
--  NO SE PUEDE VALIDAR, porque validar es una consulta y el migrador corre sin contexto
--  de tenant. `NOT VALID` no debilita nada de lo que aqui importa: sigue comprobando
--  CADA `INSERT` y cada `UPDATE`, que es todo lo que hay, porque no hay filas previas
--  que reconciliar. Es lo mismo que `V64` midio para su `CHECK`, con el resultado
--  contrario: alli validar SI se podia y lo que lo impedia eran los datos; aqui no hay
--  datos y lo que lo impide es el motor.
-- ============================================================================

-- ----------------------------------------------------------------------------
--  1. El buzon gana la huella del cuerpo que aplico
-- ----------------------------------------------------------------------------

ALTER TABLE catastro_evento_aplicado
    ADD COLUMN huella char(64) NOT NULL;

COMMENT ON COLUMN catastro_evento_aplicado.huella IS
    'sha256 del cuerpo del evento tal como lo emitio `catastro`. Lo que compra: una '
    'reentrega del MISMO `evento_id` con OTRO contenido se puede ver, en vez de '
    'descartarse en silencio por la deduplicacion. No se recalcula sobre lo proyectado';

-- ----------------------------------------------------------------------------
--  2. El predio y sus fichas ganan de que evento salieron y con que contenido
--
--  La clave foranea es compuesta porque el buzon lo es: un `evento_id` solo identifica
--  un hecho DENTRO de su municipalidad. Sin `municipalidad_id` en la clave, una fila
--  podria apuntar al evento de la municipalidad vecina y RLS ni se enteraria — la
--  politica filtra lo que se LEE, no lo que una clave foranea alcanza.
-- ----------------------------------------------------------------------------

ALTER TABLE predio_ref
    ADD COLUMN evento_id uuid     NOT NULL,
    ADD COLUMN huella    char(64) NOT NULL;

ALTER TABLE predio_ref
    ADD CONSTRAINT predio_ref_evento_fk
        FOREIGN KEY (municipalidad_id, evento_id)
        REFERENCES catastro_evento_aplicado (municipalidad_id, evento_id)
        NOT VALID;

COMMENT ON COLUMN predio_ref.evento_id IS
    'El evento de `catastro` que escribio esta fila. Con clave foranea al buzon: una '
    'procedencia que no se puede seguir hasta el hecho que la produjo no es procedencia';
COMMENT ON COLUMN predio_ref.huella IS
    'sha256 del cuerpo de ese evento, emitido por `catastro`. No se recalcula aqui';

ALTER TABLE ficha_ref
    ADD COLUMN evento_id uuid     NOT NULL,
    ADD COLUMN huella    char(64) NOT NULL;

ALTER TABLE ficha_ref
    ADD CONSTRAINT ficha_ref_evento_fk
        FOREIGN KEY (municipalidad_id, evento_id)
        REFERENCES catastro_evento_aplicado (municipalidad_id, evento_id)
        NOT VALID;

COMMENT ON COLUMN ficha_ref.evento_id IS
    'El evento de `catastro` que escribio esta version de ficha. Es lo que contesta '
    '"por que esta ficha dice esta area" sin tener que preguntarle al otro sistema';
COMMENT ON COLUMN ficha_ref.huella IS
    'sha256 del cuerpo de ese evento, emitido por `catastro`. No se recalcula aqui';

-- ----------------------------------------------------------------------------
--  3. La valuacion gana el orden, y la corrida las otras dos
--
--  `valuacion_predio` ya traia `evento_id` y `huella` desde `V5` y le faltaba el orden.
--  Ahora ese `evento_id` ademas APUNTA al buzon, que es lo que lo vuelve comprobable:
--  hasta hoy era un uuid suelto que nadie podia resolver.
-- ----------------------------------------------------------------------------

ALTER TABLE valuacion_predio
    ADD COLUMN secuencia bigint NOT NULL,
    ADD CONSTRAINT valuacion_predio_secuencia_ck CHECK (secuencia >= 0);

ALTER TABLE valuacion_predio
    ADD CONSTRAINT valuacion_predio_evento_fk
        FOREIGN KEY (municipalidad_id, evento_id)
        REFERENCES catastro_evento_aplicado (municipalidad_id, evento_id)
        NOT VALID;

COMMENT ON COLUMN valuacion_predio.secuencia IS
    'En que orden llego el hecho. `valuacion_predio` no admite UPDATE (V5), asi que aqui '
    'la secuencia no evita que un hecho viejo pise a uno nuevo —eso ya lo impide la clave '
    'primaria— sino que dice CUAL de dos corridas del mismo ejercicio dejo esta fila';

ALTER TABLE valuacion_corrida
    ADD COLUMN evento_id uuid   NOT NULL,
    ADD COLUMN secuencia bigint NOT NULL,
    ADD CONSTRAINT valuacion_corrida_secuencia_ck CHECK (secuencia >= 0);

ALTER TABLE valuacion_corrida
    ADD CONSTRAINT valuacion_corrida_evento_fk
        FOREIGN KEY (municipalidad_id, evento_id)
        REFERENCES catastro_evento_aplicado (municipalidad_id, evento_id)
        NOT VALID;

COMMENT ON COLUMN valuacion_corrida.evento_id IS
    'El evento de cierre de corrida que escribio esta fila. La corrida SI se reemplaza '
    '(V5 le da UPDATE al ingestor), asi que sin esto no habria forma de decir cual de dos '
    'cierres del mismo ejercicio es el que esta puesto';
COMMENT ON COLUMN valuacion_corrida.secuencia IS
    'En que orden llego el cierre. Con `valuacion_corrida` reemplazable, es lo que impide '
    'que un cierre VIEJO que llega tarde pise al que ya esta';

-- ----------------------------------------------------------------------------
--  4. Lo que NO se hace aqui, y por que
--
--  No se le da `UPDATE` a nadie sobre `valuacion_predio`: sigue siendo un hecho sellado
--  (ADR-0027 §1) y anadirle una columna no cambia eso.
--
--  No se toca `pago_recibido` (`V8`). No es una proyeccion: es el buzon de `caja`, y ya
--  lleva su `pago_id` —el identificador que genera QUIEN EMITE— y el cuerpo entero en
--  `jsonb`. Lo que le falta —una huella de los bytes exactos del emisor— es una decision
--  de P5D que se tomo al reves a proposito («gana la lista blanca», y lo que se pierde
--  esta escrito en el javadoc de `PeticionDePago`); reabrirla aqui seria deshacerla de
--  lado, sin la discusion que la cerro.
-- ----------------------------------------------------------------------------
