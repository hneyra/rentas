-- ============================================================================
--  V22 — LA PAPELETA SE ANULA, Y ESO YA ES CIERTO (#267)
--
--  QUE CORRIGE, Y POR QUE HACE FALTA UNA MIGRACION PARA UN COMENTARIO
--  -----------------------------------------------------------------
--  `V20` dejo escrito en el comentario de la columna: «ANULADA y PRESCRITA no
--  se derivan de nada —son actos— y todavia no existe el acto que las escriba».
--  Desde #267 la primera mitad de esa frase es FALSA: `AnularPapeleta` existe,
--  lo publica `POST /transito/papeletas/{numero}/anulacion`, y es la unica
--  escritura que mueve esta columna.
--
--  El comentario de una columna es lo que lee quien abre la base sin el codigo
--  delante, asi que dejarlo mintiendo es peor que no tenerlo. Y `V20` no se
--  puede editar: Flyway valida la suma de comprobacion de cada migracion ya
--  aplicada, de modo que tocarla haria fallar el arranque de TODA base
--  existente. Se corrige con una migracion nueva, que es justo para lo que
--  sirven.
--
--  QUE NO HACE, Y ESO ES LO DECIDIDO
--  ---------------------------------
--  1. NO estrecha `papeleta_estado_check`. Decidido que no en #259, con la
--     medida: este enumerado es tambien el vocabulario de LECTURA de una
--     columna que un padron MIGRADO trae llena, `papeleta` tiene FORCE ROW
--     LEVEL SECURITY y el migrador corre sin contexto de tenant —asi que no
--     puede ni leer ni normalizar esas filas—, y un CHECK estrechado las
--     volveria intocables: un NOT VALID sigue comprobando toda fila que se
--     ACTUALIZA, de modo que `UPDATE papeleta SET numero` (#46) fallaria sobre
--     una papeleta migrada que constase 'PAGADA'. Lo vigila
--     `PapeletaRepositoryJdbcTest.VocabularioDeLectura`, y muerde.
--
--  2. NO toca el privilegio. `V20` ya lo habia dejado puesto —`GRANT UPDATE
--     (numero, estado)`— «para el acto futuro que pueda mover el estado», y el
--     acto futuro es este. La columna que se escribe es exactamente la que el
--     privilegio permitia, y ninguna otra: lo que el inspector escribio en la
--     calle y firmo el infractor sigue sin poder corregirse desde la
--     aplicacion.
--
--  3. NO anade el acto de PRESCRITA, y la medida esta en `AnularPapeleta`:
--     `DeclararPrescripcion` no nombra ninguna papeleta —ni en su firma ni en
--     su cuerpo—, marca PRESCRITO los VALORES que alcanza, y una papeleta solo
--     tiene valor si paso la corrida masiva con su resolucion de multa dictada
--     y notificada. Escribirlo ademas aqui dejaria una segunda verdad que
--     discrepa de la primera en toda papeleta sin valor.
-- ============================================================================

COMMENT ON COLUMN papeleta.estado IS
    'En que punto esta la papeleta. Este sistema escribe DOS de los siete valores, y ningun otro: '
    'IMPUESTA en el INSERT, como nace toda papeleta, y ANULADA desde #267 —el unico UPDATE de esta '
    'columna, que escribe AnularPapeleta y publica POST /transito/papeletas/{numero}/anulacion—. '
    'Anular no borra ni edita nada mas: la fila se sigue leyendo entera y ademas se da de baja en '
    'el libro lo que la papeleta cargo, porque si no quedaria anulada y debiendo. NOTIFICADA, '
    'RESUELTA, PAGADA y COACTIVA se DERIVAN de otros hechos y no se escriben aqui: la diligencia '
    'de la resolucion de gerencia, la existencia de la resolucion, el libro de cuenta corriente y '
    'el pase a coactiva del valor. PRESCRITA tampoco se escribe, y #267 lo decidio con la medida: '
    'la prescripcion se dice en el VALOR —DeclararPrescripcion marca PRESCRITO lo que alcanza— y '
    'no en la papeleta, que ni siquiera aparece en la firma de ese acto. El CHECK sigue admitiendo '
    'los siete porque un padron MIGRADO puede traerlos, y bajo FORCE RLS ninguna migracion puede '
    'normalizar esas filas (#259).';
