package kamayuk.rentas.catastro;

/**
 * Lo que {@code catastro} dice de un predio a una fecha: si esta en el padron, y si tiene ficha
 * vigente (#397).
 *
 * <p>Son TRES estados, y hasta #397 se colapsaban en dos: {@code LectorDeFichas#fichaVigenteEn}
 * devolvia vacio tanto para «ese predio no esta en el padron» como para «esta y no tiene ficha», y
 * la DJ se registraba igual sobre un predio que no existe. La respuesta de {@code catastro} los
 * distingue con {@code enElPadron}; este tipo los conserva.
 */
public sealed interface InscripcionDelPredio {

    /** El predio no esta en el padron de esta municipalidad a esa fecha. */
    record FueraDelPadron() implements InscripcionDelPredio {}

    /** Esta en el padron, sin ficha vigente a esa fecha. */
    record SinFicha() implements InscripcionDelPredio {}

    /** Esta en el padron, con esa version de ficha vigente. */
    record ConFicha(long fichaId) implements InscripcionDelPredio {}
}
