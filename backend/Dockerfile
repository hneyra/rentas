# syntax=docker/dockerfile:1
#
# Dos imagenes del mismo arbol de fuentes, y esa es la decision principal de este
# archivo:
#
#   `aplicacion`  el artefacto unico de ADR-0003, desplegado en los perfiles web y
#                 batch. Se conecta como sgtm_app: sin DDL, sin BYPASSRLS, sin ser
#                 propietaria de ninguna tabla y sin DELETE (ARQ-03 §4).
#   `migrador`    el proceso que aplica el esquema como sgtm_owner y termina.
#
# Estan separadas porque las credenciales son distintas y no deben convivir en el
# mismo contenedor: las de sgtm_owner existen durante la migracion y desaparecen
# con ella. Meterlas en la imagen de la aplicacion —aunque solo fuera «para el
# arranque»— le daria a un proceso de larga vida, expuesto en HTTP, la capacidad
# de hacer DDL sobre el padron de todas las municipalidades.
#
# La compilacion, en cambio, es una sola: las dos salen del mismo `construccion`,
# asi que el codigo que migra en produccion es exactamente el que CI verifico.

# El contexto de compilacion es la RAIZ del repositorio, no `backend/`, y no es un
# detalle: el catalogo de las 134 opciones (NEG-03) vive en `docs/` y el build lo
# copia a los recursos de kamayuk-rentas-seguridad. Con el contexto en `backend/` ese archivo
# sencillamente no existia dentro de la imagen, la tarea que lo copia se saltaba
# con NO-SOURCE, y el jar salia sin catalogo —compilando en verde—. El sintoma
# aparecio mucho despues: la aplicacion arrancaba y servia peticiones, pero la
# implantacion de una municipalidad no encontraba ninguna opcion que sembrar, asi
# que nadie podia entrar a ninguna pantalla.
#
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS construccion

WORKDIR /origen/backend

# Primero lo que casi nunca cambia, para que la capa de dependencias se reutilice
# entre compilaciones. `--version` descarga la distribucion de Gradle y nada mas.
COPY backend/gradlew backend/gradle.properties backend/settings.gradle.kts backend/build.gradle.kts ./
COPY backend/gradle/ gradle/
RUN ./gradlew --version --no-daemon

COPY backend/buildSrc/ buildSrc/
COPY backend/config/ config/

# Lo unico que se trae de fuera de backend/, y con nombre propio para que se vea
# en el diff si alguien anade una segunda dependencia externa por descuido.
COPY docs/10-negocio/catalogo-de-opciones.md /origen/docs/10-negocio/

COPY backend/ ./

# Sin pruebas: las bloqueantes necesitan Docker y ya corrieron en CI. Una imagen
# que las repitiera dentro del build las volveria a ejecutar en cada despliegue,
# con un motor distinto al verificado.
# `-Pkamayuk.sinLibreriasComunes` (C-7, punto 5). Sin ella el build se para en el `require` de
# `settings.gradle.kts`: el composite build de `comun-verificaciones` vive en el clon hermano
# `infrastructure/`, que esta FUERA del contexto de esta imagen y no se puede meter dentro —un
# `.dockerignore` no puede describir un contexto que es el directorio padre—. Era el hueco 9.4 de
# P5A, heredado de P3, y llevaba ahi sin que nada lo midiera porque ningun CI construye todavia
# esta imagen.
#
# Se puede porque `comun-verificaciones` es `testImplementation` y solo de
# `kamayuk-rentas-aplicacion`: aqui no corre ni una prueba. Y no abre una puerta, porque el
# `build.gradle.kts` de la raiz **hace fallar toda tarea de prueba** mientras la propiedad este
# puesta: o esta la libreria, o no hay verificacion.
RUN ./gradlew --no-daemon -Pkamayuk.sinLibreriasComunes \
    :kamayuk-rentas-aplicacion:bootJar :kamayuk-rentas-esquema:installDist

# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre AS aplicacion

# Usuario sin privilegios y sin shell. Un proceso que solo tiene que escuchar en
# un puerto no necesita ser root dentro del contenedor.
RUN useradd --system --uid 10001 --shell /usr/sbin/nologin kamayuk

# curl entra por una sola razon: la sonda de vida del orquestador se ejecuta
# DENTRO del contenedor, y la imagen base no trae ningun cliente HTTP. La
# alternativa —comprobar solo que el puerto acepta conexiones— daria por sana una
# aplicacion que escucha y no llega a la base, que es justo el fallo que se quiere
# detectar antes de dar el despliegue por bueno.
RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=construccion /origen/backend/kamayuk-rentas-aplicacion/build/libs/rentas.jar /opt/kamayuk/rentas.jar
USER 10001
EXPOSE 8080

# Sin CMD con argumentos: la configuracion entra por variables de entorno
# (KAMAYUK_DB_URL, KAMAYUK_DB_USUARIO, KAMAYUK_DB_CLAVE) y por SPRING_PROFILES_ACTIVE,
# que es lo unico que distingue el perfil web del batch.
ENTRYPOINT ["java", "-jar", "/opt/kamayuk/rentas.jar"]

# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre AS migrador

RUN useradd --system --uid 10002 --shell /usr/sbin/nologin migrador
COPY --from=construccion /origen/backend/kamayuk-rentas-esquema/build/install/migrar /opt/migrador
USER 10002

# Corre y termina. El orquestador espera a que salga con exito antes de arrancar
# la aplicacion: un esquema a medias con la aplicacion ya sirviendo peticiones es
# el estado que este orden existe para impedir.
ENTRYPOINT ["/opt/migrador/bin/migrar"]
