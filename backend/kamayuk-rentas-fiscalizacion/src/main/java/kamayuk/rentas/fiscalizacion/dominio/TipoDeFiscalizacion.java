package kamayuk.rentas.fiscalizacion.dominio;

import java.util.Locale;

/**
 * Cómo se determinó lo hallado. Es el desplegable «Tipo de fiscalización» de la pantalla {@code
 * fisc_historico}: el prototipo manda.
 *
 * <p>No decide ninguna cifra: decide qué se puede sustentar. Una fiscalización {@code PRESUNTA} se
 * apoya en indicios y no en una medición de campo, y por eso la liquidación que sale de ella se
 * discute de otra manera. Aquí solo se guarda cuál fue.
 */
public enum TipoDeFiscalizacion {

    /** Sobre base cierta: lo que el fiscalizador midió y vio en el predio. */
    CIERTA,

    /** Sobre base presunta: indicios, cuando el contribuyente no permitió verificar. */
    PRESUNTA,

    /** De oficio, sin visita: el cruce de padrones detectó la diferencia. */
    DE_OFICIO,

    /** De gabinete: revisión documental del expediente del contribuyente. */
    GABINETE;

    /** Por nombre, admitiendo el espacio de la pantalla («DE OFICIO») además del guion bajo. */
    public static TipoDeFiscalizacion porNombre(String texto) {
        String normalizado = texto.strip().toUpperCase(Locale.ROOT).replace(' ', '_');
        for (TipoDeFiscalizacion tipo : values()) {
            if (tipo.name().equals(normalizado)) {
                return tipo;
            }
        }
        throw new IllegalArgumentException("Tipo de fiscalizacion desconocido: '" + texto + "'");
    }
}
