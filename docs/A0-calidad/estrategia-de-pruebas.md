# CAL-01 — Estrategia de pruebas

## 1. El principio

**Ejecutar la prueba vale más que razonar sobre ella**, y **una verificación tiene que demostrarse
capaz de fallar**. Las dos frases vienen del SRTM, donde ejecutar el DDL en lugar de revisarlo
encontró tres defectos que la revisión no habría visto —incluidos los dos hallazgos de RLS que
anulaban el aislamiento entre municipalidades.

De ahí sale la regla que gobierna este documento: **cada verificación bloqueante lleva su
contraparte que demuestra que muerde.**

| Verificación | Cómo se demuestra que puede fallar |
|---|---|
| Aislamiento del esquema | Mutando el DDL: quitar `WITH CHECK`, conceder un privilegio sobre una partición |
| Guardia del pool | Una prueba gemela **sin** guardia, que provoca la fuga de verdad |
| Reglas de ArchUnit | Una clase de muestra que viola cada regla, en `verificaciones/muestras/` |
| Revisor del código fuente | Muestras de texto con `SET SESSION`, `DELETE` y `UPDATE` prohibidos |
| Filtro del token | Una petición con encabezado **y** parámetro que dicen otra municipalidad |

## 2. Qué es bloqueante

```bash
./gradlew verificarAislamiento    # aislamiento multi-tenant: esquema y pool
./gradlew verificarArquitectura   # ArchUnit, escaner de fuentes y Spring Modulith
./gradlew build                   # ademas: Spotless, Checkstyle y NullAway
```

Las tres corren en cada pull request, en pasos separados, en
[`.github/workflows/backend.yml`](../../.github/workflows/backend.yml). Antes de ellas el workflow
comprueba lo que podría teñir de rojo el build sin que el código tenga nada que ver: que la
distribución de Gradle se descargue, que `gradle.properties` siga declarando el Java 25 de
ADR-0001 y que haya Docker con la imagen de PostgreSQL. Detalle en
[`backend/README.md`](../../backend/README.md).

**Una prueba bloqueante no se omite a sí misma.** Sin motor de base de datos, las pruebas de
persistencia **fallan**; no se saltan. Una prueba que se salta sola deja el build en verde y da
por verificado lo que nadie verificó.

## 3. Niveles

### 3.1 Reglas tributarias — puras, sin Docker

Sin base de datos, sin reloj, sin Spring. Los parámetros entran como argumento y se congelan en el
caso de prueba, de modo que recalcular el ejercicio 2027 en 2037 da el mismo céntimo.

**Todavía no existen**: la primera regla de cálculo está bloqueada por D-11 y por las partes
locales de D-02 (D-02a está firmada y el redondeo decidido, ADR-0018).

### 3.2 Persistencia — PostgreSQL real

**Prohibida la base en memoria.** H2 no tiene Row Level Security, así que una prueba de
persistencia sobre H2 pasaría en verde sin verificar lo único que aquí importa de verdad.

Por omisión se levanta un contenedor con Testcontainers. Para entornos sin Docker existe una
salida documentada —apuntar a un PostgreSQL ya existente con
`-Dkamayuk.pruebas.postgres.url`—, y **no** una salida que omita la prueba. Detalle en
[`backend/README.md`](../../backend/README.md).

### 3.3 Aislamiento multi-tenant — la prueba central

`AislamientoMultiTenantTest`, en `sgtm-esquema`. Cuatro bloques:

1. **Cobertura estructural.** Toda tabla clasificada, RLS activa y forzada, política con `USING` y
   `WITH CHECK`, ninguna política con subconsulta, toda partición con RLS explícita.
2. **Aislamiento efectivo, tabla por tabla.** Con contexto de A, ninguna lectura devuelve filas de
   B; un `INSERT` con municipalidad ajena falla con `42501`; un `UPDATE` por id ajeno afecta cero
   filas; una consulta sin contexto **falla**.
3. **Configuración de roles.** Ninguno es superusuario ni tiene `BYPASSRLS`; la aplicación no es
   propietaria de ninguna tabla, no tiene `DELETE` en ninguna, y no puede actualizar el libro de
   asientos ni la auditoría.
4. **Particiones.** La aplicación no tiene ningún privilegio sobre ninguna; el acceso directo
   falla; la lectura por la tabla padre sigue funcionando y filtra.

Más un quinto bloque que es el que hace válido a los otros cuatro: **la trampa**. Verifica que la
conexión de superusuario ve las dos municipalidades con el mismo contexto fijado. Si esa
aserción dejara de cumplirse, sería porque el rol dejó de ser superusuario, no porque la trampa
desapareció.

**Dos requisitos que la hacen valer:**

- Se conecta como `kamayuk_app`, creado en el arranque de la prueba. No con la conexión por omisión.
- Siembra **una fila en cada tabla de tenant, para las dos municipalidades**. Si una tabla
  estuviera vacía, «no se ve nada de B» sería cierto sin probar nada. Por eso la prueba exige
  además que A vea filas propias en cada tabla: **al agregar una tabla hay que sembrarla**.

### 3.4 Camino del contexto — con el pool real

`AislamientoConElPoolTest`, en `sgtm-plataforma`. Pool de una a cuatro conexiones para decenas de
peticiones, a propósito: con un pool holgado, una fuga por reutilización puede no aparecer nunca
en una prueba y aparecer el primer día de vencimiento.

Incluye la prueba gemela **sin** guardia, que demuestra que la fuga ocurre de verdad cuando el
guardia no está, y una medición del costo de la verificación que se **informa** en la salida en
lugar de convertirse en una aserción intermitente.

### 3.5 Arquitectura

- **ArchUnit** para lo que es estructura de clases: coma flotante, `LocalDateTime`, lectura del
  reloj en dominio, dependencias del dominio, `MunicipalidadId` en firmas.
- **Escáner del código fuente** para lo que es texto: `SET SESSION`, `set_config(..., false)`,
  `DELETE` sobre tabla protegida, `UPDATE` sobre tabla inmutable.
- **Spring Modulith** para los límites entre contextos.

## 4. Lo que hoy no se prueba, y hay que saberlo

- **Ninguna regla de cálculo se prueba contra cifras reales.** El motor de reglas y el corpus de
  casos de NEG-05 sí tienen pruebas —puras, sin base ni reloj—, pero las reglas que dependen de
  los factores sin fuente de D-11 y de los cuadros de GOB-03 no se implementan ni
  estructuralmente, y ningún conjunto del ejercicio está sellado con cifras reales: no hay
  todavía una prueba que compare contra un céntimo verdadero.
- El **enrolamiento del ciudadano en ventanilla** no está probado porque no está construido
  (D-15, camino B; issue #415): hoy una cuenta del realm `sgtm-ciudadano` solo se crea a mano
  contra Keycloak.

Cuatro huecos que esta sección declaraba ya se cerraron, y conviene dejarlo dicho para que nadie
los dé por abiertos: los doce contextos acotados tienen código y pruebas de negocio; el permiso
`SIN_DOMINIO_TODAVIA` de las reglas acotadas a `..dominio..` se retiró con el dominio compartido
—y `ArquitecturaTest` tiene la aserción que impide que vuelva a colarse—; y Spring Modulith
verifica los módulos con código en cada build (`ModulosTest`, que además exige que los esperados
estén detectados, porque un `verify()` sobre cero módulos pasaría sin comprobar nada). Y el
cuarto, con #57: el **camino del portal del contribuyente** sí está probado —las dos cadenas de
identidad con dos emisores de verdad (`CadenaDelPortalTest`), el recorrido por municipalidades
contra PostgreSQL (`SituacionDelCiudadanoJdbcTest`) y los 360 px en Chromium
(`portal-en-movil.spec.ts`)—, y D-07 se cerró con ADR-0020.

## 5. Al agregar código

| Si agregas… | Agrega también |
|---|---|
| Una tabla de tenant | Su siembra en `DatosDePrueba` |
| Un catálogo | Su entrada en `TABLAS_DE_CATALOGO`, en el código de la prueba |
| Una partición | Su bloque de RLS explícita, y **ningún** privilegio |
| Una regla de arquitectura | La clase de muestra que la viola |
| Una tabla con constancia de un acto | Su entrada en `TABLAS_PROTEGIDAS` del revisor |
| Una regla tributaria | Su prueba pura, con los parámetros congelados |
