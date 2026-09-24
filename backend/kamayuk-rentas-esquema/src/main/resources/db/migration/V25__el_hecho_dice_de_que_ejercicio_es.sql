-- ============================================================================
--  V25 — CADA HECHO DE LA PRESCRIPCION DICE DE QUE EJERCICIO ES (#334)
--
--  QUE PASABA
--  ----------
--  Una solicitud de prescripcion pide un RANGO de ejercicios y alega hechos —pagos
--  parciales, reclamaciones— que interrumpen (art. 45) o suspenden (art. 46) el
--  plazo. Esos articulos actuan sobre el plazo de UNA deuda concreta: el pago
--  parcial del predial 2019 interrumpe la prescripcion del 2019, no la del 2021.
--
--  Y ninguna capa guardaba a que ejercicio pertenecia un hecho: ni el tipo, ni el
--  cuerpo de la peticion, ni esta tabla. `DeclararPrescripcion` aplicaba la misma
--  lista a cada ejercicio del rango. Medido en #334: el pago del 2019 reiniciaba el
--  plazo del 2020 y se negaba una prescripcion que procedia (NO_PROCEDE en vez de
--  PROCEDE_EN_PARTE), y en un rango hasta 2021 la solicitud entera salia 422.
--
--  QUE HACE ESTA MIGRACION
--  -----------------------
--  Da a `prescripcion_hecho` la columna `ejercicios`: de que ejercicios es la deuda
--  que el hecho interrumpe o suspende. Un arreglo y no una tabla hija porque es un
--  atributo del hecho —se escribe con el y no se consulta por separado—, y una tabla
--  mas pediria su politica RLS, sus permisos y su clave foranea para guardar dos o
--  tres numeros. El tipo es el dominio `ejercicio`, asi que cada elemento pasa el
--  mismo `CHECK` de rango que cualquier otra columna de ejercicio.
--
--  LAS FILAS ANTERIORES A V25 SE QUEDAN EN NULO, Y ES UNA DECISION
--  --------------------------------------------------------------
--  NULO significa «resolucion anterior a V25»: su computo aplico el hecho a todo el
--  rango, y se conserva tal como se resolvio (#334 deja fuera revisarlas: revisar
--  una resolucion es un acto de la administracion). No se rellena con el rango
--  entero, aunque eso describiria lo que el codigo de entonces HIZO, porque en la
--  columna se leeria como lo que el hecho ERA —de que deuda era el pago—, que es
--  justo lo que nadie declaro. Es el trato que `V21` dio a `modalidad` y `V10` a
--  `motivo_anulacion`. Y no se podria de todos modos: el migrador corre sin contexto
--  de tenant y esta tabla tiene `FORCE ROW LEVEL SECURITY` (lo dice `V10`).
--
--  LO QUE LA BASE **NO** PUEDE VIGILAR, Y QUIEN LO VIGILA
--  ------------------------------------------------------
--  Que un hecho NUEVO no quede sin alcance, y que sus ejercicios esten dentro del
--  rango de su solicitud. Lo primero no lo distingue una restriccion de una fila
--  anterior a V25; lo segundo cruza tablas. Lo vigila `DeclararPrescripcion`: en un
--  rango de mas de un ejercicio, un hecho sin alcance es 422 nombrando el hecho, y
--  uno con un ejercicio fuera del rango tambien.
--
--  Lo que SI vigila el `CHECK`: que un alcance declarado nombre al menos un
--  ejercicio y ninguno vacio. `'{}'` diria «no es de ninguna deuda», que no es lo
--  mismo que no haberlo declarado. La columna nace aqui, asi que toda fila existente
--  es nula y el `CHECK` se valida sin escape (el mismo razonamiento que `V21`).
-- ============================================================================

ALTER TABLE prescripcion_hecho ADD COLUMN ejercicios ejercicio[];

ALTER TABLE prescripcion_hecho
    ADD CONSTRAINT prescripcion_hecho_ejercicios_ck
        CHECK (ejercicios IS NULL
               OR (cardinality(ejercicios) > 0 AND array_position(ejercicios, NULL) IS NULL));

COMMENT ON COLUMN prescripcion_hecho.ejercicios IS
    'De que ejercicios es la deuda que este hecho interrumpe (art. 45) o suspende (art. 46): el '
    'computo de cada ejercicio de la solicitud solo lee los hechos que lo nombran (#334). NULA '
    'significa «resolucion anterior a V25», cuyo computo aplico el hecho a todo el rango; no se '
    'rellena porque de aquellos hechos no consta de que deuda eran.';
