package kamayuk.rentas.catastro;

import java.time.LocalDate;

/**
 * A que zona urbanistica cae un predio, segun el plan vigente a una fecha (`catastro`#4).
 *
 * <h2>Publica la ZONA, y ninguna consecuencia</h2>
 *
 * <p>Quien es compatible con que giro <b>no</b> sale de aqui: eso es {@code
 * ciiu.zonificacion_compatible}, que es una tabla de este sistema, y la licencia la emite {@code
 * rentas}. Es la misma frontera de ADR-0024 que le impide a {@code catastro} calcular un tributo, y
 * el dia que este puerto devolviera un «procede: si/no» lo que habria que revisar es la frontera y
 * no el puerto.
 *
 * <p><b>Ni un importe.</b> Un parametro urbanistico es una altura, un porcentaje de area libre o un
 * lote minimo; lo que se cobre por construir sale del cuadro de valores unitarios y de la
 * ordenanza, y no de aqui.
 *
 * <h2>No devuelve vacio, y ese es el punto</h2>
 *
 * <p>{@code catastro} contesta tres cosas distintas y las distingue a proposito: el predio no esta
 * en el padron, el predio esta y <b>no tiene poligono</b> —hoy no hay ni uno cargado en ninguna
 * instalacion—, y el predio tiene poligono y ningun plan vigente lo cubre. Las tres se arreglan de
 * maneras distintas: dar de alta el predio, cargar el plano, aprobar la zonificacion.
 *
 * <p>Una zona nula seria <b>indistinguible</b> de «este predio esta en zona nula», y una zona nula
 * no admite ningun giro: un dato que falta acabaria negando una licencia que la ordenanza permite.
 * Por eso las tres llegan a este lado como {@link
 * kamayuk.rentas.catastro.infraestructura.ClienteHttpDeCatastro.NoConstaEnCatastro}, que trae el
 * codigo estable con el que {@code catastro} las separa.
 */
public interface ZonificacionDelPredio {

    /**
     * La zona vigente de un predio a una fecha.
     *
     * <p>La fecha entra como argumento y vuelve dentro de la respuesta (regla 9): no existe «la
     * zona», existe la zona vigente a un dia, y un plan se sustituye por otro.
     *
     * @throws kamayuk.rentas.catastro.infraestructura.ClienteHttpDeCatastro.NoConstaEnCatastro si
     *     el predio no esta, no tiene poligono, o ningun plan vigente a esa fecha lo cubre
     */
    ZonaDelPredio zonaDe(long predioId, LocalDate aLaFecha);
}
