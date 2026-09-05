# DEV-03 — Cuando algo no arranca

Los errores que ya costaron una tarde, con su causa. Están aquí porque **el síntoma no se parece
a la causa** en ninguno de ellos.

## 1. `text search dictionary "unaccent" does not exist`

**Estás en PostgreSQL 17 o 18.** El esquema del producto no corre ahí: PG 17+ restringe el
`search_path` al inlinear una función SQL, y la función de normalización de nombres deja de
encontrar su diccionario. Con PostgreSQL 16 pasa.

En macOS con Homebrew el `postgres` del `PATH` suele ser el 18. El 16 hay que nombrarlo entero:

```bash
/opt/homebrew/opt/postgresql@16/bin/pg_ctl -D /opt/homebrew/var/postgresql@16 \
  -l /tmp/pg16.log -o "-p 55432" start
/opt/homebrew/opt/postgresql@16/bin/psql -h localhost -p 55432 -d postgres -c "select version()"
```

## 2. `Could not find a valid Docker environment` — o Testcontainers cuelga con el demonio vivo

Dos casos distintos con el mismo síntoma aparente.

**No hay Docker.** La salida documentada es apuntar a un PostgreSQL 16 existente con
`-Dkamayuk.pruebas.postgres.url` ([DEV-01 §3B](entorno-local.md)). **Ninguna salida omite la
prueba.**

**El demonio es remoto** —`DOCKER_HOST` apuntando a un socket reenviado o a un túnel—. Entonces
Testcontainers **sí** arranca los contenedores, pero **sus puertos publicados se quedan en la
máquina del demonio**, y nada de lo que se conecta a `localhost:<puerto>` llega a ninguna parte.
Medido contra un túnel a un demonio remoto, esto es lo que sale, y conviene tenerlo escrito porque
el mensaje no habla de la base ni de puertos:

```
ContainerLaunchException: Container startup failed for image testcontainers/ryuk:0.12.0
  Caused by: RetryCountExceededException: Retry limit hit with exception
  Caused by: NotFoundException: Status 404: No such container: fde52622c404…
```

Falla el **reaper**, antes de llegar a PostgreSQL: Testcontainers lo arranca primero y no logra
hablar con él. La salida es la misma que sin Docker: apuntar a un motor alcanzable de verdad con
`-Dkamayuk.pruebas.postgres.url`.

## 2 bis. La plataforma levanta y el motor sale sin bases ni roles

Con un `DOCKER_HOST` remoto, `plataforma.compose.yaml` monta rutas **relativas al árbol**
—`./inicializacion-del-motor/…` y `../backend/sgtm-esquema/…/crear-roles.sql`— y un *bind mount*
lo resuelve **el demonio**, no el cliente. Si esas rutas no existen en la máquina del demonio, el
motor arranca **sin ejecutar sus guiones de inicialización** y sin ningún error.

La salida es copiar `infrastructure/despliegue/` y `infrastructure/backend/` a una ruta que exista
**igual en las dos máquinas** y levantar desde ahí. Y comprobar la sustancia, no el `up`: las
cuatro bases, los cuatro roles y las extensiones ([DEV-01 §3C](entorno-local.md)).

## 3. `No esta …/infrastructure/librerias-backend`

Falta el clon hermano. No es un fallo de Gradle: es la comprobación que
`backend/settings.gradle.kts` hace **antes** de configurar, y trae dentro el `git clone` que
falta. Ver [DEV-01 §2.1](entorno-local.md).

Que el mensaje sea explícito es deliberado: sin él, Gradle reventaría sobre un directorio
inexistente con un error que no dice qué hacer.

## 4. La plataforma levanta y Keycloak contesta `000`

`docker compose … up -d --wait` **vuelve antes de que Keycloak sirva sus realms**. Medido: base,
buzón y Traefik quedan `healthy` en segundos; `/realms/…/.well-known/openid-configuration` tardó
unos **treinta segundos más** en contestar `200`. No hay nada que arreglar — Keycloak no declara
sonda en ese compose, así que `--wait` sólo comprueba que el contenedor corre.

Espera por lo que de verdad necesitas, no por el `up`:

```bash
until curl -sf http://localhost:8180/realms/sgtm/.well-known/openid-configuration > /dev/null
do sleep 5; done
```

## 5. `bind: address already in use` al levantar la plataforma

Los puertos por omisión —5432, 8080, 8180, 8025— son de los más ocupados que hay, y en un demonio
compartido puede haber otra instalación corriendo. **Se mueven en el `.env`**, sin tocar el
compose: `KAMAYUK_PUERTO_BASE`, `KAMAYUK_PUERTO_INGRESO`, `KAMAYUK_PUERTO_IDENTIDAD`, `KAMAYUK_PUERTO_CORREO`.

Y **si mueves el de Keycloak, mueve `KAMAYUK_OIDC_EMISOR` con él**. Es lo que Keycloak escribe en el
`iss` de cada token y lo que el backend compara: con dos nombres distintos la firma valida, el
emisor no cuadra, y el 401 no dice por qué.

## 6. Traefik contesta 404 a todo

**Si no hay ningún sistema levantado, es lo correcto**: Traefik está vivo y no hay nada detrás.

Si sí lo hay y sigue el 404, mira la versión de la imagen. Hasta la v3.5 Traefik pide la API de
Docker en la versión 1.24, fijada en su código, y **Docker 29 elevó el mínimo a 1.44**: con una
imagen anterior el proveedor falla en bucle, no descubre ni un servicio y contesta 404 a todo —
indistinguible de «todavía no hay ningún sistema», y sano según su propia sonda. Por eso el
compose fija `traefik:v3.6`.

## 7. Una rotura que «pasa en verde»

No es alivio: es un hallazgo. Las tres causas que ya se midieron:

- **La tarea no corrió.** Gradle dio `UP-TO-DATE` o `FROM-CACHE`. Se mide con `cleanTest` y
  `--no-build-cache`. Si el archivo que mutaste vive fuera del módulo, decláralo como entrada de
  `test` o no volverá a ejecutarse nunca.
- **La rotura no llegaba al camino que la prueba recorre.** El caso clásico: anotar una clase que
  la prueba instancia con `new`, de modo que el proxy transaccional no se aplica y la anotación no
  cambia nada.
- **La verificación no medía lo que parecía.** El ejemplo caro: conectar como `kamayuk_owner` para
  demostrar una fuga de aislamiento deja todo en verde, porque con `FORCE ROW LEVEL SECURITY` el
  dueño también queda sujeto a la política. Hay que usar el superusuario del clúster.

## 8. Spotless se queja del formato

No lo pelees: `./gradlew spotlessApply`. Checkstyle no revisa formato a propósito, para no
discutir con el formateador. Lo que sí revisa, y es fácil de incumplir con el teclado en español,
son los **identificadores con tilde**.
