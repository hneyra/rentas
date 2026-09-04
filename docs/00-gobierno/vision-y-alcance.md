# GOB-01 — Visión y alcance

## 1. Qué es este proyecto

Reimplementar el **Sistema de Gestión Tributaria Municipal (SGTM)** —hoy una aplicación de
escritorio en Visual Basic .NET sobre SQL Server 2008— como sistema web multi-municipal, con el
manual de usuario existente como especificación funcional y la arquitectura del
[SRTM](../../../srtm/CLAUDE.md) como base técnica.

El manual describe un sistema en producción, con 231 pantallas fotografiadas y su
comportamiento explicado. Eso es una ventaja rara: **el alcance funcional no hay que inventarlo,
hay que leerlo.** Lo que el manual no da —y este proyecto sí necesita— es el detalle normativo
del cálculo (§4) y la arquitectura.

## 2. Qué se conserva del original

| Del manual | Se conserva |
|---|---|
| Los 12 módulos y sus 134 opciones | Íntegros. El catálogo está en [NEG-03](../10-negocio/catalogo-de-opciones.md) |
| La nomenclatura en castellano de campos, documentos y trámites | Literal. No se «moderniza» el vocabulario del manual |
| El modelo de seguridad: módulos, grupos, usuarios, accesos, permisos, miembros | Íntegro, como contexto acotado propio ([ARQ-01 §3.12](../30-arquitectura/contextos-acotados.md)) |
| La auditoría: usuario, máquina, IP, fecha y **observación obligatoria** en cada modificación | Íntegra, y reforzada ([DAT-02](../40-datos/auditoria-e-historico.md)) |
| El histórico de fichas catastrales: modificar genera versión, no sobrescribe | Íntegro |
| La transaccionalidad «todo o nada» de los procesos | Íntegra |

## 3. Qué cambia respecto del original

| Original | SGTM nuevo | Motivo |
|---|---|---|
| Una instalación por municipalidad | **Multi-municipal**: una instalación, muchas municipalidades, aisladas por RLS | Costo de operación; ver [ADR-0002](../30-arquitectura/adr/ADR-0002-estrategia-multi-tenant.md) |
| Cliente Windows en la red municipal | Aplicación web | El manual ya lo anticipaba como posible |
| Seguridad integrada con el dominio Windows | OpenID Connect; el identificador de municipalidad viaja en el token | [ADR-0005](../30-arquitectura/adr/ADR-0005-identidad-y-acceso.md) |
| SQL Server 2008 | PostgreSQL con Row Level Security | RLS es lo que hace viable el multi-tenant con esquema compartido |
| Respaldo en cinta, opción del menú | Responsabilidad de la plataforma, no del sistema | La opción «Copias de seguridad» del módulo Seguridad se conserva como consulta del estado, no como ejecutor |
| Cálculos con literales en el código | Parámetros versionados y sellados por ejercicio | Regla 5 de [ARQ-04](../30-arquitectura/estandares-de-codigo-backend.md) |

## 4. Lo que el manual **no** dice, y hace falta

Esto es el hueco principal del proyecto y la razón de que ninguna regla de cálculo se pueda
implementar todavía:

- Los **valores normativos**: UIT del ejercicio, tramos y alícuotas del impuesto predial,
  deducción del pensionista, tasas de arbitrios por sector y uso, tabla de valores unitarios de
  edificación, aranceles de terreno, tabla de depreciación por material y estado de conservación,
  tabla de valores referenciales vehiculares, porcentajes de multa por infracción.
- Los **plazos** de vencimiento, prescripción, notificación y ejecución coactiva.
- Las **fórmulas exactas** de interés moratorio y reajuste.

El manual nombra las normas (D. Leg. 776, TUO del Código Tributario, Ley 27616) pero no
transcribe sus cifras, y las capturas de pantalla muestran importes de ejemplo, no parámetros.
Ver [NEG-02](../10-negocio/marco-normativo.md) y la decisión **D-02**.

## 5. Fuera de alcance

- Migración de datos desde la base SQL Server existente (es un proyecto propio; ver **D-04**).
- Catastro gráfico o geográfico. El manual registra el código de referencia catastral, no
  geometría; no se añade PostGIS mientras no haya un requisito que lo pida.
- Integraciones externas (SUNAT, RENIEC, SUNARP, MTC, bancos). Se prevén en el modelo pero
  ninguna está especificada en el manual.
- Firma digital de valores y resoluciones (ver **D-05**).

## 6. Criterio de terminado de la iteración actual

La iteración en curso es **«las barreras»**, y termina cuando:

1. `./gradlew verificarAislamiento` pasa contra un PostgreSQL real, y se ha demostrado que puede
   fallar (mutando el DDL).
2. `./gradlew verificarArquitectura` pasa, y cada regla tiene una clase de muestra que la viola.
3. El esquema cubre las entidades de los nueve contextos acotados, con RLS en todas las tablas
   de tenant y privilegios solo sobre tablas padre.
4. El contrato de API está publicado y coincide con lo que el prototipo de interfaz declara.

**No** termina con ningún caso de uso funcionando: eso es la iteración siguiente, y está
bloqueada por D-01 y D-02.
