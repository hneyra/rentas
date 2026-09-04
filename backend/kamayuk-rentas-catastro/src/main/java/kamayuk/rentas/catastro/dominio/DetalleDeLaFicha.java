package kamayuk.rentas.catastro.dominio;

/**
 * Lo que cuelga de una ficha y depende de <b>que tipo</b> de ficha es.
 *
 * <p>Las construcciones y las obras complementarias no estan aqui: las tienen los cuatro tipos —una
 * edificacion en propiedad exclusiva y comun tiene construcciones, y un predio rustico puede
 * tenerlas—. Aqui esta solo lo que un tipo tiene y otro no: la actividad economica, los bienes
 * comunes con su reparto, los grupos de tierra con sus colindantes.
 *
 * <p><b>Sellada, y con {@link #tipo()}</b>, para que la ficha pueda rechazar en su constructor una
 * ficha {@code ECONOMICA} con detalle rural. Sin eso, la combinacion equivocada se escribe sin
 * ruido y se descubre al leerla, cuando ya nadie recuerda quien la escribio.
 *
 * <p>La ficha {@code UNICA} no tiene detalle: lo suyo son las construcciones. Por eso el campo de
 * la ficha es {@code @Nullable} y no hay aqui una implementacion vacia que existiria solo para
 * evitar un nulo.
 */
public sealed interface DetalleDeLaFicha
        permits DetalleEconomico, DetalleDeBienesComunes, DetalleRural {

    /** De que tipo de ficha es este detalle. La ficha comprueba que coincida con el suyo. */
    TipoFicha tipo();
}
