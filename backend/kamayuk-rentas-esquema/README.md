# kamayuk-rentas-esquema

El esquema del SGTM como migraciones de Flyway, y la **prueba de aislamiento multi-tenant**, que
es bloqueante.

No es un contexto acotado: es infraestructura de datos común a todos. Y **no depende de Spring** a
propósito: la prueba verifica el motor de base de datos, no la aplicación; levantar un contexto de
Spring solo agregaría formas de que pase en verde por el motivo equivocado.

## Contenido

```
src/main/resources/db/
├── roles/crear-roles.sql          NO es migracion: se ejecuta antes, como superusuario
└── migration/
    ├── V1__nucleo_y_catastro.sql
    ├── V2__rentas_y_cuenta_corriente.sql
    ├── V3__cobranza_valores_y_coactiva.sql
    ├── V4__sanciones_y_licencias.sql
    ├── V5__seguridad_y_auditoria.sql
    ├── V6__rls.sql                Row Level Security en todas las tablas
    ├── V7__privilegios.sql        GRANT solo sobre tablas padre; sin DELETE
    └── …                          hasta V57 (48 migraciones hoy; una fila por
                                   migración en DAT-01 §1)

src/testFixtures/                  El arranque de la base, reutilizado por los demás módulos
src/test/                          La prueba de aislamiento
```

Hoy, con V56: 113 tablas de tenant, 6 catálogos, 5 tablas particionadas por ejercicio. Las
cifras vivas salen de las propias migraciones, y la clasificación normativa —tenant, catálogo,
exenta— vive en el código de la prueba (`TABLAS_DE_CATALOGO`, `TABLAS_EXENTAS`).

## La prueba

```bash
./gradlew :kamayuk-rentas-esquema:test
```

> **La prueba se conecta como el rol `sgtm_app`, creado en su arranque. No cambies eso.**
>
> La conexión que Testcontainers entrega por omisión es de **superusuario**, y un superusuario
> **omite Row Level Security incluso con `FORCE ROW LEVEL SECURITY`**. Una prueba escrita sobre
> esa conexión pasa en verde **sin verificar nada**.
>
> Eso no se afirma: se demuestra. `Trampa#superusuarioOmiteRlsPorEsoLaPruebaNoUsaEsaConexion`
> verifica que, con el mismo contexto fijado, el superusuario ve las dos municipalidades y
> `sgtm_app` una.

Los fixtures provisionan la base como se provisiona un ambiente real: crean los cuatro roles con
su clave, migran conectados como `sgtm_owner` —el único con DDL— y a partir de ahí entregan
conexiones por rol.

Esa clave **no se sortea**, y desde #698 es deliberado: `ALTER ROLE` es del clúster y no de la base,
así que dos corridas contra el mismo motor por el camino de `kamayuk.pruebas.postgres.url` se la
pisaban. Se **deriva** del identificador del clúster y de la credencial con que se provisiona —dos
tareas escriben el mismo valor— y el provisionamiento entero se serializa con un candado de
asesoramiento tomado siempre en la base `postgres`. Qué garantiza eso y qué no:
[`backend/README.md`](../README.md).

## Al agregar una tabla

1. **¿Lleva `municipalidad_id NOT NULL`?** `V6` le pone RLS sola: descubre las tablas por esa
   columna, no por una lista. Solo hay que agregarle su `GRANT` en `V7`.
2. **Hay que sembrarla en `DatosDePrueba`.** Si no, la prueba falla diciendo que la municipalidad A
   no ve filas suyas. Es deliberado: con la tabla vacía, «no se ve nada de B» sería cierto sin
   probar nada.
3. **Si es catálogo** —sin `municipalidad_id NOT NULL`— hay que declararla en `TABLAS_DE_CATALOGO`
   en el código de la prueba y darle su política. Eso obliga a justificarlo en el PR.

## Al agregar una partición

1. Repetir el bloque de RLS explícita de `V6__rls.sql`. Una partición **no hereda**
   `relrowsecurity` del padre.
2. **No concederle ningún privilegio.** Es lo que cierra el hueco de verdad, y es imposible de
   olvidar porque una partición nueva no recibe privilegios salvo que alguien se los conceda.

La prueba falla si aparece una partición sin RLS o con privilegios.

## Demostrar que la prueba puede fallar

No basta con que esté escrita. Las dos mutaciones más baratas, verificadas:

| Mutación | Qué se pone en rojo |
|---|---|
| Quitar `WITH CHECK` de la política de tenant en `V6` | «toda tabla de tenant tiene politica con USING y WITH CHECK», en todas las tablas de tenant (113 hoy, con V56) |
| Agregar `GRANT SELECT ON determinacion_2026 TO sgtm_app` en `V7` | «sgtm_app no tiene ningun privilegio sobre ninguna particion» y «el acceso directo a una particion falla» |

Conviene repetirlas cada vez que se toque el DDL de RLS o de privilegios.
