import {
  CIFRAS_DEL_ARTBOARD,
  LO_QUE_SOLO_TIENE_LA_INSTALACION,
  abrir,
  expect,
  test,
} from './arnes.ts';
import { CUENTAS } from './instalacion.ts';

/**
 * Un camino por seccion (AC4), y cada uno exige **algo que solo aparece con los datos de la
 * instalacion puestos**.
 *
 * <h2>El criterio, que es el mismo del AC9 de #8 y aqui aprieta mas</h2>
 *
 * Alli bastaba con distinguir el dato del catalogo: un titulo llega del arbol y estaria en
 * pantalla con la seccion vacia, asi que se exigia una marca que solo dibuja el dato. Aqui hay
 * un tercer origen posible —`src/datos/prototipo.ts`, las cifras del artboard— y es el que
 * importa: con el proxy de datos encendido las cuatro secciones se dibujan enteras y guapas sin
 * que exista ningun backend. Por eso cada camino afirma una cadena que **el artboard no tiene**
 * y que solo puede venir del volcado de la marcha blanca.
 *
 * <h2>Lo que estos caminos NO afirman</h2>
 *
 * Que las secciones esten completas. Hoy no lo estan, y no por su culpa: dos de las once
 * lecturas contestan 500 por desajustes de frontera con issue abierto —`/indicadores/recaudacion`
 * (#27) y `/seguridad/parametros/ejercicios/{e}` (#25)— y las cuatro determinaciones contestan
 * 422 porque `pedirCalculo` no manda cuerpo. Lo que si se afirma es que la seccion **dice lo
 * que pasa con las palabras del backend** en vez de quedarse en blanco, que es la diferencia
 * entre una pantalla rota y una pantalla honesta.
 */

test.use({ storageState: CUENTAS.escala.estado });

test.describe('Panel', () => {
  test('dibuja los cuatro frentes de «GET /indicadores/trabajo-parado»', async ({ page }) => {
    await abrir(page, '#panel');

    // Los cuatro frentes y sus frases salen del backend, uno por uno. El artboard tiene TRES
    // entradas en su cola de trabajo y se llaman de otra manera —«Contribuyentes sin emisión»,
    // «Predios que no generan deuda», «Beneficios por resolver»—, asi que estas cuatro no
    // pueden venir de ahi.
    for (const frente of ['TRANSITO', 'VALORES', 'COACTIVA', 'CATASTRO']) {
      await expect(page.getByText(frente, { exact: true }).first()).toBeVisible();
    }
    await expect(
      page.getByText('papeletas sin resolucion de multa emitida'),
    ).toBeVisible();
    await expect(
      page.getByText('predios con ficha y sin conciliar con rentas'),
    ).toBeVisible();
  });

  test('y donde el backend falla lo DICE, con sus palabras y su codigo', async ({ page }) => {
    await abrir(page, '#panel');

    // Caduca sola, y esta bien que caduque: el dia que #27 cierre, `/indicadores/recaudacion`
    // dejara de contestar 500 y este caso se pondra rojo pidiendo que se reescriba con lo que
    // la operacion publique. Un caso que no caducara seria uno que da igual lo que conteste.
    const aviso = page.getByText('No se pudo leer el indicador de recaudación');
    await expect(aviso).toBeVisible();
    await expect(page.getByText('No se pudo completar la operacion (500)')).toBeVisible();
  });
});

test.describe('Contribuyentes', () => {
  test('dibuja el padron de la municipalidad 9, con nombres que solo tiene la instalacion', async ({
    page,
  }) => {
    await abrir(page, '#contribuyentes');

    // El padron de Catacaos son 10 603 filas del volcado de la marcha blanca. Estos dos son los
    // dos primeros por codigo, y ni el nombre ni el codigo aparecen en ningun archivo de este
    // repositorio — comprobado con `grep` sobre `src/`.
    await expect(page.getByText('SULLON VILCHEZ-JOSE RAUL')).toBeVisible();
    await expect(page.getByText('00000000008')).toBeVisible();
    await expect(page.getByText('YPANAQUE SULLON-MANUEL DE LOS REYES')).toBeVisible();

    // Y la ficha del artboard NO esta: es la misma pantalla, con otro origen.
    await expect(page.getByText('Rufina Medina Medina')).toHaveCount(0);
  });
});

test.describe('Determinación', () => {
  test('la corrida masiva ensena las etapas que devolvio la instalacion', async ({ page }) => {
    await abrir(page, '#determinacion');

    await page.getByRole('button', { name: /Predial — masivo/ }).click();

    // La corrida 20 de esta instalacion tiene DOS etapas, «Padrón leído» y «Simulados». El
    // artboard dibuja CINCO y ninguna se llama asi —«Lectura del padrón», «Valuación de
    // predios», «Determinación del impuesto», «Determinación de arbitrios», «Generación de
    // cuponeras»—, asi que estas dos solo pueden venir de `GET /rentas/predial/corridas/ultima`.
    await expect(page.getByText('Padrón leído')).toBeVisible();
    await expect(page.getByText('Simulados')).toBeVisible();
    await expect(page.getByText('Lectura del padrón')).toHaveCount(0);
    await expect(page.getByText('62,418')).toHaveCount(0);
  });

  test('y una determinacion que el backend rechaza se cuenta con el mensaje del backend', async ({
    page,
  }) => {
    await abrir(page, '#determinacion');

    // `pedirCalculo` manda `POST` sin cuerpo —lo decidio F-6, porque el cuerpo es lo que dira
    // QUE se determina y eso todavia no lo decide esta pantalla—, y el backend contesta 422
    // con esa frase. Es el peldano `no-valido` de la escalera, dentro de una seccion.
    await expect(
      page.getByText('El cuerpo de la peticion no se puede leer: no es JSON valido').first(),
    ).toBeVisible();
    // Y las cifras del artboard, que es lo que se veria con el proxy encendido, no estan.
    await expect(page.getByText('170,616.75')).toHaveCount(0);
  });
});

test.describe('Valores', () => {
  test('dice que la sesion no tiene ejercicio fijado, que es lo que contesta la instalacion', async ({
    page,
  }) => {
    await abrir(page, '#valores');

    // `GET /seguridad/sesion` de esta instalacion contesta `ejercicioDeTrabajo: null`, asi que
    // la portada del conjunto sellado no tiene ejercicio por el que preguntar y lo dice en vez
    // de elegir uno. El artboard tiene un ejercicio puesto, de modo que con el proxy encendido
    // este texto NO aparece: es dato de la instalacion, no del catalogo.
    await expect(page.getByText('La sesión no tiene ejercicio de trabajo fijado')).toBeVisible();
    await expect(page.getByText('ejercicioDeTrabajo: null')).toBeVisible();
  });
});

test.describe('el proxy de datos esta apagado en las cuatro', () => {
  /**
   * La guarda que sostiene a las otras.
   *
   * Cada camino de arriba afirma una cadena de la instalacion, pero ninguno prueba por si solo
   * que el prototipo no este ademas — y una seccion que dibujara las dos cosas seria peor que
   * una rota, porque las cifras del artboard tienen aspecto de dato bueno. Esto recorre las
   * cuatro y exige que **ninguna** cifra del artboard aparezca en ninguna.
   */
  for (const [destino, marca] of Object.entries(LO_QUE_SOLO_TIENE_LA_INSTALACION)) {
    test(`«${destino}» trae lo de la instalacion y ni una cifra del artboard`, async ({ page }) => {
      await abrir(page, destino);

      // Primero se espera a que la seccion haya recibido lo suyo. Sin esto la foto de abajo se
      // toma sobre una pantalla vacia y pasa sin mirar nada — medido, no supuesto.
      await expect(page.getByText(marca).first()).toBeVisible();

      const texto = await page.locator('body').innerText();
      for (const cifra of CIFRAS_DEL_ARTBOARD) {
        expect(texto, `«${destino}» ensena «${cifra}», que es del artboard`).not.toContain(cifra);
      }
    });
  }
});
