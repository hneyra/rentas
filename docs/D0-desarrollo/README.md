# D0 — Desarrollo

Cómo montar el ambiente local de `rentas`, arrancarlo, depurarlo y probarlo. Escrito para quien
acaba de clonar el repositorio y quiere ver algo funcionando **hoy**.

| Documento | Para qué |
|---|---|
| [DEV-01 — Entorno local](entorno-local.md) | Qué instalar, el clon hermano que **no es opcional**, y las tres formas de trabajar |
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

# 2 · Las barreras de arquitectura. NO necesitan Docker, ni base de datos, ni red
cd backend && ./gradlew verificarArquitectura

# 3 · El descriptor de despliegue. Tampoco necesita Pulumi, ni token, ni cluster
cd ../infrastructure && yarn install && yarn verificar
```

Con eso ya corre todo lo que hoy hay que correr en este repositorio. **Lo que todavía no hay es
una aplicación que arrancar**: no existe ni una clase de negocio, así que no hay `bootRun`, ni
API, ni pantalla. Levantar la plataforma sirve para tener la base y la identidad esperando —y
para descubrir hoy lo que si no se descubre el día que haya código—, y está en
[DEV-01 §3](entorno-local.md).

## Qué comando para qué tarea

| Quiero… | Comando | Dónde |
|---|---|---|
| Las reglas de arquitectura y los escáneres | `./gradlew verificarArquitectura` | `backend/` |
| El aislamiento multi-tenant | `./gradlew verificarAislamiento` | `backend/` |
| Todo, más el formato | `./gradlew build` | `backend/` |
| Arreglar el formato | `./gradlew spotlessApply` | `backend/` |
| Verificar el descriptor | `yarn verificar` | `infrastructure/` |
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
