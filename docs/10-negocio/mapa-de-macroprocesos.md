# NEG-01 — Mapa de macroprocesos

Los procesos que el manual describe, en el orden en que ocurren. Cada uno indica el contexto
acotado que lo sirve y las opciones del menú que lo componen
([NEG-03](catalogo-de-opciones.md)).

## 1. Registro y mantenimiento del padrón — `catastro`, `contribuyentes`

El manual (cap. 2) lo llama «la herramienta fundamental que garantice el sostenimiento o
incremento del importe a cobrar»: sin padrón actualizado no hay recaudación.

```
Contribuyente (código único)
   └── Predio (código de referencia catastral)
         ├── Ficha catastral   ← única · económica · bienes comunes · rural
         ├── Titularidad       ← propietario, cónyuge, condómino, poseedor
         ├── Construcción      ← por piso: categorías, material, estado, antigüedad
         ├── Otras instalaciones
         └── Inquilinos        ← para arbitrios
```

**Invariante del manual:** modificar una ficha **no sobrescribe**. El sistema copia la ficha
original, genera una versión nueva con los datos modificados y registra autor, fecha y hora. El
histórico permite ver los estados de la ficha en el tiempo (cap. 2 §Actualización del Catastro).

## 2. Determinación — `rentas`, `parametros`

Cálculo del importe a pagar. Dos caminos, misma regla:

- **Individual**, por contribuyente, con impresión de HR, PU, PR, aviso de cobranza y ficha
  tributaria.
- **Masiva**, para todo el padrón de un ejercicio, con generación de estados de cuenta.

Tributos: impuesto predial, arbitrios, patrimonio vehicular, alcabala, espectáculos públicos no
deportivos, juegos y anuncios.

**La determinación no inventa cifras**: toma el conjunto de parámetros **sellado** del ejercicio
(UIT, tramos, alícuotas, valores unitarios, aranceles, depreciación). Recalcular un ejercicio
pasado con su conjunto sellado debe dar el mismo céntimo.

## 3. Cuenta corriente — `cuentacorriente`

Todo lo determinado se asienta como **cargo**; todo lo pagado, condonado o anulado, como
**abono**. El libro es inmutable: un asiento no se corrige, se reversa con otro asiento.

Los movimientos que el manual llama **nota de abono** (alta de deuda) y **nota de cargo** (baja
de deuda) entran por aquí, con su sustento documental, y son consultables (cap. 3 §Consulta de
Altas y Bajas).

La deuda de un contribuyente **no es un dato almacenado**: es una función de la fecha. Toda
cifra mostrada indica a qué fecha está actualizada.

## 4. Fiscalización — `fiscalizacion`

Verificación en campo de lo declarado. El manual describe el ciclo completo:

```
programación → acta de fiscalización → cálculo del impuesto fiscalizado
   → liquidación (consolidado de deudas y multas) → reliquidaciones
   → transferencia predial: lo hallado sobrescribe lo declarado en rentas
```

La transferencia al área de rentas es el punto delicado: es el único camino por el que un dato
de fiscalización pasa a ser el dato oficial del padrón. Tiene su propio histórico de versiones.

## 5. Sanciones — `sanciones`

Dos familias con el mismo esqueleto y distinta base legal:

| | Tránsito | Administrativa |
|---|---|---|
| Origen | Papeleta impuesta al conductor o propietario | Notificación previa (opcional) → papeleta |
| Catálogo | Códigos de infracción de tránsito | Cuadro de infracciones y sanciones (CUIS) |
| Cálculo | Base imponible × % de la infracción, con % realmente cobrado y beneficio | Ídem |
| Escalado | Resolución de gerencia ordinaria → sancionadora → coactiva | Resolución de gerencia → notificación → coactiva |

## 6. Cobranza — `tesoreria`, `valores`, `coactiva`

```
deuda pendiente
   ├── pago en caja  ─────────────► recibo (anulable el mismo día)
   ├── convenio de fraccionamiento ─► preconvenio (pago inicial) → cuotas → quiebre
   └── emisión de valor (OP · RD · RM)
         → notificación → pase a coactiva
              → expediente coactivo → REC-1 → medida cautelar (REC-2)
                   → costas procesales → fraccionamiento coactivo
```

**Caja** cobra tributos («caja tributaria») y derechos («caja tasas»), aplica campañas de
beneficio, emite duplicados y anula recibos del día. El cierre diario por cajero es el corte
contable.

## 7. Autorizaciones y licencias — `licencias`

Licencia de funcionamiento (con giros CIIU, duplicados y cancelación), licencia de edificación
(según el Formulario Único de Edificaciones) y autorización de anuncios y propaganda. Cada
autorización **genera su deuda** por la tasa correspondiente, que entra a la cuenta corriente
como cualquier otra.

## 8. Seguridad y auditoría — `seguridad` (transversal)

No es un macroproceso de negocio, pero atraviesa todos: cada opción del menú es un **acceso**
sobre el que un grupo o un usuario tiene privilegios, y **cada modificación de datos deja pista
de auditoría con observación obligatoria**. Ver [REQ-03](../20-requisitos/actores-y-permisos.md)
y [DAT-02](../40-datos/auditoria-e-historico.md).
