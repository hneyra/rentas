# DEV-02 — Pruebas

## 1. Qué verifica qué

| Tarea | Qué mide | Necesita | Hoy |
|---|---|---|---|
| `./gradlew verificarArquitectura` | 18 reglas de ArchUnit, tres escáneres de fuentes y la frontera de sistema —contra sus muestras **y contra el código de negocio**—, más el contrato de la API, las formas y respuestas publicadas y los límites de Modulith | nada | **130 pruebas** |
| `./gradlew verificarAislamiento` | Los cuatro roles, `FORCE ROW LEVEL SECURITY`, el `WITH CHECK`, que sin contexto la consulta **reviente en vez de devolver vacío**, la trampa del superusuario, y RLS sobre las **132 tablas** del baseline | PostgreSQL 16 | **223 pruebas** (46 del esquema + 177 del pool) |
| `./gradlew build` | Lo anterior más Spotless, Checkstyle y NullAway, sobre los 17 módulos | PostgreSQL 16 | **3 756 pruebas** |
| `yarn verificar` (en `infrastructure/`) | El descriptor de despliegue: lint, tipos y pruebas | nada | |
| `node docs/00-gobierno/verificar-las-muestras-del-registro.mjs` | Que la guarda de #711 muerde y no muerde de más | nada | **6 muestras** |

**Las dos de Gradle son bloqueantes**, y van en pasos separados en CI a propósito: cuando algo se
rompe, el nombre del paso ya dice qué barrera cayó.

## 2. Que las 130 no son un verde vacío

Desde P5A las reglas se aplican **a código de negocio de verdad**, no sólo a las muestras. Aun
así el mecanismo que impide el verde vacío sigue siendo el mismo, y sigue haciendo falta:

- **Las 40 clases de muestra viajan con las reglas**, dentro de `comun-verificaciones`. Cada regla
  se aplica a la muestra que la viola y se exige que falle.
- **`ReglasDeArquitecturaMuerdenTest` es un `@TestFactory` sobre todas las reglas**, así que una
  regla sin muestra sale roja sola. No hay dónde esconder una regla muda.
- **Que la configuración de este repositorio exista se descubre por `ServiceLoader`.** Si se pasara
  por constructor, un repositorio que no derivara las clases base no correría ninguna barrera y su
  CI seguiría en verde. Cero proveedores falla; dos, también.

Comprobado rompiendo: borrar una muestra **en `infrastructure`** pone en rojo el
`verificarArquitectura` de este repositorio, nombrando la regla.

## 3. Que las 9 tampoco

`verificarAislamiento` corre **sin una sola migración**, y sigue midiendo algo: crea su propia
tabla con el mismo bloque de RLS que el esquema le pone a toda tabla de tenant, y sobre ella
verifica los cuatro roles y las cuatro propiedades. La más importante es **la trampa del
superusuario**: un superusuario **omite RLS incluso con `FORCE ROW LEVEL SECURITY`**, así que una
prueba escrita sobre la conexión que Testcontainers entrega por omisión pasa en verde **sin
verificar nada**. Aquí se demuestra en vez de afirmarse: con el mismo contexto fijado, el
superusuario ve las dos municipalidades y el rol de la aplicación, una.

Y hay una segunda trampa, medida y que conviene tener escrita: **conectar como `sgtm_owner` no
sirve para demostrar la fuga.** Con `FORCE ROW LEVEL SECURITY` el dueño de la tabla también queda
sujeto a la política, así que esa rotura pasa en **verde** y no demuestra nada. La que hay que
escribir es la del superusuario del clúster.

**El censo del esquema ya no está eximido**: `V1__baseline.sql` trae las 132 tablas y la prueba
de aislamiento las censa una a una, que es para lo que la exención caducaba sola.

## 4. Correr una sola

```bash
cd backend
./gradlew :kamayuk-rentas-aplicacion:test --tests '*Frontera*'
./gradlew :kamayuk-rentas-esquema:test --tests '*Aislamiento*'
```

**Cuidado con el verde rancio.** Gradle puede dar `UP-TO-DATE` o `FROM-CACHE` y no ejecutar nada;
una tarea que no corre no demuestra nada. Para medir de verdad:

```bash
./gradlew cleanTest verificarArquitectura --no-build-cache
```

Es la misma lección que costó una tarde en `sgtm`: una rotura pasó «en verde» porque el archivo
que se mutó vivía fuera del módulo y no era entrada declarada de `test`.

## 5. Cómo se cuenta lo que corrió

El número que se afirma en un PR sale de los informes, no de la memoria:

```bash
python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
t = f = e = s = 0
for p in glob.glob('backend/**/build/test-results/test/*.xml', recursive=True):
    r = ET.parse(p).getroot()
    t += int(r.get('tests')); f += int(r.get('failures'))
    e += int(r.get('errors')); s += int(r.get('skipped'))
print(f'pruebas={t} fallos={f} errores={e} omitidas={s}')
PY
```

**`omitidas` tiene que ser 0.** Una prueba bloqueante que se salta a sí misma deja el build en
verde sin haber verificado nada.

## 6. Demostrar que una verificación puede fallar

Es la mitad del trabajo, y la que se anota en `CLAUDE.md`. La forma que funciona:

1. Se rompe **una sola cosa** en el código que la verificación protege.
2. Se ejecuta —de verdad, sin caché— y se anota **el rojo exacto**: cuántas pruebas, cuáles y qué
   dice el mensaje.
3. Se **restaura por copia** y se compara byte a byte con `cmp`. Un `sed` de vuelta puede pisar
   otra línea idéntica, y el único síntoma sería que algo deja de compilar más tarde.
4. Si la rotura pasa en **verde**, eso es el hallazgo: la verificación no medía lo que parecía.
   Se escribe, no se descarta.
