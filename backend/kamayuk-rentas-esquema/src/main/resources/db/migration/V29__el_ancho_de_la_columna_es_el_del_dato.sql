-- ============================================================================
--  V29 — EL ANCHO DE LA COLUMNA ES EL DEL DATO QUE EL SISTEMA GENERA (#408)
--
--  QUE PASABA
--  ----------
--  Tres columnas eran mas estrechas que el dato que el propio sistema compone o
--  admite. El ancho vivia en dos sitios —el dominio y el DDL— y nada los
--  comparaba:
--
--    * `notificacion.numero varchar(20)`. La diligencia de un acto coactivo se
--      numera con el numero del acto, una barra y el intento, y el del acto
--      lleva el tipo (`MEDIDA_CAUTELAR-2026-000001`, que cabe en
--      `acto_coactivo.numero varchar(40)`). `EMBARGO-2026-000001/1` mide 21 y
--      `MEDIDA_CAUTELAR-2026-000001/1` 29: seis de los diez tipos de acto no se
--      podian notificar nunca (RF-103), y el dominio lo rechazaba con 422 sin
--      que nada de la peticion permitiera corregirlo.
--
--    * `edificacion_terreno.cod_catastral varchar(20)`. El dominio
--      `cod_catastral` de este mismo esquema es `varchar(25)` con
--      `CHECK '^[0-9]{18,25}$'`, y D-10 duda entre 21 posiciones (el prototipo)
--      y 23 (el manual): las dos pasaban de 20, asi que ningun codigo real cabia
--      y la base lo rechazaba con 22001, que el borde contestaba 500.
--
--    * `corrida_predial_observado.nombre varchar(200)` y
--      `corrida_predial.sector varchar(10)`. El nombre se copia entero de
--      `nombre_razon_social`, que admite 240; el sector se compara con
--      `predio_ref.sector_codigo varchar(20)` (V4). El rastro de la corrida se
--      escribe al final, con la emision ya confirmada, y un 22001 alli la
--      contestaba 500 y perdia la lista de observados.
--
--  QUE HACE
--  --------
--  Ensancha las cuatro columnas hasta el ancho del dato del que salen, y el
--  dominio Java topa con la misma cifra:
--
--    columna                           antes         ahora          la cifra del dominio
--    --------------------------------  ------------  -------------  -------------------------------
--    notificacion.numero               varchar(20)   varchar(45)    NotificacionCoactiva
--                                                                     .NUMERO_MAXIMO = 40 + 1 + 4
--    edificacion_terreno.cod_catastral varchar(20)   cod_catastral  TerrenoDelFue: 18 a 25 digitos
--    corrida_predial_observado.nombre  varchar(200)  varchar(240)   CorridaDeEmision.Observado
--                                                                     .NOMBRE_MAXIMO = 240
--    corrida_predial.sector            varchar(10)   varchar(20)    CorridaDeEmision.SECTOR_MAXIMO
--
--  El 45 es el numero del acto (`ActoCoactivo.NUMERO_MAXIMO`, 40), la barra y
--  cuatro digitos de intento. `notificacion` es polimorfica: los valores y las
--  resoluciones de sanciones escriben ahi tambien, con su propio tope de 20, y
--  una columna mas ancha que su dato no les cambia nada.
--
--  El codigo catastral pasa a ser el DOMINIO, no un `varchar(25)` suelto: asi el
--  `CHECK` es el mismo que el de `predio_ref.codigo_ref_catastral` (V4) y no una
--  segunda copia del patron. Mientras D-10 siga abierta no se fija un largo
--  unico: el dominio admite de 18 a 25, y en ese tramo caben las dos lecturas.
--
--  POR QUE UNA MIGRACION NUEVA Y NO V1
--  -----------------------------------
--  V1 ya esta aplicada, y Flyway valida su suma de comprobacion: editarla hace
--  fallar el arranque de toda base existente.
--
--  POR QUE NO ES DESTRUCTIVO
--  -------------------------
--  No se borra ni se reescribe ni una fila (regla 4, RNF-051). Ensanchar un
--  `varchar` es un cambio de catalogo: PostgreSQL no reescribe la tabla ni
--  reconstruye sus indices. Pasar `cod_catastral` al dominio si REVISA cada
--  fila contra el `CHECK`: una que ya estuviera escrita fuera del patron —de
--  menos de 18 digitos, o con letras— hace fallar la migracion nombrando
--  `cod_catastral_check`, en vez de pasar en silencio. Una de 21 o mas no puede
--  existir: no cabia.
-- ============================================================================

ALTER TABLE notificacion ALTER COLUMN numero TYPE varchar(45);

ALTER TABLE edificacion_terreno ALTER COLUMN cod_catastral TYPE cod_catastral;

ALTER TABLE corrida_predial_observado ALTER COLUMN nombre TYPE varchar(240);

ALTER TABLE corrida_predial ALTER COLUMN sector TYPE varchar(20);

COMMENT ON COLUMN notificacion.numero IS
    'Identifica la diligencia; unico por objeto. La de un acto coactivo es el numero del acto '
    '(acto_coactivo.numero, hasta 40), una barra y el intento: de ahi el 45 (#408), que es '
    'NotificacionCoactiva.NUMERO_MAXIMO.';
COMMENT ON COLUMN edificacion_terreno.cod_catastral IS
    'El codigo de referencia catastral del terreno, cuando lo tiene. Es el dominio cod_catastral, '
    'de 18 a 25 digitos (#408): mientras D-10 siga abierta caben las 21 posiciones del prototipo '
    'y las 23 del manual. TerrenoDelFue valida el mismo patron para que un error salga 422.';
COMMENT ON COLUMN corrida_predial_observado.nombre IS
    'El nombre del observado, copiado entero de contribuyente.nombre_razon_social: de ahi los 240 '
    '(#408), que es CorridaDeEmision.Observado.NOMBRE_MAXIMO.';
COMMENT ON COLUMN corrida_predial.sector IS
    'El sector del alcance SECTOR. Se compara con predio_ref.sector_codigo varchar(20): de ahi los '
    '20 (#408), que es CorridaDeEmision.SECTOR_MAXIMO.';
