# D0 — Desarrollo

Cómo montar el ambiente local de `rentas`, arrancarlo, depurarlo y probarlo. Escrito para quien
acaba de clonar el repositorio y quiere ver algo funcionando **hoy**.

| Documento | Para qué |
|---|---|
| [DEV-01 — Entorno local](entorno-local.md) | Qué instalar, el clon hermano que **no es opcional**, y las cuatro formas de trabajar |
| [DEV-02 — Pruebas](pruebas.md) | Qué verifica qué, cómo correr una sola, y cómo probar sin Docker |
| [DEV-03 — Cuando algo no arranca](solucion-de-problemas.md) | Los errores que ya costaron una tarde, con su causa |

## Lo primero, y no es un detalle

**`infrastructure` tiene que estar clonado al lado.** Las barreras que este backend ejecuta viven
allí y se consumen como *composite build*; sin ese clon, Gradle no llega ni a configurar el
proyecto.

```bash
cd ..                                                   # el directorio que contiene a rentas/
git clone https://github.com/hneyra/infrastructure
```

Queda así, y las rutas de este documento cuentan con ello:

```
IdeaProjects/
├── infrastructure/     la plataforma y las barreras comunes
├── rentas/          este repositorio
└── sgtm/               el archivo historico (opcional, pero se consulta a diario)
```

## Lo mínimo para empezar

```bash
# 1 · Prerrequisitos. Docker sólo hace falta para la plataforma; hay salida sin él
java -version && node --version && yarn --version

# La versión de Node NO se elige: la dice `.nvmrc`, y es la misma que instala la CI (#289)
cat .nvmrc   # y `node --version` de arriba tiene que decir esa mayor

# 2 · Las barreras de arquitectura. NO necesitan Docker, ni base de datos, ni red
cd backend && ./gradlew verificarArquitectura

# 3 · El descriptor de despliegue. Tampoco necesita Pulumi, ni token, ni cluster
cd ../infrastructure && yarn install && yarn verificar
```

Con eso corren las barreras. **Y la interfaz se mira sin nada más levantado**, que es la cuarta
forma de trabajar y la más barata de todas:

```bash
# 4 · Las 40 pantallas, sin PostgreSQL, sin Keycloak, sin Traefik y sin backend
cd ../frontend && yarn install && yarn dev       # http://localhost:5173/rentas/
```

El árbol de módulos va **sembrado** con la captura de la instalación y la puerta de identidad se
esquiva: lo enciende `VITE_KAMAYUK_SIN_PLATAFORMA`, que `.env.development` trae puesto y que
`yarn build` no puede leer. Contra la plataforma de verdad es `yarn dev:con-plataforma`. Los dos
niveles, con lo que se ve en cada uno, están en [DEV-01 §3D](entorno-local.md).

### Node: no hay gestor de versiones, así que es un tarball

`.nvmrc` dice qué versión usa este repositorio y `frontend/package.json` la exige al instalar
—`engines` es un pin desde #289, no un suelo: yarn falla si no cuadra, y medido con Node 22.14.0
no sólo en `yarn install` sino también en `yarn run` («Commands cannot run with an incompatible
environment»)—. No hay `nvm`, `volta`,
`fnm` ni `asdf` en el puesto, así que se instala el tarball oficial y se mueven los enlaces:

```bash
V=$(cat .nvmrc)
cd ~/.local/toolchain
curl -O https://nodejs.org/dist/v$V/node-v$V-linux-x64.tar.xz
curl -O https://nodejs.org/dist/v$V/SHASUMS256.txt
grep "node-v$V-linux-x64.tar.xz" SHASUMS256.txt | sha256sum -c -   # y sólo si dice OK
tar -xf node-v$V-linux-x64.tar.xz
~/.local/toolchain/node-v$V-linux-x64/bin/corepack enable \
  --install-directory ~/.local/toolchain/node-v$V-linux-x64/bin   # yarn sale de aquí
for b in node npm npx yarn; do ln -sfn ~/.local/toolchain/node-v$V-linux-x64/bin/$b ~/.local/bin/$b; done
```

**El árbol de la versión anterior no se borra**, y eso es lo que hace reversible la subida: para
volver, los mismos `ln -sfn` apuntando al directorio viejo. Nada más se tocó.

> Este párrafo decía «lo que todavía no hay es una aplicación que arrancar: no existe ni una clase
> de negocio, así que no hay `bootRun`, ni API, ni pantalla». Era cierto en F-1 y dejó de serlo en
> P5A: hoy hay 3 080 pruebas de negocio y 40 pantallas. Una guía que describe un estado anterior
> no se lee como desactualizada — se lee como instrucciones.

## Qué comando para qué tarea

| Quiero… | Comando | Dónde |
|---|---|---|
| Las reglas de arquitectura y los escáneres | `./gradlew verificarArquitectura` | `backend/` |
| El aislamiento multi-tenant | `./gradlew verificarAislamiento` | `backend/` |
| Todo, más el formato | `./gradlew build` | `backend/` |
| Arreglar el formato | `./gradlew spotlessApply` | `backend/` |
| Verificar el descriptor | `yarn verificar` | `infrastructure/` |
| Verificar la interfaz | `yarn verificar` | `frontend/` |
| Mirar las 40 pantallas sin plataforma | `yarn dev` | `frontend/` |
| Mirar la interfaz contra la plataforma | `yarn dev:con-plataforma` | `frontend/` |
| Levantar la plataforma | `docker compose -f despliegue/plataforma.compose.yaml up -d --wait` | `../infrastructure/` |
| Lo que hay que pasar antes de un PR | `./gradlew build verificarAislamiento verificarArquitectura` · `yarn verificar` | ambos |

## Las dos frases que gobiernan todo lo demás

**Ejecutar la prueba vale más que razonar sobre ella**, y **una verificación tiene que demostrarse
capaz de fallar**. Por eso aquí no hay ningún comando que «debería funcionar»: los de estos
documentos **se ejecutaron**, y donde algo falla en una máquina concreta se dice en
[DEV-03](solucion-de-problemas.md) en vez de omitirlo.

**Una prueba bloqueante no se omite a sí misma.** Sin motor de base de datos, `verificarAislamiento`
**falla**; no se salta. Si alguna vez encuentras la forma de ponerla en verde sin PostgreSQL, has
encontrado un defecto, no un atajo.
