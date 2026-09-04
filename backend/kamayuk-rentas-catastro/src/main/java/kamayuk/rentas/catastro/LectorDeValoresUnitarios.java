package kamayuk.rentas.catastro;

import java.util.List;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * El cuadro de valores unitarios de edificacion que rige un ejercicio, publicado para otros
 * contextos acotados (ARQ-01 §4, #17, #48).
 *
 * <h2>Por que existe</h2>
 *
 * <p>El AC 2 de #48 dice que la valorizacion de obra del FUE «usa las tablas de #17 y <b>no</b>
 * duplica cifras». La unica forma de que no las duplique es que no las tenga: {@code licencias} no
 * guarda ningun valor por metro cuadrado, se lo pide a {@code catastro} por este puerto y lo
 * multiplica por el area declarada. Si manana la municipalidad corrige una celda y sella una
 * version nueva, la valorizacion cambia sin tocar una linea de {@code licencias}.
 *
 * <h2>El ejercicio entra como argumento, y se traduce a un conjunto sellado</h2>
 *
 * <p>Quien implementa esto resuelve el ejercicio a un {@code IdentificadorDeConjunto} con {@code
 * LectorDeParametros.conjuntoVigenteEn} y lee <b>ese conjunto</b>, nunca el ejercicio directamente
 * (ARQ-09 §3). Un ejercicio con dos versiones selladas —un cuadro corregido a mitad de anio— tiene
 * dos respuestas distintas, y solo el conjunto dice cual uso una emision concreta.
 *
 * <h2>Que pasa cuando no hay tabla</h2>
 *
 * <p><b>Falla</b>, con {@code LectorDeParametros.EjercicioSinSellar}. No devuelve vacio y no
 * devuelve ceros: las celdas del cuadro estan bloqueadas por D-02a (#197) y una valorizacion de
 * cero soles es indistinguible de una valorizacion correcta de una obra sin valor. Quien llama
 * decide que hacer con el fallo; {@code licencias} lo convierte en «no disponible» diciendo que
 * falta, y el papel imprime «—».
 */
public interface LectorDeValoresUnitarios {

    /**
     * Las celdas del cuadro que rige ese ejercicio, todas.
     *
     * <p>Se devuelven todas de una vez y no celda a celda a proposito: una valorizacion de tres
     * pisos por siete partidas son veintiuna consultas si se pregunta una por una, y las veintiuna
     * tienen que salir del <b>mismo</b> conjunto sellado —resolverlas por separado abriria la
     * puerta a que un sellado ocurrido entre dos de ellas dejara media valorizacion con una version
     * y media con otra—.
     *
     * @throws kamayuk.rentas.parametros.LectorDeParametros.EjercicioSinSellar si el ejercicio no
     *     tiene ningun conjunto sellado
     */
    List<ValorUnitarioPublicado> valoresUnitariosVigentesEn(Ejercicio ejercicio);
}
