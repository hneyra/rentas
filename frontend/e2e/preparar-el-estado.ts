import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';

import { CUENTAS } from './instalacion.ts';

/**
 * Deja escrito un estado de acceso VACIO para cada cuenta, antes de que corra nada.
 *
 * <h2>Existe para que el AC6 pueda saltarse de verdad</h2>
 *
 * Los caminos declaran su `storageState` con `test.use(...)`, y Playwright crea el contexto del
 * navegador —leyendo ese archivo— **antes** de entrar en el cuerpo de la prueba. Sin instalacion
 * levantada el proyecto de identidad se salta y no llega a escribir ningun archivo, de modo que
 * los caminos no se saltarian: reventarian antes de empezar con un `ENOENT` sobre
 * «e2e/.estado/administrador.json», que no habla de la instalacion que falta ni dice como
 * levantarla.
 *
 * Un estado vacio es exactamente lo que un navegador recien abierto tiene, asi que no finge
 * ninguna sesion: solo hace que el contexto se pueda crear para que la prueba llegue a su
 * propia comprobacion previa y se salte diciendo lo que hay que correr.
 *
 * Se sobrescribe con el estado de verdad en cuanto una cuenta entra.
 */
export default function prepararElEstado(): void {
  for (const cuenta of Object.values(CUENTAS)) {
    mkdirSync(dirname(cuenta.estado), { recursive: true });
    // Solo si no esta: un archivo que ya existe es de una corrida anterior que SI entro, y
    // machacarlo obligaria a repetir el formulario sin ninguna necesidad.
    if (!existsSync(cuenta.estado)) {
      writeFileSync(cuenta.estado, JSON.stringify({ cookies: [], origins: [] }));
    }
  }
}
