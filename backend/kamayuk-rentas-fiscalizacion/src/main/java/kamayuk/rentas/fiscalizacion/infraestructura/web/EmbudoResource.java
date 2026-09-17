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
 * <h2>{@code conActa} no es «Con acta cerrada», y el nombre lo dice a proposito</h2>
 *
 * <p>El artboard rotula la tercera etapa «Con acta cerrada», y eso <b>no se puede contestar</b>:
 * {@code EstadoDeActa} declara cinco valores y este sistema solo escribe {@code ABIERTA} —medido:
 * no hay en {@code src/main} un solo camino que mueva el estado de un acta, ni al liquidar ni al
 * transferir—. Un {@code conActaCerrada} valdria cero siempre, en verde y sin sintoma, que es el
 * defecto que #194 midio, y que el estado no lo mueva nada es #214. Lo que este campo cuenta es
 * cuantas unidades del programa tienen acta <b>viva</b>: la etapa que {@code ActasController} llama
 * «Inspeccionados».
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
 * @param conActa cuantas tienen acta viva. <b>No es «con acta cerrada»</b>: ver arriba
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
