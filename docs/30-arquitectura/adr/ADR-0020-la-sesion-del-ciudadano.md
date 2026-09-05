# ADR-0020 — El ciudadano tiene sesión propia, y su consulta recorre el registro de municipalidades

| Campo | Valor |
|---|---|
| Estado | Aceptada |
| Fecha | 2026-08-29 |
| Decide | Dirección del proyecto |
| Cierra | **D-07** (GOB-02). Abre y decide **D-15** |
| Referencias | [ARQ-03 §6](https://github.com/hneyra/sgtm/blob/migracion-a-microservicios/docs/30-arquitectura/estrategia-multitenant.md), [ADR-0005](https://github.com/hneyra/infrastructure/blob/main/docs/30-arquitectura/adr/ADR-0005-identidad-y-acceso.md), [ADR-0009 §1 y §2](https://github.com/hneyra/sgtm/blob/migracion-a-microservicios/docs/30-arquitectura/adr/ADR-0009-plataforma-frontend.md), [ADR-0012](https://github.com/hneyra/infrastructure/blob/main/docs/30-arquitectura/adr/ADR-0012-usuarios-y-grupos-declarativos.md), [ADR-0013](ADR-0013-permisos-de-la-sesion.md), [ADR-0016 §3](ADR-0016-el-inicio-pregunta-la-ficha-compone.md), issues [#57](https://github.com/hneyra/sgtm/issues/57) y [#415](https://github.com/hneyra/sgtm/issues/415) |

## Contexto

D-07 estaba redactada así: «el token del portal no lleva municipalidad y **el contexto sale del
objeto consultado**». De esa premisa salían los dos problemas que la mantuvieron abierta:

1. **El contexto de tenant se fijaría con un dato que elige quien pregunta.** Resolver «¿de qué
   municipalidad es este predio?» y fijar ahí el `SET LOCAL` invierte el camino de ARQ-03 §2 y
   convierte el identificador de municipalidad en un parámetro del cliente, que es exactamente lo
   que ADR-0005 prohíbe.
2. **La consulta por documento es un endpoint de enumeración.** `GET /portal/deuda?doc=44218937`
   contesta a cualquiera por cualquiera: quien teclea ocho dígitos averigua si esa persona figura
   en el padrón, cuánto debe y qué predios tiene.

## Decisión

### 1. El ciudadano se autentica, y su documento viaja firmado

Realm propio `sgtm-ciudadano` en el mismo Keycloak, versionado como el de funcionarios y aplicado
por el mismo Job de reconciliación. **Emisor distinto**, que es lo que separa las dos poblaciones
estructuralmente y no con un `if`: el backend monta **dos cadenas de seguridad**, cada una con su
`JwtDecoder` apuntando a un solo emisor. Un token de funcionario **no autentica** en
`/api/v1/portal/**`; un token de ciudadano **no autentica** en ninguna otra ruta.

El token del ciudadano lleva `tipo_documento` y `numero_documento` como claims, y **no** lleva
`municipalidad_id`: el ciudadano no pertenece a ninguna. El sujeto de la consulta deja de ser un
parámetro y pasa a ser un claim validado criptográficamente, igual que `municipalidad_id` lo es hoy
para el funcionario. Los dos problemas de arriba desaparecen **por construcción**, no por una
mitigación.

### 2. La consulta no cruza el aislamiento: lo recorre

**El contexto de tenant no sale del objeto consultado. Sale del registro de municipalidades, una
municipalidad a la vez, y nunca se cruza.**

> **No es una consulta multi-municipalidad; son *N* consultas de una municipalidad cuya unión se
> filtra a un documento firmado.**

```
token del ciudadano (realm propio)
  → claims tipo_documento + numero_documento   ← firmados, no tecleados
  → para cada municipalidad ACTIVA del registro:
        transacción propia → SET LOCAL app.municipalidad_id = N → RLS
        ¿hay contribuyente con ese (tipo, número)?  ── no → no se lee nada más, no se audita
                                                    └─ sí → deuda a la fecha + predios a la fecha
  → el servidor compone la respuesta con UNA SOLA fecha de corte
```

La base de datos no aprende a cruzar municipalidades; sigue sin poder hacerlo. Cada rama abre su
transacción, emite su `SET LOCAL` y queda sujeta a la misma política RLS que cualquier consulta de
ventanilla. Lo único nuevo es que el proceso **recorre el registro de tenants**, que ya es legítimo
y ya se hace: `municipalidad` es catálogo con `USING (true)`, `kamayuk_app` tiene `SELECT` sobre ella
(V6, V7) y todo proceso masivo del perfil `batch` itera municipalidad por municipalidad.

Tres reglas hacen que ese recorrido sea seguro, y las tres se verifican:

- **Un solo componente lo hace.** `RecorridoPorMunicipalidades`, en `sgtm-plataforma`, es el
  **único** componente del perfil `web` autorizado a mover `TenantContext` dentro de una petición.
  Hasta hoy ese invariante existía —los nueve llamadores restantes son todos `@Profile("batch")`— y
  no lo comprobaba nadie; pasa a ser regla de ArchUnit, con su clase de muestra que la viola.
- **El contexto se limpia entre ramas, pase lo que pase.** Una rama que falle sin limpiar deja el
  contexto de la municipalidad anterior puesto, y la siguiente devuelve **datos reales bajo la
  etiqueta equivocada**. Es la fuga que no se ve: las cifras son ciertas, el municipio no.
- **Ninguna transacción envolvente.** Es la lección de #54 y #72: una rama que lance
  —`EjercicioSinSellar` es lo que ocurre **hoy** en todas las municipalidades— marcaría la
  transacción del anfitrión como *rollback-only* y el portal entero reventaría con
  `UnexpectedRollbackException` por culpa de una municipalidad.

### 3. Una sola fecha, y sin total consolidado si falta una rama

La fecha de corte **entra como argumento** y es **la misma** para todas las ramas (regla 9,
RNF-075). Es lo que hace legítimo el total consolidado: sin ella cada rama leería en su propio
instante y la suma sería de cifras de momentos distintos presentada como una sola.

Y **si alguna rama falla, no hay total consolidado**. Un total al que le falta una municipalidad es
un importe plausible y equivocado. Se dice cuál falta y por qué no se puede totalizar; las demás se
muestran.

### 4. D-15 — quién acredita el documento: **enrolamiento en ventanilla**

Todo lo anterior se sostiene sobre que `numero_documento` del token **sea de quien lo presenta**. Un
realm donde el ciudadano se registra solo y **declara** su DNI es la peor versión del problema
original: en vez de teclear el documento ajeno en una caja, lo teclea una vez al registrarse y el
sistema se lo cree para siempre, firmado.

Se elige el **camino B**: el funcionario, con el documento delante, crea o vincula la cuenta —el
mecanismo declarativo de ADR-0012, extendido, con su rastro de auditoría—.

- **A** (federación con la identidad nacional, ID Perú / RENIEC) **es el destino**, y no se puede
  hacer hoy: exige un convenio y unas credenciales de federación que no existen. Implementarlo ahora
  sería escribir contra un emisor que no está.
- **C** (autorregistro con un factor de verificación —código impreso en un recibo, envío al
  domicilio fiscal—) es **más débil que B y más caro**: acredita menos y exige además un canal de
  entrega y su operación.

Y sea cual sea el camino, queda decidido:

- el atributo `numero_documento` **no es editable por el usuario** en el realm del ciudadano
  (`permissions.edit: ["admin"]`, igual que `municipalidad_id` en el de funcionarios);
- `registrationAllowed` sigue en **`false`**;
- el mapeador del claim lee **ese atributo**, y no un campo del formulario.

### 5. El acto de enrolamiento no es una pantalla nueva

**Las 134 opciones del catálogo son 134**, y lo fija una prueba. El enrolamiento **no** añade una
pantalla 135: es el mismo mecanismo declarativo y versionado de ADR-0012 —un archivo por
municipalidad, reconciliado por el Job, sin una sola clave en git— extendido con los ciudadanos que
la municipalidad enroló en ventanilla. El acto que quien atiende ejecuta es «declarar y reconciliar»,
no «rellenar un formulario nuevo», y su rastro es el del propio archivo versionado más la corrida
del Job.

La razón no es de empaquetado. Una pantalla que cree cuentas de ciudadano desde el back-office es
una pantalla que **fija identidades** —lo que el sistema creerá para siempre sobre quién es quien
consulta— y esa clase de acto es exactamente la que ADR-0012 sacó del navegador: «un realm
versionado que trae usuarios con contraseña es la forma más cómoda de que esa contraseña acabe en
producción». Enrolar por el mismo camino hereda de ADR-0012 sus tres propiedades: es reproducible,
es idempotente y **nadie llega a ver una clave**.

### 6. Confirmada y construida: cómo es el acto (#415)

§5 se **confirma** —el enrolamiento no añade una pantalla 135— y se construye con el mismo guion
que ADR-0012, en un segundo modo:

```bash
./identidad/reconciliar-identidades.sh              # funcionarios, realm `sgtm`
./identidad/reconciliar-identidades.sh ciudadanos   # enrolados,   realm `sgtm-ciudadano`
```

Un archivo por municipalidad, [`despliegue/identidad/ciudadanos/<ubigeo>.json`](https://github.com/hneyra/infrastructure/blob/main/despliegue/identidad/ciudadanos/README.md),
hermano del de personas. Tres cosas quedaron decididas al construirlo, y las tres son de identidad
y no de empaquetado:

- **La cuenta se deriva del documento, y lleva el tipo delante**: `dni-70123456`. Derivada, porque
  una cuenta declarable se puede declarar *distinta* del documento, y entonces la fila `ACCESO` que
  cada rama deja en la bitácora —que lleva el `preferred_username`— deja de identificar a nadie. Y
  con el tipo delante porque **`CE 12345678` y `DNI 12345678` son dos personas distintas** y las dos
  formas son válidas (`TipoDocumento`): con la cuenta llamada solo por el número, la segunda
  declaración actualizaría la cuenta de la primera y le cambiaría el `tipo_documento` —a partir de
  ahí una de las dos leería el padrón de la otra, firmado—.

- **El número declarado tiene que estar en el padrón de esa municipalidad.** Es lo único que impide
  enrolar a alguien que ningún padrón conoce, y se cruza a tres bandas contra la base: archivo ↔
  fila de `contribuyente` de ese ubigeo ↔ atributo en Keycloak. Ese cruce es también lo que explica
  por qué el archivo es **por municipalidad** cuando el realm del ciudadano es uno solo: el archivo
  registra **quién acreditó**.

- **El correo es opcional.** Con él, Keycloak manda el enlace de un solo uso; sin él la cuenta nace
  igual —con `UPDATE_PASSWORD` pendiente— y la clave se entrega fuera de banda. Un padrón real tiene
  mucha gente sin correo, y exigirlo dejaría fuera del portal justo a quien va a ventanilla.

Y lo que **no** cambia: ni una clave en el archivo (el guion rechaza `credentials`, `password`,
`secret` o `clave` nombrando el archivo), ningún grupo y ningún `municipalidad_id` —el ciudadano no
pertenece a ninguna—, y las **134 siguen siendo 134**.

Ejercer la rotura de §4 —quitarle el atributo `numero_documento`— enseñó de paso **cuál de las dos
barreras actúa primero**: Keycloak **no deja crear** la cuenta, porque el perfil declarativo del
realm exige ese atributo al administrador. La cuenta no llega a existir, de modo que el `403
SIN_DOCUMENTO` del borde de la aplicación es la segunda barrera y no la primera; sigue haciendo
falta, y se comprueba con un documento que existe y que el dominio no puede leer.

El costo de §5 se paga y se dice: **el ciudadano que va a ventanilla no sale enrolado, sale
esperando el despliegue.** Es lo que se compra a cambio de que el acto que fija una identidad tenga
diff, revisor y corrida reproducible. Si algún día eso no fuera aceptable operativamente, lo que hay
que reescribir es §5, no el mecanismo.


## Consecuencias

- **`GET /portal/deuda` se retira del contrato**, con su parámetro `doc`. Es literalmente el
  endpoint de enumeración que D-07 describía. Lo sustituye `GET /api/v1/portal/situacion`, **sin
  ningún parámetro**: una sola ida y vuelta, el servidor recorre, compone y suma (RNF-083).
- **La opción `portal` de las 134 no cambia y no gana backend.** Sigue siendo la vista del
  funcionario (ADR-0016 §3) y sigue diciendo, con el mecanismo de actos honestos, que no tiene a
  dónde preguntar. Servirle `/portal/**` a un funcionario sería devolver el endpoint que esta
  decisión quita.
- **`@RequiereAcceso` gana un segundo centinela**, con el precedente exacto de `SESION_PROPIA`
  (ADR-0013): `RequiereAcceso.CIUDADANO`. El ciudadano no tiene fila en `usuario` y no hay
  privilegio que comprobar; lo que sí hay es una comprobación nueva —el centinela se admite **solo**
  si la petición llegó por la cadena del ciudadano—, y un endpoint del catálogo anotado con él rompe
  el build.
- **`TenantContextFilter` no corre en `/api/v1/portal/**`.** En su lugar corre
  `DocumentoCiudadanoContextFilter`, que fija el sujeto desde los dos claims y rechaza el token que
  no los traiga: no hay valor por omisión ni modo «sin documento», igual que no lo hay para un token
  de funcionario sin `municipalidad_id`. El agujero que esto abre está declarado y es ruidoso: si
  alguien sirviera mañana un endpoint de funcionario bajo `/portal/`, correría sin contexto de
  tenant y **toda** consulta fallaría en la base, que es el comportamiento correcto.
- **Cada rama que lee deja su fila `ACCESO` en la auditoría de esa municipalidad** (precedente
  #344). Una municipalidad donde la persona **no figura** no recibe ninguna: el sondeo del padrón no
  es un acceso, y auditarlo convertiría la bitácora de cada municipio en una forma de saber que
  alguien existe en otro.
- **Fronteras que se dicen y no se puentean.** El documento del token identifica **exactamente una
  fila del padrón por municipalidad** (`contribuyente_documento_uq`), y nada más se compone: ni el
  cónyuge, ni la sucesión indivisa, ni la sociedad conyugal, ni el RUC de la empresa que representa,
  ni las obligaciones donde figura como responsable solidario. Las cinco son composiciones
  plausibles y todas enseñan deuda de otra persona a quien no es ella. Ningún predio nombra a su
  copropietario (ADR-0019). Una municipalidad `activa = false` queda fuera del recorrido; un
  contribuyente `activo = false` queda dentro y **marcado** —la deuda sobrevive a la baja del
  padrón, y ocultarla sería decirle que no debe nada—.
- **El pago en línea no entra aquí.** D-14 está abierta —la regla de imputación de un pago parcial—
  y el asiento de un cobro exige caja, serie, turno y cajero (#33, #36), ninguno de los cuales tiene
  el ciudadano. Esta decisión deja el camino puesto: identidad acreditada, sujeto firmado y deuda
  leída con su fecha.

## Alternativas descartadas

- **Resolver el contexto desde el objeto consultado**, que era la premisa original de D-07. Fija el
  `SET LOCAL` con un dato del cliente e invierte el camino de ARQ-03 §2. Y no arregla lo segundo:
  seguiría contestando por cualquiera que se teclee.
- **Un cliente más en el realm de funcionarios, en vez de un realm propio.** Las dos poblaciones
  compartirían emisor, y entonces lo único que separaría a un ciudadano de un funcionario sería una
  comprobación dentro de la aplicación —un `if` que se puede olvidar, y que al olvidarse no rompe
  nada visible—. Con emisores distintos, olvidarse produce un 401.
- **Una consulta SQL que cruce municipalidades** (recorrer el padrón con RLS desactivada, o con un
  rol que la omita). Es el único diseño que de verdad rompería el aislamiento: bastaría un `WHERE`
  mal escrito para que una persona viera el padrón de otra. El recorrido, en cambio, no puede
  devolver una fila que su `SET LOCAL` no autorice.
- **Un endpoint por municipalidad, y que la interfaz recorra.** Mueve el recorrido al navegador:
  *N* idas y vueltas, *N* fechas de corte distintas y un total que compondría el cliente, contra
  RNF-083 y contra la regla 9.
- **Auditar también las municipalidades donde la persona no figura.** Convierte la bitácora de cada
  municipio en un canal para saber que alguien existe en otro. Se audita lo que se lee.
