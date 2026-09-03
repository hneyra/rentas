package kamayuk.rentas.verificaciones;

import kamayuk.comun.verificaciones.ProhibicionesEnElCodigoFuenteTestBase;

/**
 * Las prohibiciones de texto de ARQ-04 §2 sobre el codigo de `rentas`: {@code SET SESSION}, el
 * {@code DELETE} sobre tabla protegida, el {@code UPDATE} sobre una inmutable y el literal
 * numerico tributario.
 *
 * <p>Hoy no hay {@code src/main} que recorrer y la configuracion lo declara; las pruebas que
 * demuestran el escaner sobre sus muestras corren igual.
 */
class ProhibicionesEnElCodigoFuenteTest extends ProhibicionesEnElCodigoFuenteTestBase {}
