import { describe, expect, it } from 'vitest';

import { TONO_SIN_RECONOCER, reconocido, tonoDe } from './tono.ts';

/**
 * **El reparto de tonos de ESTE sistema** (#153, #175).
 *
 * Su unica prueba vivia en `Pantalla.test.tsx` —«la columna de situacion se pinta como insignia,
 * con el tono que el TEXTO pide»—, que se fue a `@kamayuk/ui` con el interprete. Alli sigue
 * probandose que la insignia lleva **el tono que el sistema dice**, con un reparto inventado; lo
 * que no puede probarse alli es **este** reparto, porque su vocabulario es de Rentas y la libreria
 * no lo nombra. Asi que se queda aqui, que es donde vive lo que prueba.
 *
 * Lo que barre las 40 definiciones —que ninguna columna de insignia llegue al verde sin regla— es
 * otra cosa y esta en `verificaciones/la-insignia-no-se-pinta-verde-sin-regla.test.ts`. Aqui se
 * prueba el reparto; alli, que nadie lo esquive.
 */
describe('el tono de una insignia sale de lo que DICE la celda', () => {
  it('lo que ya ha ido mal pide accion hoy', () => {
    for (const texto of ['En coactiva', 'Observado', 'Vencida', 'Denegado']) {
      expect(tonoDe(texto), texto).toBe('mal');
    }
  });

  it('lo que va a ir mal tiene plazo, pero corre', () => {
    for (const texto of ['Con deuda', 'Por vencer', 'En tramite', 'En trámite']) {
      expect(tonoDe(texto), texto).toBe('atencion');
    }
  });

  it('el verde se GANA: esta enumerado, y no es lo que sobra (#175)', () => {
    for (const texto of ['Conforme', 'Vigente', 'VIGENTE', 'Pagado', 'Al día', 'Cancelada', 'Activa', 'Inspeccionado', 'Bajo']) {
      expect(tonoDe(texto), texto).toBe('ok');
    }
  });

  it('LO QUE NO RECONOCE NINGUNA REGLA sale con el tono de «no se», nunca con el de «conforme»', () => {
    // Las cuatro son las frases que `GET /indicadores/trabajo-parado` publica en la columna de
    // insignia de `ini-parado` —`porQueCuestaDinero`, que es una frase y no un estado—, copiadas
    // del enumerado `FrenteDeTrabajo` del backend de este repositorio. Hasta #175 las cuatro
    // salian VERDES: la interfaz pintaba de «conforme» trabajo parado que cuesta dinero.
    const frases = [
      'sin emitir no se pueden notificar ni cobrar, y prescriben',
      'existen, no cobran, y el plazo de prescripcion les corre igual',
      'el expediente esta abierto y el procedimiento no ha empezado',
      'tienen ficha catastral y no generan deuda predial',
    ];
    for (const frase of frases) {
      expect(reconocido(frase), frase).toBe(false);
      expect(tonoDe(frase), frase).toBe(TONO_SIN_RECONOCER);
      expect(tonoDe(frase), frase).not.toBe('ok');
    }
  });

  it('y «no se» NO es `ok`: es el unico de los cuatro tonos que no juzga la fila', () => {
    expect(TONO_SIN_RECONOCER).toBe('info');
    expect(TONO_SIN_RECONOCER).not.toBe('ok');
    // Y tampoco es `atencion`: un aviso que sale siempre deja de leerse. Ver el javadoc.
    expect(TONO_SIN_RECONOCER).not.toBe('atencion');
  });

  it('«Emitida» ya NO es verde: emitir no es cobrar (#175)', () => {
    // Estaba en la lista de buenos de #153 por estar del lado bueno del `return ok`, no por una
    // decision. Un valor emitido y sin notificar es **uno de los cuatro frentes que `ini-parado`
    // cuenta**: darle verde seria volver a decir «conforme» sobre lo que cuesta dinero.
    expect(tonoDe('Emitida')).toBe(TONO_SIN_RECONOCER);
  });

  it('el ancla de palabra no es adorno: «Trabajo parado» no es «Bajo»', () => {
    // Sin `\b`, el `bajo` del riesgo ITSE casaria dentro de «tra**bajo**» y la fila mas fea de la
    // pantalla de trabajo parado saldria verde por una subcadena.
    expect(tonoDe('Trabajo parado')).toBe(TONO_SIN_RECONOCER);
    expect(tonoDe('Bajo')).toBe('ok');
  });

  it('no distingue mayusculas: «VENCIDA» sigue siendo mal', () => {
    expect(tonoDe('VENCIDA')).toBe('mal');
  });

  it('el texto vacio tampoco es «conforme»', () => {
    expect(tonoDe('')).toBe(TONO_SIN_RECONOCER);
  });

  it('`info` SI sale de aqui desde #175, y es lo unico que sale sin ser un juicio', () => {
    // Hasta #175 esta prueba decia lo contrario —«`info` no sale de aqui»— porque el tono de «no
    // se» no existia: lo desconocido salia verde. Se deja la afirmacion dada la vuelta, con su
    // motivo, en vez de borrarla: es lo que cambio.
    expect(['En coactiva', 'Con deuda', 'Conforme'].map(tonoDe)).not.toContain('info');
    expect(tonoDe('cualquier cosa')).toBe('info');
  });
});
