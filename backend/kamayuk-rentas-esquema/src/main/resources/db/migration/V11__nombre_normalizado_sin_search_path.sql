-- ============================================================================
--  V11 — `nombre_normalizado` deja de depender del `search_path` de la sesion (C-4)
--
--  EL DEFECTO, MEDIDO Y NO SUPUESTO
--  --------------------------------
--  `V1` declara la funcion asi —heredada tal cual del `V11` del monolito—:
--
--      SELECT regexp_replace(
--                 lower(unaccent('unaccent'::regdictionary, coalesce(texto, ''))),
--                 '\s+', ' ', 'g');
--
--  Los DOS nombres que ahi aparecen se resuelven por `search_path`: la funcion
--  `unaccent(regdictionary, text)`, que vive en `public` porque ahi la instala la
--  extension, y el literal `'unaccent'::regdictionary`, cuya conversion de entrada
--  busca el diccionario por `search_path` igual que un nombre de tabla.
--
--  Con el `search_path` de una sesion normal —«$user», public— eso funciona, y por
--  eso llevaba aqui desde el monolito sin que nadie lo notara. Lo que rompe es
--  cualquier camino que restrinja el `search_path`, y hay uno que este producto
--  usa: **`pg_dump` lo vacia**. Todo volcado empieza por
--
--      SELECT pg_catalog.set_config('search_path', '', false);
--
--  Y entonces, al restaurar, PostgreSQL intenta INSERTAR EN LINEA el cuerpo de la
--  funcion para poder construir el indice, no encuentra el diccionario, y falla.
--
--  MEDIDO CONTRA PostgreSQL 16.15 —LA VERSION QUE ESTE PRODUCTO DESPLIEGA—:
--
--      $ pg_dump -Fc -d rentas ... && pg_restore -d restaurada ...
--      pg_restore: error: could not execute query:
--          ERROR:  text search dictionary "unaccent" does not exist
--        CONTEXT:  SQL function "nombre_normalizado" during inlining
--        Command was: CREATE INDEX contribuyente_nombre_trgm_ix ON public.contribuyente
--                     USING gin (public.nombre_normalizado((nombre_razon_social)::text)
--                                public.gin_trgm_ops);
--      pg_restore: warning: errors ignored on restore: 1
--
--  **Y `pg_restore` termina con codigo de salida 0.** La base restaurada se queda
--  SIN `contribuyente_nombre_trgm_ix` y nada lo dice: es un aviso, no un error, y
--  el sintoma —«la busqueda del padron va lenta»— aparece meses despues y en otro
--  sitio. Con esta migracion aplicada, la misma ida y vuelta da 0 errores y el
--  indice se restaura.
--
--  Esto NO es un defecto de PostgreSQL 18. Se descubrio buscando por que el esquema
--  no aplica en 18 —ahi el mismo cuerpo mata la migracion, porque PG 17+ restringe
--  ademas el `search_path` de `CREATE INDEX`—, pero la restauracion logica ya estaba
--  rota en 16. Lo que 18 hizo fue ensanchar la superficie de un defecto que ya habia.
--
--  POR QUE UNA MIGRACION NUEVA Y NO EDITAR `V1`
--  --------------------------------------------
--  Porque `V1` ya corrio. Editarla cambia su suma de Flyway y deja «la base de al
--  lado distinta sin que nada se ponga rojo», que es el modo de fallo que la propia
--  cabecera de `V1` describe. `CREATE OR REPLACE` sirve igual: la restauracion
--  reproduce el esquema FINAL, asi que lo que se vuelca es este cuerpo y no el de
--  `V1`.
--
--  LO QUE ESTE REEMPLAZO **NO** CAMBIA, Y SE MIDIO
--  -----------------------------------------------
--  1. **El valor.** Las dos formas devuelven lo mismo: `nombre_normalizado('PEÑA
--     GARCÍA')` da `pena garcia` antes y despues. Por eso no hay que reconstruir el
--     indice ni reescribir ninguna columna generada: los valores almacenados siguen
--     siendo los correctos.
--  2. **El indice.** `CREATE OR REPLACE` sobre una funcion usada por un indice se
--     acepta, y el indice queda `indisvalid = t, indisready = t`. Comprobado.
--  3. **El plan.** `EXPLAIN (ANALYZE, BUFFERS)` de la consulta real de
--     `ContribuyenteRepositoryJdbc` —60 000 contribuyentes en dos municipalidades,
--     como `sgtm_app` y con RLS activa— sale IDENTICO antes y despues: los mismos
--     nodos, el mismo `Index Cond` y `shared hit=1133` en los dos.
--
--  LO QUE SIGUE MAL Y ESTA MIGRACION NO ARREGLA, DICHO AQUI
--  --------------------------------------------------------
--  Medir ese plan destapo otra cosa, y conviene que quede escrita donde se lea:
--  **`contribuyente_nombre_trgm_ix` no lo usa nadie bajo RLS.** `similarity_op` —el
--  operador `%`— tiene `proleakproof = f`, asi que PostgreSQL no lo evalua antes de
--  la politica y no lo puede empujar al indice; el plan dice «Index» igual, pero es
--  `Bitmap Index Scan on contribuyente_pk` por la condicion de la propia politica, y
--  descarta 29 750 filas de 30 000 en el `Filter`. Como superusuario —que omite RLS—
--  el MISMO `%` si usa el indice GIN. Es el quinto hallazgo de DAT-01 §0 otra vez,
--  con la agravante de que la consulta de produccion ni siquiera usa `%`, sino
--  `similarity(...) >= 0.30`, que `gin_trgm_ops` no sabe responder ni sin RLS.
--
--  No se arregla aqui a proposito: cambiar como busca el padron es otro trabajo, con
--  su medida y su decision. Lo que si arregla esta migracion es que el indice
--  sobreviva a una restauracion, para que el dia que ese otro trabajo lo haga util
--  el indice este.
-- ============================================================================

CREATE OR REPLACE FUNCTION public.nombre_normalizado(texto text)
 RETURNS text
 LANGUAGE sql
 IMMUTABLE PARALLEL SAFE STRICT
AS $function$
    SELECT regexp_replace(
               lower(public.unaccent('public.unaccent'::regdictionary, coalesce(texto, ''))),
               '\s+', ' ', 'g');
$function$
;

COMMENT ON FUNCTION public.nombre_normalizado(text) IS
    'Minusculas, sin tildes y sin espacios repetidos. IMMUTABLE para poder indexarla. '
    'La funcion y el diccionario van CUALIFICADOS con su esquema desde C-4: los dos se '
    'resuelven por search_path, y pg_dump lo vacia, de modo que sin cualificar la '
    'restauracion logica pierde contribuyente_nombre_trgm_ix con codigo de salida 0';
