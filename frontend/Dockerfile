# syntax=docker/dockerfile:1
#
# `kamayuk-rentas-interfaz`: la interfaz de rentas, compilada y servida como archivos estaticos.
#
# ## POR QUE NO SE LLAMA `kamayuk-rentas-web`, que es como el issue #44 la nombra
#
# Porque `kamayuk-rentas-web` YA ES OTRA COSA, y una que esta viva: en
# `infrastructure/src/descriptor.ts` es el nombre del **`Deployment` y el `Service` del BACKEND**
# con el perfil `web` de Spring —lo compone `despliegueDelPerfil(e, "web", true)` como
# `kamayuk-${SISTEMA}-${perfil}`—, o sea un proceso que no tiene nada que ver con este.
#
# Un `Service` de Kubernetes y una imagen de un registro son espacios de nombres distintos, asi
# que la colision **no seria un error**: seria peor. Dos artefactos del mismo producto llamados
# igual, uno sirviendo la API y otro archivos estaticos, y a partir de ahi cualquier frase del
# tipo «desplega `kamayuk-rentas-web`» tiene dos respuestas. Y el dia que alguien nombre mal un
# `Service` a partir de la imagen, lo que sale no es un fallo de despliegue —que se ve— sino un
# `Service` repartiendo entre las dos: una parte de las peticiones de la API contestadas por un
# `nginx` que solo sirve archivos.
#
# `interfaz` es ademas el vocabulario que `infrastructure` ya usa para la del monolito
# (`sgtm-interfaz`) y el que `caja` eligio, por escrito y por este mismo motivo
# (`kamayuk-caja-interfaz`). Un nombre para una cosa.
#
# ## Por que NO se reutiliza `backend/Dockerfile`
#
# Aquel compila con Gradle y su contexto es la RAIZ del repositorio, porque tiene `COPY` que salen
# de `backend/`. Este tiene su contexto en `frontend/` y su `.dockerignore` es
# `frontend/.dockerignore`.
#
# Decia «este no necesita nada de fuera de `frontend/`», y desde #75 ya no es cierto: necesita los
# tres paquetes de `kamayuk-lib`, que entran por un CONTEXTO CON NOMBRE y no por el principal
# —ver la etapa `construccion`—. Las dos decisiones van juntas: un
# `.dockerignore` solo se aplica desde la raiz del contexto, de modo que el de la raiz —que ademas
# excluye `frontend/` entero— no dice nada de esta imagen.
#
# ## Las dos etapas se tratan distinto a proposito
#
# `construccion` se tira: nada de ella se publica, y por eso su base es la etiqueta movil
# `node:22-alpine` —la version mayor es la que `frontend.yml` declara (`node-version: "22"`) y lo
# que fija que se instale es `yarn.lock` con `--frozen-lockfile`—.
#
# `interfaz` SI se publica, asi que su base va clavada: `nginx:1.31.5-alpine`, no `stable-alpine`
# ni `1.31`. Es lo que permite que un escaneo de imagenes diga de que version habla, y actualizar
# el numero es la correccion cuando el escaneo encuentre algo, no ignorar el hallazgo.

# ---------------------------------------------------------------------------
FROM node:22-alpine AS construccion

# EL `WORKDIR` REPRODUCE LA DISPOSICION DE CLONES HERMANOS, Y NO ES COSMETICO (#75).
#
# `frontend/package.json` declara `@kamayuk/{api,formato,sesion}` como
# `link:../../kamayuk-lib/paquetes/*`, y yarn resuelve eso contra el DISCO. Para que la misma
# ruta relativa valga aqui dentro, el arbol de la imagen tiene que tener la misma forma que el
# de quien compila:
#
#     /obra/rentas/frontend     <- aqui, o sea `../../` = /obra
#     /obra/kamayuk-lib         <- ahi cae el contexto con nombre
#
# Acortarlo a `/obra/frontend` —que es lo que apetece— hace que `../../kamayuk-lib` resuelva a
# `/kamayuk-lib` y el `yarn install` falle. **Y no fallaria hoy**: mientras nada de `src/`
# importe `@kamayuk/*`, la imagen se construye igual y el defecto espera. Por eso la profundidad
# no se deja a la vista de nadie: la deriva `imagen-y-despliegue.test.ts` del `link:` declarado.
WORKDIR /obra/rentas/frontend

# EL CLON HERMANO, POR UN CONTEXTO CON NOMBRE DE BUILDKIT (#75).
#
# El contexto principal de esta imagen es `frontend/` y el hermano vive DOS niveles por encima,
# o sea fuera de lo que Docker puede copiar. Subir el contexto a la raiz del repositorio no vale
# por dos motivos medidos: el `.dockerignore` de la raiz excluye `frontend/` entero, y aun asi
# `kamayuk-lib` es OTRO repositorio y seguiria fuera. Y el arreglo del backend tampoco
# transfiere: `-Pkamayuk.sinLibreriasComunes` se pudo escribir porque su libreria es
# `testImplementation` y la imagen no corre pruebas; estas tres son dependencias de EJECUCION
# del bundle, asi que no existe un «construir el artefacto sin la libreria».
#
# Quien lo pasa: `--build-context kamayuk-lib=…` (buildx), `build-contexts:` en
# `publicar-imagenes.yml` y `additional_contexts:` en `despliegue/compose.yaml`. Los tres tienen
# que decir el mismo nombre, y eso lo ata una prueba.
#
# SE COPIA `paquetes/` Y NADA MAS, a proposito: un contexto con nombre entra por su propia raiz
# y **ningun `.dockerignore` lo acota** —el de `frontend/` solo filtra el contexto principal—.
# Medido el 2026-09-12: `kamayuk-lib` no trae `.dockerignore` y su `node_modules` pesa 160 MB;
# un `COPY --from=kamayuk-lib .` se lo llevaria dentro, con el `.git` y cualquier `.env`.
COPY --from=kamayuk-lib paquetes/ /obra/kamayuk-lib/paquetes/

# Primero el manifiesto y el candado, y nada mas: asi la capa de dependencias se reutiliza
# mientras esos dos archivos no cambien, que es lo que hace barato reconstruir tras tocar una
# pantalla. `--frozen-lockfile` es lo mismo que exige `frontend.yml`: si `package.json` y
# `yarn.lock` no cuadran, esto falla aqui en vez de instalar en silencio una version distinta de
# la que se verifico.
#
# Y ojo con lo que NO garantiza: un `link:` a un directorio que no existe se instala con codigo
# 0 y sin avisar (medido). Lo que lo detecta es `verificaciones/enlace-con-kamayuk-lib.test.ts`,
# que corre en `yarn verificar`, no aqui.
COPY package.json yarn.lock ./
RUN yarn install --frozen-lockfile

COPY . .

# EL PROXY DE DATOS, APAGADO Y DICHO (AC-3 de #44).
#
# Vite sustituye `import.meta.env.VITE_*` **al construir**, asi que con esta variable en `false`
# la condicion de `src/arranque.ts` se pliega y el `import()` dinamico del proxy —con las
# dieciocho operaciones y todas las cifras del artboard— se cae del paquete. Medido en F-4: la
# diferencia entre las dos construcciones son 33 535 bytes y un trozo `proxy-*.js` entero.
#
# Es la PRIMERA de dos vallas, y ninguna sobra. `vite build` no lee `.env.development` —solo se
# carga en modo desarrollo—, o sea que en teoria bastaria con no hacer nada; pero «el paquete sale
# limpio porque nadie se acordo de encenderlo» es una propiedad que se pierde el dia que alguien
# cree un `.env.production`. Escribirlo aqui lo hace una decision y no una casualidad. La segunda
# valla es `.dockerignore`, que deja fuera del contexto **todos** los `.env*`.
ENV VITE_KAMAYUK_PROXY_DE_DATOS=false

RUN yarn build

# LOS MAPAS DE FUENTE NO SE PUBLICAN, y es una decision con dos motivos medidos.
#
# `vite.config.ts` declara `build.sourcemap: true` desde F-1, asi que `dist/` sale con un `.map`
# por trozo. No se toca esa opcion —`yarn build` tiene que seguir dando lo mismo para todo lo que
# ya se mide contra el— y se retiran **aqui**, que es donde importa:
#
#   1. Un `.map` lleva dentro **el codigo fuente entero**. Publicarlo en la interfaz de un sistema
#      de recaudacion se lo entrega a cualquiera que abra las herramientas del navegador.
#   2. Llevan las cifras del artboard. Esta medido en I-2: «Rufina Medina Medina» y «62,418»
#      aparecen en el `.map` y **cero** veces en el `.js`. O sea que sin este paso, el AC-3 —cero
#      apariciones sobre el `dist/` de la imagen— seria falso por los mapas, aunque el codigo que
#      se ejecuta estuviera limpio.
#
# Se quita tambien el comentario `sourceMappingURL` que quedaria apuntando a un archivo que ya no
# esta: dejarlo daria un 404 en la consola de cada navegador que abriera las herramientas, y un
# aviso que sale siempre es un aviso que nadie lee.
RUN find dist -name '*.map' -delete \
    && find dist -name '*.js' -exec sed -i '/^\/\/# sourceMappingURL=/d' {} + \
    && find dist -name '*.css' -exec sed -i '/^\/\*# sourceMappingURL=/d' {} +

# ---------------------------------------------------------------------------
FROM nginx:1.31.5-alpine AS interfaz

# La base trae su propio `index.html` de bienvenida y un `50x.html`. Se vacia el directorio antes
# de copiar para que lo que la imagen sirve sea EXACTAMENTE `dist/` y nada mas — que es lo que
# hace legible un `ls /usr/share/nginx/html` como comprobacion, en vez de dejar dos archivos
# ajenos que hay que aprenderse para saber que sobran.
RUN rm -rf /usr/share/nginx/html/*

COPY --from=construccion /obra/rentas/frontend/dist/ /usr/share/nginx/html/
COPY nginx.conf /etc/nginx/conf.d/default.conf

# LO QUE SE SIRVE NO LLEVA CODIGO FUENTE NI UNA CIFRA DEL ARTBOARD (AC-2 y AC-3 de #44).
#
# ## Por que la comprobacion esta AQUI y no en la etapa de construccion
#
# La primera version miraba `dist/` **dentro de `construccion`**, y medirla enseno que eso deja
# el agujero abierto: un `COPY --from=construccion /obra/rentas/frontend/src/ /usr/share/nginx/html/src/` en
# ESTA etapa **no lo ve nadie**, porque la comprobacion ya habia corrido en otra imagen. Medido:
# con ese `COPY` puesto el `docker build` sale en VERDE y la imagen sirve
# `/src/api/identidad.ts` con **200 y 13 712 bytes** — el codigo fuente de la puerta de identidad,
# publicado.
#
# Asi que se comprueba **lo que el contenedor va a servir**, en la ultima etapa y despues del
# ultimo `COPY`. La regla general que deja: una comprobacion sobre un artefacto intermedio no
# afirma nada sobre el artefacto que se publica.
#
# ## Lo que busca
#
# `vite build` no deberia emitir un solo `.ts`/`.tsx`, pero «no deberia» no es una comprobacion.
#
# ## LA OTRA MITAD DE #44 —«ni una cifra del artboard»— ESTA ABIERTA DESDE #90, Y SE DICE AQUI
#
# Hasta la V6, la imagen se negaba a construirse si lo servido contenia una de cinco cadenas del
# artboard. Era cierto porque las cifras inventadas vivian en `datos/prototipo.ts`, detras de
# `VITE_KAMAYUK_PROXY_DE_DATOS`, y Rollup plegaba el `import()` dinamico entero con la bandera
# apagada — medido: 193 592 bytes y cero cifras, frente a 227 205 con ellas.
#
# **Con la V8 no se sostiene.** Las cifras de ejemplo del artboard SON el contenido de las cuarenta
# pantallas: viven en sus definiciones, que se importan estaticamente y se comparan campo por campo
# contra el artboard (#86). Medido sobre el `dist` de hoy: «9,418,204.60» esta dentro.
#
# Las cinco cadenas de V6 se retiran en vez de dejarse puestas, y el motivo es que **ya no pueden
# aparecer**: el prototipo se borro en #90. Una guarda que no puede fallar no protege nada, y
# dejarla habria hecho creer que la propiedad se cumple.
#
# Vuelve con #97, cuando las pantallas lean de la API. Lo que NO ha cambiado y sigue mordiendo es
# lo de abajo: **lo servido no lleva codigo fuente**.
RUN set -eu; \
    servido=/usr/share/nginx/html; \
    if find "$servido" \( -name '*.ts' -o -name '*.tsx' \) | grep -q .; then \
        echo "Lo que se sirve lleva codigo fuente dentro:"; \
        find "$servido" \( -name '*.ts' -o -name '*.tsx' \); exit 1; \
    fi; \
    for cadena in 'Rufina Medina Medina' '170,616.75' 'SULLON VILCHEZ'; do \
        if grep -rqF "$cadena" "$servido"; then \
            echo "Lo que se sirve lleva una cifra del prototipo V6, que se borro en #90: «$cadena»."; \
            grep -rlF "$cadena" "$servido"; exit 1; \
        fi; \
    done; \
    echo "lo servido esta limpio: sin fuentes y sin rastro del prototipo V6 (ver #97 para las de V8)"

# Sin root, y esto es lo unico que lo consigue: la configuracion de `nginx:*-alpine` trae `User`
# VACIO, o sea que sin este `USER` la interfaz correria como root. El usuario `nginx` ya existe en
# la base con uid 101.
#
# Ponerle `USER` a secas no basta, y hay tres cosas que arreglar:
#
#   1. `/var/cache/nginx` — el maestro crea ahi sus directorios temporales al arrancar. Sin
#      permiso de escritura no es un aviso: es `[emerg] mkdir() ... failed (13: Permission
#      denied)` y el contenedor no levanta.
#   2. `/etc/nginx` — dos guiones del entrypoint escriben ahi en CADA arranque
#      (`30-tune-worker-processes.sh` hace `sed -i.bak` sobre `nginx.conf`, y
#      `10-listen-on-ipv6-by-default.sh` empieza por un `touch` de `default.conf`). Sin permiso
#      los dos abortan con un error por consola en cada arranque.
#   3. El pid, que por omision va a `/run/nginx.pid`. Se manda a `/tmp`, escribible por cualquiera.
#
# El `chown` es a `nginx:0` y el `chmod` a `g=u` —por GRUPO y no por usuario— para que siga
# funcionando si el orquestador decide correrlo con un uid arbitrario del grupo 0, que es como
# despliega OpenShift.
#
# Y se borra `user nginx;` del conf principal porque nginx no puede cambiar de usuario cuando ya
# no es root: la directiva queda muerta y el arranque la anuncia en cada reinicio.
RUN sed -i \
        -e '/^user  *nginx;/d' \
        -e 's#^pid .*#pid /tmp/nginx.pid;#' \
        /etc/nginx/nginx.conf \
    && chown -R nginx:0 /var/cache/nginx /etc/nginx \
    && chmod -R g=u /var/cache/nginx /etc/nginx

# EL UID EN NUMERO, no `USER nginx`, y no es estilo (AC-2 de #44).
#
# `infrastructure/src/descriptor.ts` endurece todo contenedor con `runAsNonRoot: true` —«el
# endurecimiento que no admite excepcion», y aqui es `SEGURIDAD`—. Con un `USER` no numerico el
# kubelet **no puede comprobar** que el usuario no es root y se niega a arrancar el contenedor,
# con un `CreateContainerConfigError` que solo aparece al desplegar. Por eso las dos imagenes del
# backend ya usan `USER 10001` y `USER 10002`.
#
# El 101 es el del usuario `nginx` de la base, asi que el `chown` de arriba —que resuelve el
# nombre en tiempo de construccion, cuando todavia se es root— apunta a este mismo uid.
USER 101
EXPOSE 8080

# La sonda se ejecuta DENTRO del contenedor, y aqui —al reves que en `backend/Dockerfile`— no hay
# que instalar ningun cliente: el `wget` de busybox viene en la base.
#
# Pide `/index.html` y no `/`: con el `try_files` de `nginx.conf`, `/` devuelve la pantalla
# **aunque el directorio este vacio**, porque cualquier cosa cae al `index.html`... que tampoco
# estaria, pero la peticion de `/` con `index index.html` daria 403 y no 200. Pedir el archivo
# **por su nombre** es lo unico que distingue «nginx levantado» de «nginx levantado sobre el
# `dist/` que se copio»: si el `COPY` fallara, esto da 404 y el contenedor sale `unhealthy`.
#
# En forma JSON, o sea sin shell de por medio: `wget --spider` ya sale con codigo 1 cuando no
# puede conectar o recibe un 4xx, de modo que el `|| exit 1` que la forma de shell obliga a
# escribir no anade nada y se lleva por delante un proceso mas.
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD ["wget", "--spider", "-q", "http://127.0.0.1:8080/index.html"]
