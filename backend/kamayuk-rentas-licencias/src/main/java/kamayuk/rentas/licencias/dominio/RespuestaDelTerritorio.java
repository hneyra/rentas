package kamayuk.rentas.licencias.dominio;

/**
 * Los cuatro desenlaces de preguntarle un hecho del territorio a {@code catastro} (#43, AC-5).
 *
 * <h2>Son cuatro y no dos, y colapsarlos borra la distincion que el proveedor construyo</h2>
 *
 * <p>{@code catastro} separa a proposito «ese predio no esta en este padron», «esta y no tiene
 * poligono» y «no se pudo preguntar», y les da codigos estables para que este lado pueda
 * distinguirlas (#9). Se arreglan de maneras distintas —dar de alta el predio, cargar el plano,
 * levantar el despliegue— y quien opera necesita saber cual le toca.
 *
 * <p><b>Y ninguna de las tres que no son {@link #RESPONDIO} significa «no hay riesgo».</b> Esa es
 * la lectura que AC-4 prohibe con todas las letras: leer una ausencia como una respuesta favorable
 * es lo que autoriza un local en una zona de la que no se sabe nada.
 */
public enum RespuestaDelTerritorio {

    /** {@code catastro} contesto el hecho. Es la unica que permite concluir algo. */
    RESPONDIO,

    /**
     * El predio no consta: no esta en el padron de esta municipalidad, no tiene poligono levantado,
     * o ningun plan vigente lo cubre. Hoy es el caso NORMAL, porque no hay ni un poligono cargado
     * en ninguna instalacion.
     */
    NO_CONSTA,

    /** No se pudo preguntar: {@code catastro} no contesto. No es «no hay». */
    NO_SE_PUDO_PREGUNTAR,

    /**
     * No se pregunto, porque la solicitud no trae predio.
     *
     * <p>Hay giros sin predio empadronado —lo dice {@code LicenciaDeFuncionamiento#predioId}—, y
     * para esos no existe territorio que consultar. Se distingue de las otras tres porque no es un
     * fallo de nadie y no se arregla con nada.
     */
    NO_SE_PREGUNTO
}
