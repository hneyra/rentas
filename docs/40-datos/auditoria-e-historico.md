# DAT-02 — Auditoría e histórico

El manual dedica a esto una sección entera (cap. 1 §Auditoría) y describe un comportamiento que
conviene no perder al reimplementar. Este documento dice qué exige el manual y dónde está cumplido
en el esquema.

## 1. Lo que promete el manual

> «Aquí se registra cualquier acción que genere una modificación o eliminación sobre los datos […];
> ante una modificación se guarda el registro original en las tablas históricas antes de ejecutar
> la modificación […]. En cada registro de modificación se registra en forma interna el ID del
> usuario, el Nombre de la Máquina (PC) y el IP de la PC desde la cual ocurre la modificación, la
> Fecha y Hora y **una observación que debe escribir el usuario, de lo contrario no le permite
> guardar la modificación**.»

Y en el módulo de catastro (cap. 2 §Actualización del Catastro):

> «cuando se va a registrar una modificación el sistema internamente saca una copia de la ficha
> original y genera un nuevo registro con los datos modificados; de esta manera se obtiene el
> historial de las Fichas Catastrales».

## 2. Cómo se cumple

### 2.1 La observación obligatoria

`auditoria.observacion` es `NOT NULL` **y** lleva `CHECK (length(btrim(observacion)) >= 5)`: una
cadena de espacios tampoco explica nada.

La consecuencia práctica es la que el manual buscaba: como cada operación de escritura tiene que
insertar su registro de auditoría en la **misma transacción**, una modificación sin observación
**no se completa**. No es una validación de la interfaz que se pueda saltar llamando a la API.

Además llevan `observacion NOT NULL` las tablas donde el manual insiste en el sustento:
`ficha_catastral`, `declaracion_jurada`, `beneficio`, `transferencia`, `valor`, `papeleta`,
`acta_fiscalizacion`, `expediente_coactivo`, `licencia_funcionamiento`, `licencia_edificacion`,
`anuncio` e `internamiento`.

### 2.2 Quién, desde dónde y cuándo

| Manual | Columna |
|---|---|
| ID del usuario | `auditoria.usuario_id` |
| Nombre de la máquina (PC) | `auditoria.origen_equipo` |
| IP de la PC | `auditoria.origen_ip` (`inet`) |
| Fecha y hora | `auditoria.fecha` (`timestamptz`) |
| Observación | `auditoria.observacion` |

Se conservan `origen_equipo` y `origen_ip` aunque hoy el cliente sea un navegador: en una red
municipal siguen identificando el puesto desde el que se hizo el cambio, que es para lo que el
manual los pedía.

También se registran los datos: `datos_anteriores` y `datos_nuevos`, en `jsonb`.

### 2.3 El registro original se conserva

Dos mecanismos, según el caso:

- **Fichas catastrales versionadas**: `ficha_catastral` lleva `version` y `vigencia_desde/hasta`.
  Modificar es cerrar la vigente y crear la siguiente. Un índice parcial garantiza que solo hay
  una vigente por predio y tipo. El histórico completo es la consulta de todas sus versiones.
- **Actas de fiscalización versionadas**: igual, con `version` por programa y contribuyente. Es lo
  que el manual llama «Histórico de Fiscalización Predial».
- **Libro de asientos**: no hay «registro original» que copiar, porque nada se modifica
  (ADR-0006).

### 2.4 La auditoría no se puede alterar

La aplicación tiene sobre `auditoria` **`SELECT` e `INSERT`, nada más**. Sin `UPDATE` y sin
`DELETE`, verificado por la prueba de aislamiento. Quien puede modificar la pista de auditoría
puede borrar su propio rastro, y eso vacía de sentido todo lo anterior.

El revisor de código fuente falla el build si aparece un `UPDATE auditoria … SET` o un
`DELETE FROM auditoria` en cualquier fuente de producción.

### 2.5 Se audita también la configuración de seguridad

Esto **no** lo pide el manual; se añade (ADR-0008 §5). La operación `PERMISO` de
`auditoria.operacion` registra los cambios sobre `permiso`, `miembro`, `grupo` y `usuario`. Sin
ello, el administrador del sistema es el único usuario que puede alterar su propia pista.

## 3. Volumen y retención

`auditoria` está particionada por `ejercicio`, igual que el libro de asientos: crece rápido y su
consulta habitual es «qué pasó en el ejercicio N».

**La política de retención es la decisión D-08**: cuántos años en línea y cómo se archiva el
resto. Hasta cerrarla, no se borra nada.

## 4. Lo que todavía no está

- El **mecanismo** que escribe la auditoría —disparadores de base de datos, o un aspecto en la capa
  de aplicación— no está implementado: no hay ninguna operación de escritura todavía. La decisión
  entre las dos formas se toma con el primer caso de uso, y el criterio es simple: la que no se
  pueda olvidar.
- La **consulta** de auditoría (opción `auditoria` del módulo Seguridad, RF-124) tampoco.
- Si en el futuro el dominio publica eventos, ADR-0008 se revisará: una bitácora de eventos de
  dominio es mejor que auditar tablas, pero exige que exista el dominio.
