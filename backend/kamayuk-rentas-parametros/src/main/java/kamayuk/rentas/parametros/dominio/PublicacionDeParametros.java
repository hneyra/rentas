package kamayuk.rentas.parametros.dominio;

import java.util.List;

/**
 * Puerto de <b>publicacion</b> de valores normativos: el unico camino por el que una cifra entra en
 * {@code parametro_tributario} (#188, #247 §4).
 *
 * <h2>Por que es un puerto aparte de {@link ParametrosRepository}</h2>
 *
 * <p>Porque no lo usa el mismo actor. {@code ParametrosRepository} es lo que la aplicacion tiene, y
 * su javadoc dice —y sigue diciendo— que no hay ningun metodo que cree un {@link
 * ParametroTributario}: la aplicacion solo tiene {@code SELECT} sobre esa tabla (V7). Publicar es
 * trabajo de {@code rol_carga_parametros}, que <b>solo</b> puede tocar esa tabla y ninguna otra: ni
 * {@code conjunto_parametro_detalle}, ni {@code auditoria}. Es la separacion de funciones SoD-1 de
 * REQ-03, y esta escrita en los privilegios, no en una convencion.
 *
 * <p>Meter un metodo de publicar en el repositorio de la aplicacion habria puesto la escritura al
 * alcance del proceso web, donde ninguna credencial puede ejecutarla: fallaria en produccion con un
 * error de privilegio en vez de no existir. Su implementacion vive en el perfil {@code batch} y
 * fuera de el <b>no hay bean</b>.
 *
 * <h2>Que no hace</h2>
 *
 * <p>No compone ningun conjunto y no sella nada. Publicar y componer son dos actos de dos roles
 * distintos: el segundo es {@code AbrirConjuntoDeParametros}, que corre como {@code sgtm_app}.
 */
public interface PublicacionDeParametros {

    /**
     * Escribe el valor normativo y devuelve el identificador que le toco.
     *
     * @param parametro el valor con su vigencia y su documento fuente
     * @param transcribio quien lo transcribio de la norma, tal como lo dice el corpus
     * @param verifico quien lo re-verifico contra la norma, tal como lo dice el corpus. La base
     *     exige que sea distinto de {@code transcribio} ({@code parametro_doble_verificacion_ck},
     *     RNF-092): la doble firma de ADR-0007 no es una convencion de este proceso
     */
    long publicar(ParametroTributario parametro, String transcribio, String verifico);

    /**
     * Los valores ya publicados que responden a esa llave.
     *
     * <p>Lo necesita la publicacion para no duplicar: {@code parametro_tributario} no tiene ninguna
     * restriccion de unicidad sobre {@code (tipo, clave, vigencia_desde)} —V1 no la puso, y
     * quitarsela hoy retiraria la guarda de homonimos que {@code
     * AdministrarParametros.agregarParametroPublicado} verifica—, asi que una segunda corrida del
     * mismo archivo insertaria todas sus filas otra vez y dejaria el conjunto <b>sin poder
     * componerse</b>: cada llave tendria dos candidatos y ninguno elegible.
     */
    List<ParametroTributario> publicados(LlaveDeParametro llave);
}
