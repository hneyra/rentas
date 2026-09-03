# ADR-0003 — Monolito modular con Spring Modulith

**Estado:** Aceptado
**Fecha:** 2026-08-17

## Contexto

El sistema tiene doce contextos acotados ([ARQ-01](https://github.com/hneyra/sgtm/blob/migracion-a-microservicios/docs/30-arquitectura/contextos-acotados.md)) y procesos de
naturaleza muy distinta: caja, que es interactiva y sensible a la latencia, y emisión masiva, que
procesa padrones enteros de noche.

La tentación de partirlo en servicios es fuerte, y sería un error caro: el equipo que mantendrá
esto en una municipalidad no opera doce despliegues.

## Decisión

**Un solo artefacto desplegable, internamente modular**, con Spring Modulith verificando los
límites en el build. El mismo artefacto arranca en dos perfiles:

- **web** — la aplicación interactiva.
- **batch** — emisión masiva, generación de valores, cálculos de padrón.

Cada contexto acotado es un módulo Gradle y un módulo de Spring Modulith. Un contexto se importa
solo por su paquete raíz; nunca por sus paquetes internos.

## Consecuencias

- Los límites son **verificables**: `verificarArquitectura` falla si un contexto importa
  `dominio` o `infraestructura` de otro.
- La transacción es local. Un cobro que asienta abonos y cierra un convenio no necesita
  coordinación distribuida, que es exactamente el problema que un sistema tributario no debería
  tener.
- Separar un contexto en servicio propio más adelante sigue siendo posible, y más barato desde
  módulos con límites verificados que desde un monolito sin ellos.
- El perfil batch escala aparte sin duplicar el código ni el despliegue.
- **Advertencia heredada:** un paquete que solo contiene `package-info.java` no es un módulo para
  Spring Modulith. Los doce contextos —vacíos cuando se decidió esto— no se verificaban como
  módulos hasta tener una clase; hoy los doce tienen código y `ModulosTest` exige que los doce
  se detecten.

## Alternativas consideradas

- **Microservicios por contexto.** Descartado: doce despliegues, doce bases de datos o una
  compartida —que sería lo peor de ambos mundos—, y transacciones distribuidas en el camino del
  dinero.
- **Monolito sin módulos.** Es a lo que se llega solo, y en cinco años cualquier cambio en
  catastro rompe caja. Lo que evita eso no es la disciplina: es que el build falle.
