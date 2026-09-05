# ARQ-03 — Estrategia multi-tenant

**Este es el riesgo número uno del proyecto.** Una fuga entre municipalidades no es un error de
cálculo que se corrige: es información tributaria de contribuyentes de un municipio expuesta a
otro, con consecuencias legales y sin vuelta atrás.

La estrategia está heredada del SRTM, verificada allí contra PostgreSQL, y aquí se reproduce
completa —incluidas sus dos trampas— porque el modelo de datos es distinto pero el mecanismo es
el mismo.

## 1. La decisión

**Esquema compartido, fila etiquetada, aislamiento en el motor** ([ADR-0002](adr/ADR-0002-estrategia-multi-tenant.md)).

Toda tabla de negocio lleva `municipalidad_id NOT NULL` y una política de Row Level Security que
la filtra. No hay una base por municipalidad ni un esquema por municipalidad.

**Por qué en el motor y no en la aplicación:** un filtro en la aplicación se olvida. Basta una
consulta escrita a mano, un `findAll`, un informe nuevo. La política de RLS se aplica a **todas**
las consultas de esa conexión, incluidas las que nadie revisó.

## 2. El camino del contexto

```
  token validado
        │  claim municipalidad_id            TenantContextFilter
        ▼
  TenantContext (ThreadLocal)
        │                                    TenantTransactionManager
        ▼  al abrir la transaccion:
  SET LOCAL app.municipalidad_id = ...
        │
        ▼  en cada consulta de esa transaccion:
  politica RLS: municipalidad_id = current_setting('app.municipalidad_id')::bigint
        │
        ▼  al devolver la conexion al pool:
  TenantConnectionGuard: si el parametro sigue puesto, la conexion se descarta
```

Cuatro eslabones, cada uno con su prueba:

| Eslabón | Componente | Prueba |
|---|---|---|
| Token → contexto | `TenantContextFilter` | Petición con encabezado y parámetro que dicen otra municipalidad |
| Contexto → transacción | `TenantTransactionManager` | Aislamiento con el pool real |
| Transacción → filas | Políticas RLS de `V6__rls.sql` | Prueba de aislamiento, bloqueante |
| Devolución al pool | `TenantConnectionGuard` | Prueba gemela **sin** guardia, que demuestra la fuga |

## 3. Las reglas

### 3.1 El identificador sale del token, y de ningún otro sitio

Nunca de un parámetro de consulta, de un encabezado, de un campo del cuerpo ni de la sesión del
navegador. Un token sin el claim recibe **403** y no llega al controlador: no existe valor por
omisión ni modo «sin municipalidad».

**Corolario:** ningún método de dominio recibe `municipalidadId`. Si el desarrollador no lo
maneja, no puede olvidarlo, y tampoco puede aceptarlo del cliente. Lo verifica ArchUnit.

### 3.2 `SET LOCAL`, jamás `SET SESSION`

`SET LOCAL` muere con la transacción. `SET SESSION` sobrevive al retorno de la conexión al pool
y la siguiente petición —de otra municipalidad— la reutiliza con el contexto ajeno puesto.

Se usa `set_config('app.municipalidad_id', ?, true)`, que es la forma parametrizada de
`SET LOCAL`: `SET` no admite parámetros de enlace y concatenar el valor invitaría a inyección.

El escáner del código fuente falla el build ante un `SET SESSION` literal.

### 3.3 Sin contexto, la consulta falla

Las políticas usan `current_setting('app.municipalidad_id')` **sin** el segundo argumento. Sin
contexto, la consulta lanza error en lugar de devolver vacío.

Es deliberado: un error ruidoso se detecta el primer día; un vacío silencioso se interpreta como
«este contribuyente no tiene deuda» y se descubre meses después.

**Los dos `SQLSTATE` posibles**, y la diferencia importa:

| Código | Cuándo | Por qué |
|---|---|---|
| `42704` *undefined_object* | Conexión nueva | El parámetro nunca existió |
| `22P02` *invalid_text_representation* | Conexión **reutilizada del pool** | Una vez fijado alguna vez, PostgreSQL deja el parámetro **definido**; al terminar la transacción vuelve a su valor previo, que es la **cadena vacía**. `''::bigint` falla |

Quien escriba la alerta de observabilidad tiene que reconocer los dos. Y quien escriba una
política tiene que recordar que sin contexto el valor es la cadena vacía: tratarla como «sin
filtro» abriría exactamente la fuga que la forma estricta impide.

### 3.4 `USING` **y** `WITH CHECK`

Con solo `USING`, un `INSERT` puede plantar filas en otra municipalidad aunque no pueda leerlas.
La prueba verifica que toda tabla de tenant tiene las dos cláusulas, y que un `INSERT` con
municipalidad ajena falla con `42501`.

### 3.5 Las particiones necesitan política propia

**Hallazgo verificado:** una partición **no hereda** `relrowsecurity` del padre, y al consultarla
directamente el filtro del padre no se aplica.

Dos mitigaciones, y la segunda es la que cierra el hueco:

1. RLS explícita en cada partición (defensa en profundidad).
2. **La aplicación no tiene ningún privilegio sobre ninguna partición.** Los `GRANT` se conceden
   solo sobre las tablas padre. Por eso **no** se usa `GRANT … ON ALL TABLES IN SCHEMA`: una
   partición nueva no recibe privilegios salvo que alguien se los conceda expresamente, y eso se
   ve en el diff.

### 3.6 El rol de aplicación no puede ser superusuario

**Hallazgo verificado:** un superusuario **omite RLS incluso con `FORCE ROW LEVEL SECURITY`**.

Consecuencias prácticas:

- El rol de aplicación se crea `NOSUPERUSER NOBYPASSRLS`, y la prueba lo comprueba.
- La aplicación **no se conecta como propietario** de las tablas: `FORCE` protege del propietario
  en las políticas, pero el propietario puede alterarlas.
- **La prueba de aislamiento no usa la conexión que Testcontainers entrega por omisión**, que es
  de superusuario. Una prueba escrita sobre ella pasa en verde sin verificar nada. La prueba lo
  demuestra: con el mismo contexto fijado, el superusuario ve las dos municipalidades y
  `kamayuk_app` una.

## 4. Roles de base de datos

| Rol | Para qué | Privilegios |
|---|---|---|
| `kamayuk_owner` | Migraciones. **Único que hace DDL** | Propietario de las tablas; `CREATE` en el esquema |
| `kamayuk_app` | La aplicación | `SELECT, INSERT, UPDATE` sobre tablas padre; **sin `DELETE`**; solo `SELECT, INSERT` en el libro de asientos y en auditoría |
| `kamayuk_readonly` | Reportes y réplica de lectura | `SELECT` sobre tablas padre |
| `rol_carga_parametros` | Carga de catálogos normativos | Escritura sobre `parametro_tributario` y tablas de valuación |
| `kamayuk_respaldo` | El respaldo con wal-g (INF-08, #155) | `pg_read_all_settings` y `EXECUTE` sobre `pg_backup_start`/`pg_backup_stop`; sin `CONNECT` a la base del padrón |
| `kamayuk_monitor` | Métricas de `postgres_exporter` (#156) | El rol predefinido `pg_monitor`: vistas de estadísticas, ni una fila del padrón |

Los cuatro primeros los crea `db/roles/crear-roles.sql` antes de la primera migración; los dos
últimos nacen solo en el clúster, en `infra/componentes/inicializacion/40-rol-de-respaldo.sh` y
`50-rol-de-monitoreo.sh` —en el compose de un portátil no hay respaldo ni recolección de
métricas, y por eso ahí no existen—.

Sin pertenencia entre roles: ser miembro de otro permitiría un `SET ROLE` que borra la
separación.

## 5. Excepciones admitidas

Solo dos, y ninguna desactiva RLS:

1. **`municipalidad`** — el registro de tenants. Lectura para todos (un proceso masivo itera
   municipalidad por municipalidad), escritura solo para `kamayuk_owner`.
2. **Catálogos con política propia** — `parametro_tributario` con `municipalidad_id IS NULL`, las
   tres tablas de valuación que V55 volvió nacionales por ADR-0017 (`valor_unitario_edificacion`,
   `depreciacion` y `valor_referencial_vehiculo`) y `respaldo`, que no lleva `municipalidad_id`
   porque una copia de seguridad es del clúster entero. En las que sí llevan la columna, la
   política admite `municipalidad_id IS NULL` **o** la del contexto, y aquí sí se usa la forma de
   dos argumentos de `current_setting`, porque estos catálogos deben poder leerse sin contexto
   durante el arranque. No hay fuga posible: sin contexto, la comparación da `NULL` y las filas
   locales quedan invisibles. Ojo: el CIIU y los códigos de infracción de tránsito **no** están
   aquí —llevan `municipalidad_id NOT NULL` (V4) y son tablas de tenant—. La lista normativa de
   catálogos es `TABLAS_DE_CATALOGO`, en el código de la prueba de aislamiento.

Toda tabla nueva es de tenant, de catálogo o exenta, y la lista de exentas tiene **una** entrada:
`flyway_schema_history`. La prueba falla si aparece una tabla sin clasificar.

## 6. El punto débil declarado, y cómo dejó de serlo

**Era el portal del contribuyente.** Su usuario no pertenece a una municipalidad: es un ciudadano
que consulta su deuda. Este documento decía que el contexto tendría que salir **del objeto
consultado** —el predio, la papeleta—, lo que invierte el flujo de §2 y fija el `SET LOCAL` con un
dato que elige quien pregunta. Era la decisión D-07, y se cerró el 2026-08-29 con
[ADR-0020](adr/ADR-0020-la-sesion-del-ciudadano.md) **cambiando la premisa**, no resolviéndola:

> **No es una consulta multi-municipalidad; son *N* consultas de una municipalidad cuya unión se
> filtra a un documento firmado.**

El ciudadano tiene **realm propio** con **emisor distinto** (`sgtm-ciudadano`), y su token lleva
`tipo_documento` y `numero_documento` como claims firmados. El sujeto deja de ser un parámetro y
pasa a ser un claim validado, exactamente como `municipalidad_id` lo es para el funcionario (§3.1).
El contexto no sale del objeto consultado: sale del **registro de municipalidades**, una a la vez.

```
token del ciudadano  →  claims tipo_documento + numero_documento
  → por cada municipalidad ACTIVA del registro:
        transacción propia → SET LOCAL app.municipalidad_id = N → RLS
```

Cada rama queda sujeta a la misma política que cualquier consulta de ventanilla, y la base sigue
sin poder cruzar municipalidades. Lo único nuevo es que el proceso **recorre** el registro, que es
la excepción 1 de §5 y lo que ya hace todo proceso masivo del perfil `batch`.

Lo que sostiene ese recorrido, y se verifica:

1. **Un solo componente lo hace.** `RecorridoPorMunicipalidades` (`sgtm-plataforma`) es el único
   componente del perfil `web` autorizado a mover `TenantContext` dentro de una petición. Es regla
   de ArchUnit, con su clase de muestra que la viola: hasta entonces el invariante existía —los
   demás llamadores son todos `@Profile("batch")`— y no lo comprobaba nadie.
2. **El contexto se limpia entre ramas, aunque la rama falle.** Sin eso, la rama siguiente devuelve
   datos **reales** de la municipalidad anterior bajo la etiqueta de otra. Es la fuga que no se ve.
3. **Ninguna transacción envolvente**, y por tanto ninguna conexión vuelve al pool con
   `app.municipalidad_id` puesto: lo comprueba el guardia del pool, igual que para cualquier
   petición.
4. **Dos cadenas de seguridad, no una con excepciones.** `/api/v1/portal/**` valida solo contra el
   emisor del ciudadano y la cadena general solo contra el de funcionarios: un token de funcionario
   no autentica en el portal, y uno de ciudadano no autentica en ninguna otra ruta.

**El agujero que esto abre está declarado y es ruidoso**: bajo `/api/v1/portal/**` no corre
`TenantContextFilter` sino `DocumentoCiudadanoContextFilter`, así que un endpoint de funcionario
servido ahí por descuido correría **sin contexto de tenant** y toda consulta fallaría en la base
(§3.3), que es el comportamiento correcto.

Quién acredita que ese documento es de quien lo presenta es **D-15**, decidida el mismo día:
enrolamiento en ventanilla (ADR-0020 §4).

## 7. Qué hacer al agregar una tabla

1. ¿Lleva `municipalidad_id NOT NULL`? Entonces necesita RLS con `USING` y `WITH CHECK`, y el
   `GRANT` correspondiente. La prueba lo exige sola.
2. ¿Es catálogo? Hay que declararla en `TABLAS_DE_CATALOGO` **en el código de la prueba**, y
   darle su política. Eso obliga a justificarlo en el PR.
3. ¿Es partición? Repetir el bloque de RLS explícita y **no concederle privilegios**.
4. Todo índice selectivo empieza por `municipalidad_id`: la política añade esa condición a cada
   consulta.
