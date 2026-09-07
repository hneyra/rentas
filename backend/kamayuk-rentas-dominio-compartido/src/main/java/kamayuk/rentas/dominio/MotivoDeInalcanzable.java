package kamayuk.rentas.dominio;

/**
 * Por que no se pudo hablar con un sistema vecino (#25, AC-4).
 *
 * <p>Son <b>dos cosas distintas con dos remedios distintos</b>, y hasta #25 llegaban al registro
 * como el mismo {@code ...Inalcanzable} con la unica diferencia dentro de la frase en castellano.
 * Quien lee el registro a las tres de la manana tiene que poder separar «el despliegue esta mal
 * armado» de «el vecino se cayo» sin analizar un texto: lo primero se arregla poniendo una variable
 * de entorno y no se cura solo; lo segundo se arregla levantando al vecino y se cura solo.
 *
 * <p>Viaja como <b>dato y no dentro del mensaje</b>, por lo mismo que {@code
 * NoConstaEnCatastro.codigo()}: un texto en castellano se reescribe en cuanto alguien lo lee en voz
 * alta, y entonces el que decidia mirando la frase deja de decidir bien sin que nada se ponga rojo.
 *
 * <p>Es un enum del dominio compartido y no tres copias anidadas: las tres familias de excepcion
 * —{@code CajaInalcanzable}, {@code CatastroInalcanzable} y {@code NormativaInalcanzable}— viven en
 * modulos distintos, y tres enums de dos constantes son tres sitios donde la misma verdad puede
 * separarse.
 */
public enum MotivoDeInalcanzable {

    /**
     * La URL del vecino no esta configurada: nunca se llego a intentar la llamada.
     *
     * <p>No es una caida. Es un despliegue al que le falta una variable de entorno, y el sintoma
     * —una operacion en 500— no se parece en nada a la causa. Las tres se declaran
     * {@code @Value("${kamayuk.<sistema>.url:}")}, o sea <b>con cadena vacia por omision</b>, asi
     * que el proceso arranca, la sonda dice {@code UP} y el defecto no aparece hasta la primera
     * llamada.
     */
    SIN_CONFIGURAR,

    /**
     * La URL esta puesta y el vecino no contesto, o contesto algo que no se puede leer.
     *
     * <p>Esto si es una caida, y reintentar puede cambiar el resultado — que es exactamente lo que
     * distingue a {@code SERVICIO_NO_DISPONIBLE} de {@code ERROR_INTERNO}.
     */
    NO_CONTESTA
}
