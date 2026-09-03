# Migraciones de `rentas`

**Vacio a proposito, y no es un olvido.**

El esquema de este sistema nace como un `V1__baseline.sql` propio, no repartiendo las 68
migraciones del monolito: `V1` pertenece a dos sistemas y `V6`/`V7` a los cuatro, asi que
repartirlas no se puede
([ADR-0032](../../../../../../docs/30-arquitectura/adr/ADR-0032-el-esquema-nace-en-baseline.md)
§1 y §3). Lo que abarata esa decision esta medido: **no hay datos reales en ningun ambiente**, asi
que ninguna extraccion necesita plan de migracion ni conciliacion de saldos (GOB-05 §7.1).

Lo que **si** esta desde el primer dia es la prueba de aislamiento, y no espera al baseline:
verifica el mecanismo —los cuatro roles, RLS con `FORCE`, y que el rol de la aplicacion no sea
superusuario— sobre una tabla que ella misma crea. Cuando el baseline llegue, su censo pasa a
tener tablas que censar sin cambiar una linea, y la exencion
`sinEsquemaTodavia` de `AislamientoMultiTenantTest` **se pone roja sola** pidiendo que se retire.
