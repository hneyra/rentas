package kamayuk.rentas.verificaciones;

import kamayuk.comun.verificaciones.ArquitecturaTestBase;

/**
 * Las reglas de ARQ-04 §2 aplicadas al codigo de {@code sgtm}.
 *
 * <p>El cuerpo esta en {@code comun-verificaciones} y lo que cambia —el paquete raiz, las listas de
 * tablas, los tipos ajenos que fiscalizacion lee— lo declara {@link ConfiguracionDelSgtm}, que
 * encuentra {@code ServiceLoader}.
 *
 * <p>Esta clase tiene que existir: sin ella la barrera no corre en este build. Que sea de dos
 * lineas es lo que se buscaba — lo que se comparte es la regla, no la decision de aplicarla.
 */
class ArquitecturaTest extends ArquitecturaTestBase {}
