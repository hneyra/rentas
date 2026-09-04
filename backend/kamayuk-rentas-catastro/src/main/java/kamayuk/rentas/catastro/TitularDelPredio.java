package kamayuk.rentas.catastro;

import java.util.Objects;
import kamayuk.rentas.dominio.Porcentaje;

/**
 * Una cuota de titularidad vigente sobre un predio, publicada para otros contextos acotados
 * (ADR-0015 §2.4, #366).
 *
 * <p>Es lo que {@link TitularesDelPredio} deja cruzar la frontera: el identificador del titular y
 * cuanto le corresponde, nada mas. No es {@code kamayuk.rentas.catastro.dominio.Titularidad} —ese
 * tipo vive en un subpaquete y Spring Modulith lo trata como interno—, y tampoco su copia: de la
 * titularidad no viajan ni sus fechas de vigencia, ni el documento que la sustenta, ni su
 * identificador de fila.
 *
 * <p><b>Que aqui haya un {@code contribuyenteId} no contradice a {@link FichaDelPadron}</b>, que
 * publica el nombre del titular y no su identificador. Son dos fronteras distintas y la decision de
 * ADR-0015 §2.4 las separa: el identificador puede cruzar hacia otro <b>contexto</b> —{@link
 * PredioDelPadron} ya lo hacia para la deteccion de omisos (#49)—, y lo que no puede es salir por
 * HTTP en un <b>listado</b>, que convertiria la consulta de fichas en un extractor de la
 * correlacion predio→persona de todo el padron. Quien resuelve este identificador a un codigo de
 * contribuyente lo hace de uno en uno, con permiso del padron y dejando rastro.
 *
 * <p><b>Ni un importe</b>, ni el porcentaje sumado: los porcentajes vigentes de un predio no tienen
 * que sumar 100 —un padron real tiene titularidad parcialmente identificada (DAT-01 §4.2)— y
 * publicar un total invitaria a leerlos como si la suma fuera un invariante.
 *
 * @param contribuyenteId el titular, para resolverlo contra el padron de {@code contribuyentes}
 * @param condicion en que calidad lo es ({@code PROPIETARIO_UNICO}, {@code COPROPIETARIO}, {@code
 *     CONYUGE}, {@code POSEEDOR}, {@code SUCESION}, {@code USUFRUCTUARIO}); va como texto porque
 *     {@code CondicionDeTitularidad} es interno de catastro
 * @param porcentaje cuanto le corresponde de este predio a la fecha consultada
 */
public record TitularDelPredio(long contribuyenteId, String condicion, Porcentaje porcentaje) {

    public TitularDelPredio {
        Objects.requireNonNull(condicion, "La cuota necesita la condicion del titular");
        Objects.requireNonNull(porcentaje, "La cuota necesita su porcentaje");
        if (contribuyenteId <= 0) {
            throw new IllegalArgumentException(
                    "Una cuota de titularidad sin titular no se publica: no hay a quien resolver");
        }
    }
}
