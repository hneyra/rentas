# DEV-01 — Entorno local

| Campo | Valor |
|---|---|
| Estado | Vigente |
| Verificado en | macOS 15 (arm64), JDK 25 (Temurin 25.0.4), Node 26.7, yarn 1.22.22, PostgreSQL 16.15 (Homebrew) y Docker Engine 29.1.3 remoto |

## 1. Prerrequisitos

| Herramienta | Versión | Por qué esa |
|---|---|---|
| JDK | **25** | `backend/gradle.properties` declara `kamayuk.java.version=25`, y CI comprueba que coincida (ADR-0001) |
| Node | **22** | Es la que fija CI. Con otra, el descriptor puede pasar aquí y caer allí |
| yarn | 1.22 (clásico) | Es el que hay en el repositorio |
| PostgreSQL | **16** | `verificarAislamiento` lo necesita. **En 18 el esquema del producto no corre** — ver [DEV-03 §1](solucion-de-problemas.md) |
| Docker | Con Compose v2 | Sólo para la plataforma y para Testcontainers. Hay salida documentada sin él |

**No hace falta instalar Gradle**: el repositorio trae el *wrapper* (`./gradlew`).

## 2. Los clones hermanos

### 2.1 `infrastructure` — obligatorio

```bash
cd ..
git clone https://github.com/hneyra/infrastructure
```

`backend/settings.gradle.kts` busca `../../infrastructure/librerias-backend` y **se para antes de
configurar** si no está, diciendo qué `git clone` falta. Eso es deliberado: es mejor que un fallo
de Gradle sobre un directorio inexistente, que no dice nada.

Lo que cuesta esa decisión está dicho y no escondido: **este backend no compila solo.** Se eligió
así frente a un jar publicado porque el modo de fallo de un jar es peor — se queda viejo sin que
nada se ponga rojo, y una verificación vieja que pasa en verde es lo que este proyecto lleva
doscientos issues evitando.

### 2.2 `sgtm` — el archivo histórico

```bash
git clone https://github.com/hneyra/sgtm && (cd sgtm && git checkout migracion-a-microservicios)
```

No es obligatorio para compilar, pero se consulta a diario: el inventario del corte (GOB-05), las
decisiones abiertas (GOB-02), los baselines de esquema y las 288 filas del registro «Verificar
antes de afirmar» viven allí. **`sgtm` no se modifica.**

### 2.3 `srtm` — para el cálculo tributario

```bash
git clone https://github.com/hneyra/srtm
```

De ahí salen las reglas del predial (NEG-05), el motor de reglas (ARQ-09) y los tipos y longitudes
de columna. **No es una sugerencia:** el motor de reglas se escribió una vez sin poder leer esos
documentos y salieron dos defectos estructurales, los dos en verde.

## 3. Las tres formas de trabajar

Elige la más barata que sirva para lo que vas a tocar.

| # | Forma | Necesita | Sirve para |
|---|---|---|---|
| **A** | Sólo las barreras de arquitectura | JDK | Reglas, escáneres, frontera de sistema, muestras |
| **B** | Las dos barreras | JDK + PostgreSQL 16 | Todo lo anterior más el aislamiento multi-tenant |
| **C** | La plataforma levantada | Docker | Base con las cuatro bases, identidad con sus dos realms, enrutado |

### A · Sólo las barreras de arquitectura

```bash
cd backend
./gradlew verificarArquitectura
```

Sin Docker, sin base y sin red. Corre **176 pruebas**: las 20 reglas de ArchUnit —aplicadas a las
muestras de `comun-verificaciones` **y al código de negocio**—, los cinco escáneres de fuentes y el revisor de esquema, la
frontera de sistema, el contrato de la API contra las rutas publicadas, las formas y respuestas
que los controladores emiten, y los límites de módulo de Spring Modulith.

Que las muestras viajen con las reglas sigue importando: es lo que hace que cada regla demuestre
que muerde, en vez de pasar en verde por no encontrar nada.

### B · Las dos barreras

`verificarAislamiento` necesita un PostgreSQL 16 **real**: una base en memoria no tiene Row Level
Security y daría falsos verdes. Por omisión levanta un contenedor con Testcontainers:

```bash
cd backend
./gradlew verificarAislamiento
```

Y si no hay Docker —o si el demonio es remoto, ver [DEV-03 §2](solucion-de-problemas.md)—, la
salida documentada es apuntar a un motor que ya exista:

```bash
# Un PostgreSQL 16 local, en un puerto que no choque con nada
/opt/homebrew/opt/postgresql@16/bin/pg_ctl -D /opt/homebrew/var/postgresql@16 \
  -l /tmp/pg16.log -o "-p 55432" start

cd backend
./gradlew verificarAislamiento \
  -Dkamayuk.pruebas.postgres.url=jdbc:postgresql://localhost:55432/postgres \
  -Dkamayuk.pruebas.postgres.usuario=postgres \
  -Dkamayuk.pruebas.postgres.clave=…
```

El usuario tiene que ser **superusuario**: la prueba crea los cuatro roles, les asigna clave y
crea una base nueva por corrida. Los roles son del **clúster** y no de la base, así que los
comparten todas las corridas que apunten a ese motor.

### C · La plataforma

Vive en `infrastructure` y **no se copia aquí**. Levanta PostgreSQL con las **cuatro** bases,
Keycloak con **sus dos realms**, el buzón de correo y Traefik con el enrutado por prefijo:

```bash
cd ../infrastructure
cp despliegue/.env.ejemplo despliegue/.env

# Una clave DISTINTA por marcador. Con `sed` y `$(openssl …)` saldrían todas iguales:
# la sustitución de comandos se evalúa una sola vez, antes que el sed.
python3 - <<'PY'
import re, secrets, pathlib
env = pathlib.Path('despliegue/.env')
env.write_text(re.sub(r'CAMBIAR_\S+', lambda _: secrets.token_hex(24), env.read_text()))
PY

docker compose -f despliegue/plataforma.compose.yaml up -d --wait
```

**Si tu `DOCKER_HOST` apunta a un demonio remoto, ese comando no vale tal cual**: los *bind
mounts* los resuelve el demonio, y si las rutas no existen allí el motor arranca sin sus guiones
de inicialización y **sin ningún error**. Ver [DEV-03 §2 bis](solucion-de-problemas.md).

**`--wait` vuelve antes de que Keycloak sirva sus realms.** Medido: la base, el buzón y Traefik
quedan `healthy` en segundos, y `/realms/sgtm/.well-known/openid-configuration` sigue sin
contestar unos **treinta segundos más**. No es un fallo; es que Keycloak no declara sonda en este
compose. Lo que hay que esperar es esto:

```bash
until curl -sf http://localhost:8180/realms/sgtm/.well-known/openid-configuration > /dev/null
do sleep 5; done
```

Qué queda levantado, y cómo se comprueba que de verdad está:

| Pieza | Dónde | Comprobación que se ejecutó |
|---|---|---|
| PostgreSQL | `localhost:5432` | `rentas`, `catastro`, `normativa` y `caja` existen, con `pg_trgm`, `unaccent`, `btree_gist` y `postgis` en cada una |
| Roles | el clúster | `kamayuk_owner`, `kamayuk_app`, `kamayuk_readonly`, `rol_carga_parametros`; **ninguno superusuario ni con `BYPASSRLS`** |
| Keycloak | <http://localhost:8180> | los dos realms, `sgtm` y `sgtm-ciudadano`, con el emisor que el backend va a comparar |
| Buzón (Mailpit) | <http://localhost:8025> | ahí llega el enlace de primera clave |
| Traefik | <http://localhost:8080> | **404 es lo correcto**: está vivo y no hay ningún sistema detrás todavía |

**Este repositorio todavía no levanta nada contra ella.** No hay `despliegue/compose.yaml` propio
porque no hay imagen que construir; la forma que tendrá el día que la haya está escrita en
[`infrastructure/despliegue/README.md`](https://github.com/hneyra/infrastructure/blob/main/despliegue/README.md).

## 4. Puertos

| Puerto | Quién | Cuándo |
|---|---|---|
| 5432 | PostgreSQL de la plataforma | Compose |
| 8080 | Traefik, el enrutado por prefijo | Compose |
| 8180 | Keycloak | Compose |
| 8025 | Mailpit, el buzón | Compose |

**Los cuatro se pueden mover**, y a veces hay que hacerlo: `KAMAYUK_PUERTO_BASE`, `KAMAYUK_PUERTO_INGRESO`,
`KAMAYUK_PUERTO_IDENTIDAD` y `KAMAYUK_PUERTO_CORREO` en el `.env`. Si mueves el de Keycloak, **mueve con
él `KAMAYUK_OIDC_EMISOR`**: es lo que Keycloak pone en el `iss` de cada token y lo que el backend
compara, y con dos nombres distintos la firma valida y el emisor no cuadra — y el 401 no dice por
qué.

## 5. El `.env` del despliegue

No se versiona, y si alguna vez aparece en un diff, la clave que lleve deja de ser una clave: hay
que **rotarla**, no borrarla del commit. Una clave **distinta por rol**: si el superusuario,
`kamayuk_owner` y `kamayuk_app` comparten clave, la separación de privilegios entera es decorativa.

## 6. Editor

IntelliJ IDEA: importar `backend/` como proyecto Gradle, con el JDK del proyecto en 25. El
directorio `infrastructure/` funciona solo con las extensiones de ESLint.

**Que el editor formatee a su gusto no ayuda**: Checkstyle no revisa formato a propósito, para no
discutir con Spotless. Lo que sí revisa —y es fácil de incumplir con el teclado en español— son
los **identificadores con tilde**: `alicuota`, nunca `alícuota`.
