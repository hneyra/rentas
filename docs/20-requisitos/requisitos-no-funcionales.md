# REQ-02 — Requisitos no funcionales

Los que el manual promete explícitamente llevan la marca **(manual)**: son compromisos del
sistema actual que la reimplementación no puede perder.

## Aislamiento y seguridad de datos

| # | Requisito | Verificación |
|---|---|---|
| RNF-030 | Ninguna operación puede leer ni escribir datos de una municipalidad distinta de la del token | `verificarAislamiento`, bloqueante |
| RNF-031 | **Toda** tabla lleva RLS activa y forzada. Lo que varía es la política, no su existencia | Prueba de aislamiento, cobertura estructural |
| RNF-032 | Una consulta **sin contexto de municipalidad falla**; no devuelve vacío ni devuelve todo | Prueba de aislamiento; `ContextoDeTenant.ESTADOS_SIN_CONTEXTO` |
| RNF-033 | El identificador de municipalidad sale **exclusivamente** del token validado | Prueba del filtro con encabezado y parámetro hostiles |
| RNF-034 | Ninguna conexión vuelve al pool con el contexto fijado | `TenantConnectionGuard`, con prueba gemela sin guardia |
| RNF-035 | El rol de aplicación no es superusuario, no tiene `BYPASSRLS` y no es propietario de ninguna tabla | Prueba de aislamiento, configuración de roles |

## Integridad y trazabilidad

| # | Requisito | Verificación |
|---|---|---|
| RNF-050 | **(manual)** Un proceso se registra en su totalidad o no se registra. Si falla un paso, se deshacen todos | Pruebas de integración por caso de uso |
| RNF-051 | No se borra deuda, pagos, recibos, valores, papeletas, asientos ni auditoría. Se anula, se da de baja o se reversa | Privilegios: la aplicación no tiene `DELETE`; escáner de fuentes |
| RNF-052 | **(manual)** Cada modificación registra usuario, nombre de máquina, IP, fecha, hora y **observación obligatoria**; sin observación no se guarda | Restricción `NOT NULL` en auditoría + prueba |
| RNF-053 | **(manual)** Se conserva el registro original antes de cada modificación | Tablas históricas y fichas versionadas |
| RNF-054 | Recalcular un ejercicio pasado con su conjunto de parámetros sellado da el mismo resultado | Pruebas de reglas con parámetros congelados |
| RNF-055 | Los importes se representan en decimal exacto. `double` y `float` prohibidos | ArchUnit |
| RNF-056 | El libro de cuenta corriente no admite `UPDATE` desde la aplicación | Privilegios + prueba de aislamiento |

## Disponibilidad y rendimiento

| # | Requisito | Objetivo |
|---|---|---|
| RNF-060 | Consulta de deuda de un contribuyente | < 1 s en el percentil 95 |
| RNF-061 | Cobro en caja, de la selección al recibo | < 2 s en el percentil 95 |
| RNF-062 | **(manual)** Estadísticas «en tiempo real», que se actualizan a medida que varía la información | El avance de recaudación no depende de un proceso nocturno |
| RNF-063 | Emisión masiva de un padrón de 100 000 predios | Ventana nocturna, reanudable, con avance consultable |
| RNF-064 | Todo índice selectivo empieza por `municipalidad_id`, porque la política RLS añade esa condición a cada consulta | Revisión del DDL |
| RNF-065 | **(manual)** El sistema maneja volúmenes del orden de terabytes | Particionado por ejercicio en las tablas de movimiento |

## Operación

Los objetivos de recuperación de esta sección salen de la topología de un solo nodo de
[`INF-01`](../80-infraestructura/arquitectura-de-infraestructura.md) §1.1, donde la recuperación es
una **restauración** y no una promoción. **Un objetivo que no está escrito no se puede incumplir**,
que es la forma más cómoda de no cumplirlo nunca: por eso llevan número, y por eso el número tiene
un sitio donde compararse.

| # | Requisito | Objetivo | Cómo se verifica |
|---|---|---|---|
| RNF-070 | **(manual)** Copias de seguridad programadas, comprimidas y en dispositivo distinto del servidor | Respaldo base periódico y archivado continuo, **fuera del VPS** (INF-01 §1.3) | El simulacro de restauración de INF-03 §2, y una alerta cuando el destino externo deja de estar accesible |
| RNF-071 | Recuperación ante caída sin pérdida de transacciones confirmadas | Archivado continuo de WAL | Restauración a un punto en el tiempo en `stg`, comparando las cifras con las del origen |
| RNF-072 | **(manual)** Registro de sesiones: quién está conectado y desde cuándo | Tabla de sesiones | Las quince pruebas de sesión y auditoría contra PostgreSQL |
| RNF-073 | Toda migración de esquema es reversible o aditiva; ninguna borra datos sin respaldo verificado | Revisión de migraciones | Revisión del PR, y el migrador que se niega a correr como superusuario o con `BYPASSRLS` |
| RNF-074 | **Todo el tráfico entra cifrado, con certificado válido y renovación automática. Ningún otro puerto responde desde fuera** | TLS 1.2 como mínimo, 1.3 preferido. Desde internet responden **80 —que solo redirige— y 443**, y nada más | Barrido de puertos contra el VPS desde fuera, y comprobación de la cadena del certificado y de la versión de TLS negociada, tras cada despliegue de `prod` |
| RNF-075 | Toda cifra de deuda mostrada indica **a qué fecha** está actualizada | Revisión de la API y de la interfaz | Las diecisiete pruebas de «ninguna cifra sin su fecha», y la regla de ESLint que prohíbe aritmética de importes en la interfaz |
| RNF-076 | **Pérdida máxima de datos ante la pérdida total del VPS (RPO): 5 minutos** | `archive_timeout` de 5 minutos contra un destino que está fuera del VPS | El simulacro de INF-03 §2 mide la pérdida real de la restauración; una alerta avisa cuando el archivado se atrasa, que es cuando el RPO deja de cumplirse |
| RNF-077 | **Tiempo máximo de recuperación ante la pérdida total del VPS (RTO): 4 horas** | VPS nuevo → k3s → `pulumi up` del stack → restauración a un punto en el tiempo → verificación → DNS | Simulacro **cronometrado** en `stg`, con el tiempo anotado. Sin ese número, el simulacro no tiene contra qué compararse y se puede declarar exitoso siempre |
| RNF-078 | **Toda ventana de mantenimiento del VPS se anuncia antes de abrirse** | Con un solo nodo no hay a dónde mover la carga: actualizar el nodo, redimensionar el disco o reiniciar k3s **es indisponibilidad**, no una operación transparente | Registro de despliegues y ventanas. Es una regla de proceso y no tiene comprobación automática: un despliegue de `prod` fuera de ventana declarada es un hallazgo de la revisión |
| RNF-079 | **Un respaldo que no se ha restaurado no cuenta como respaldo** | Al menos un simulacro de restauración completo por periodo, con su fecha y su tiempo anotados | El registro del simulacro (INF-03 §2). Si no hay entrada en el periodo, el requisito está incumplido — y es la única forma de detectarlo, porque un respaldo que nadie restaura siempre parece correcto |

## Interfaz y accesibilidad

| # | Requisito | Objetivo |
|---|---|---|
| RNF-080 | **(manual)** Pantallas, reportes, mensajes y ayudas en castellano | — |
| RNF-081 | **(manual)** Todo reporte se puede guardar en `.xls` y `.rtf` | — |
| RNF-082 | Operación de caja completa con teclado, sin ratón | Pruebas de interacción |
| RNF-083 | La interfaz no hace aritmética con importes: los totales llegan calculados del servidor | Revisión de código del frontend |
| RNF-084 | Los reportes se imprimen en A4 vertical, una hoja por reporte | Estilos de impresión |

## Cumplimiento

| # | Requisito | Objetivo |
|---|---|---|
| RNF-090 | Datos personales tratados conforme a la Ley 29733 y su reglamento | Documento propio, pendiente |
| RNF-091 | Ningún dato normativo vive en el código: todos en datos versionados con documento fuente | Escáner de fuentes; revisión |
| RNF-092 | Un parámetro tributario lo carga un usuario y lo aprueba otro | Restricción `CHECK` en `parametro_tributario` |
