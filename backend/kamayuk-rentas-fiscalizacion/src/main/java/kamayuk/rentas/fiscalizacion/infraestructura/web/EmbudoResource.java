package kamayuk.rentas.fiscalizacion.infraestructura.web;

import kamayuk.rentas.fiscalizacion.dominio.EmbudoDeFiscalizacion;
import org.jspecify.annotations.Nullable;

/**
 * El embudo de un programa tal como sale por HTTP ({@code fis-panel}, #196).
 *
 * <p>Las cuatro cifras juntas y cuadradas, porque un embudo se lee entero. Lo que la interfaz
 * <b>no</b> tiene que hacer es componerlas con el {@code totalElementos} de cuatro operaciones
 * distintas: serian cuatro peticiones para cuatro numeros que ninguna operacion afirma que
 * signifiquen eso, y el resultado se leeria igual que este sin poder cuadrarse.
 *
 * <h2>{@code conActa} es la etapa «Inspeccionados», y el nombre lo dice a proposito</h2>
 *
 * <p>Cuenta cuantas unidades del programa tienen acta <b>viva</b> —levantada y no anulada—, que es
 * la etapa que {@code ActasController} llama «Inspeccionados». El artboard rotulaba esa celda «Con
 * acta cerrada» y por eso la pantalla la dejaba vacia con su motivo: un acta <i>cerrada</i> no
 * existe aqui —{@code EstadoDeActa} declara {@code ABIERTA} y {@code ANULADA} desde #214, y la
 * unica transicion que este sistema escribe es anular—.
 *
 * <p><b>#241 midio que el equivocado era el rotulo</b>, con dos frases del propio artboard: la nota
 * de {@code fis-panel} dice «lo detectado, lo <b>inspeccionado</b> y lo que sostiene una
 * determinacion» —tres cosas para cuatro cifras—, y la de {@code fis-actas} situaba el cierre
 * <b>antes</b> de liquidar, que es lo contrario de lo que #214 llamo «cerrada». El rotulo es «Con
 * acta levantada» y este campo lo llena.
 *
 * <p><b>No hay una quinta cifra, y no es por falta de dato</b> (#231). «Con liquidacion» se puede
 * contar —{@code LiquidarFiscalizacion} la escribe y {@code CambiarEstadoDeLaLiquidacion} la
 * anula—, pero es la etapa que va <b>despues</b> de «Inspeccionados» en el embudo del manual, y
 * {@code fis-panel} no tiene celda para ella: la suya es «Con diferencia». Publicarla aqui dejaria
 * un campo que ninguna pantalla dibuja, que es lo que #431, #432 y #544 tuvieron que retirar.
 *
 * @param programaId el programa
 * @param codigo su «N.º de programa», que es lo que la pantalla teclea
 * @param ejercicio el ejercicio que examina; nulo en un programa anterior a {@code V60}
 * @param aLaFecha el dia al que esta el cruce (regla 9). Las tres ultimas etapas estan congeladas
 *     por lo que se sorteo y se visito; la primera se resuelve contra el padron de hoy
 * @param detectadosPorCruce cuantos predios senala el cruce con los parametros del programa; nulo
 *     si el programa no los declara
 * @param parametroQueFalta cual de esos parametros le falta, cuando no hay cifra que dar
 * @param programados cuantas unidades sorteo la muestra
 * @param conActa cuantas tienen acta viva: «Inspeccionados», que la pantalla rotula «Con acta
 *     levantada» desde #241
 * @param conDiferencia cuantas sostienen una determinacion, en la ultima version de su liquidacion
 */
public record EmbudoResource(
        long programaId,
        String codigo,
        @Nullable Integer ejercicio,
        String aLaFecha,
        @Nullable Integer detectadosPorCruce,
        @Nullable String parametroQueFalta,
        int programados,
        int conActa,
        int conDiferencia) {

    public static EmbudoResource de(EmbudoDeFiscalizacion embudo) {
        return new EmbudoResource(
                embudo.programaId(),
                embudo.codigo(),
                embudo.ejercicio() == null ? null : embudo.ejercicio().valor(),
                embudo.aLaFecha().toString(),
                embudo.detectadosPorCruce(),
                embudo.parametroQueFalta(),
                embudo.programados(),
                embudo.conActa(),
                embudo.conDiferencia());
    }
}
