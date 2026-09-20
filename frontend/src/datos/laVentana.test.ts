import { describe, expect, it } from 'vitest';

import { laVentanaQueSePide, loQueDijoElServidor } from './laVentana.ts';
import { bloquesDe } from '../pantallas/bloques.ts';
import { pantallaDe } from '../pantallas/definiciones/index.ts';

/**
 * **Lo que se pide de la ruta, que es una funcion pura y por eso se prueba sin montar nada** (#186).
 *
 * La ruta la escribe cualquiera —es la barra de direcciones—, asi que lo que llega no es lo que el
 * interprete escribio: es lo que alguien tecleo. Estas pruebas son las dos direcciones de eso:
 * que lo bueno viaja, y que lo que no es bueno **no se reenvia**.
 */
describe('la ventana sale de la definicion y de la ruta, en ese orden', () => {
  it('sin nada en la ruta, el tamano es el que DECLARA la tabla y nada mas viaja', () => {
    // Es lo que cierra el AC3 de #186: el tamano vivia escrito en `RUTAS` y ahora vive en la
    // definicion, que es donde el interprete lo lee para dibujar los mandos. En dos sitios diverge.
    expect(laVentanaQueSePide('aut-cat', 'giros-ciiu', {})).toEqual({ tamano: '20' });
    expect(bloquesDe(pantallaDe('aut-cat'))[0]?.tabla?.paginacion?.tamano).toBe(20);
  });

  it('la pagina 0 no viaja: es la del backend por omision, y la direccion se lee mejor corta', () => {
    expect(laVentanaQueSePide('aut-cat', 'giros-ciiu', { pagina: '0' })).toEqual({ tamano: '20' });
    expect(laVentanaQueSePide('aut-cat', 'giros-ciiu', { pagina: '3' })).toEqual({
      tamano: '20',
      pagina: '3',
    });
  });

  it('una pagina que no es un entero no negativo NO se reenvia: la escribio alguien', () => {
    for (const tecleada of ['-1', '2.5', 'ultima', '', ' ']) {
      expect(laVentanaQueSePide('aut-cat', 'giros-ciiu', { pagina: tecleada }), tecleada).toEqual({
        tamano: '20',
      });
    }
  });

  it('el tamano de la ruta vale si es UNO DE LOS OFRECIDOS, y si no el de la definicion', () => {
    // Cualquier entero no: el backend topa en 500 (`Paginacion.TAMANO_MAXIMO`) y un `?tamano=600`
    // tecleado seria un 422 dibujado como una averia de la pantalla. Ademas el desplegable de la
    // tabla solo puede ensenar uno de los suyos.
    expect(laVentanaQueSePide('aut-cat', 'giros-ciiu', { tamano: '50' })).toEqual({ tamano: '50' });
    expect(laVentanaQueSePide('aut-cat', 'giros-ciiu', { tamano: '600' })).toEqual({
      tamano: '20',
    });
    expect(laVentanaQueSePide('aut-cat', 'giros-ciiu', { tamano: '7' })).toEqual({ tamano: '20' });
  });

  it('un `ordenarPor` de la lista blanca viaja; uno que la tabla no ofrece, NO', () => {
    // La lista blanca del backend rechaza con 422 ORDEN_NO_ADMITIDO. Reenviar lo que traiga la
    // ruta convertiria `#/aut-cat?ordenarPor=loQueSea` en una pantalla rota.
    expect(laVentanaQueSePide('aut-cat', 'giros-ciiu', { ordenarPor: 'descripcion' })).toEqual({
      tamano: '20',
      ordenarPor: 'descripcion',
    });
    expect(laVentanaQueSePide('aut-cat', 'giros-ciiu', { ordenarPor: 'loQueSea' })).toEqual({
      tamano: '20',
    });
  });

  it('el sentido solo acompana a un campo admitido, y solo si es uno de los dos declarados', () => {
    // Sin campo, el backend ordena por lo suyo y un sentido suelto no dice nada de esa columna.
    expect(laVentanaQueSePide('seg-aud', 'movimientos', { sentido: 'DESCENDENTE' })).toEqual({
      tamano: '20',
    });
    expect(
      laVentanaQueSePide('seg-aud', 'movimientos', {
        ordenarPor: 'usuarioId',
        sentido: 'DESCENDENTE',
      }),
    ).toEqual({ tamano: '20', ordenarPor: 'usuarioId', sentido: 'DESCENDENTE' });
    // Y un sentido inventado tampoco: los dos valores son del backend —`ASCENDENTE`/`DESCENDENTE`—
    // y `desc` es un 422.
    expect(
      laVentanaQueSePide('seg-aud', 'movimientos', { ordenarPor: 'usuarioId', sentido: 'desc' }),
    ).toEqual({ tamano: '20', ordenarPor: 'usuarioId' });
    // Y el nombre VIEJO ya no es el sitio del sentido: una ruta guardada de antes de #236 no
    // mueve nada. Es el precio de renombrar, y es el que se eligio: admitir los dos nombres
    // dejaria `direccion` tomado para siempre, que es justo lo que #236 vino a soltar.
    expect(
      laVentanaQueSePide('seg-aud', 'movimientos', {
        ordenarPor: 'usuarioId',
        direccion: 'DESCENDENTE',
      }),
    ).toEqual({ tamano: '20', ordenarPor: 'usuarioId' });
  });

  it('`aut-tram` ordena de verdad, que hasta #226 no podia: el sentido tenia el nombre tomado', () => {
    // `GET /licencias/funcionamiento` declaraba en su firma un filtro llamado `direccion` —el
    // domicilio del establecimiento— y recibia ademas `ParametrosDePaginacion`, cuyo sentido del
    // orden se llamaba TAMBIEN `direccion`. Spring ataba el mismo parametro de consulta a los dos:
    // `?ordenarPor=numero&direccion=DESCENDENTE` acotaba el padron a las licencias cuya direccion
    // contiene «DESCENDENTE» —o sea, a ninguna— y ademas ordenaba al reves. Por eso esta fue la
    // unica de las cuatro tablas de #186 que pagino sin `orden`.
    //
    // #226 renombro el filtro a `direccionDelEstablecimiento` y el sentido recupero su sitio;
    // #236 aparto ademas el sentido a `sentido`, de modo que el choque no puede volver con la
    // siguiente pantalla que dibuje un domicilio.
    expect(
      laVentanaQueSePide('aut-tram', 'padron-de-licencias', {
        ordenarPor: 'nombreComercial',
        sentido: 'DESCENDENTE',
      }),
    ).toEqual({ tamano: '20', ordenarPor: 'nombreComercial', sentido: 'DESCENDENTE' });

    // Y el PRIMERO que ofrece es el orden por omision del backend —`numero`—, que es lo que hace
    // que la barra no anuncie un orden distinto del que traen las filas. Lo comprueba contra el
    // contrato `la-ruta-de-la-hoja-llega-al-conector.test.ts`; aqui se deja escrito cual es.
    expect(bloquesDe(pantallaDe('aut-tram'))[0]?.tabla?.orden?.campos[0]?.valor).toBe('numero');
  });

  it('pedir la ventana de una tabla que no declara paginacion REVIENTA nombrandola', () => {
    // Un `{}` silencioso dejaria al conector pidiendo sin tamano mientras la tabla no dibuja ni un
    // mando: veinte filas de un padron entero, sin decir que son una ventana.
    expect(() => laVentanaQueSePide('coa-exp', 'actos-del-expediente', {})).toThrow(
      /no declara «paginacion/,
    );
    expect(() => laVentanaQueSePide('aut-cat', 'la-que-no-existe', {})).toThrow(
      /no tiene ninguna tabla con la clave/,
    );
  });
});

describe('lo que dijo el servidor viaja con el nombre que la tabla busca', () => {
  it('los dos nombres salen de la clave de la tabla, y no de una lista aparte', () => {
    const dicho = loQueDijoElServidor('giros-ciiu', {
      contenido: [],
      pagina: 0,
      tamano: 20,
      totalElementos: 1842,
      totalPaginas: 93,
      hayMas: true,
    });

    expect([...dicho.keys()]).toEqual(['giros-ciiu.hayMas', 'giros-ciiu.paginas']);
    // `paginas` viaja como TEXTO: `DatoConNombre` no admite `number` porque una cifra llega ya
    // formateada por el sistema. Esta no lleva formato: es un indice.
    expect(dicho.get('giros-ciiu.paginas')).toBe('93');
  });
});
