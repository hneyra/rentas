package kamayuk.rentas.parametros.aplicacion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.dominio.Vigencia;
import kamayuk.rentas.parametros.dominio.LlaveDeParametro;
import kamayuk.rentas.parametros.dominio.ParametroTributario;
import org.jspecify.annotations.Nullable;

/**
 * Una fila del derivado publicable del corpus, ya analizada (#188, #247 §4).
 *
 * <p>Las <b>tres primeras columnas son, en el mismo orden, las que lee {@code
 * ImportarParametrosDelConjunto}</b>: {@code tipo,clave,vigenciaDesde}. No es una casualidad
 * comoda, es el punto: asi el mismo archivo publica el valor y compone el conjunto, y no existen
 * dos listas que puedan separarse el dia que alguien anada una fila a una y se olvide de la otra.
 * Las columnas de la cuarta en adelante las ignora el importador.
 *
 * <p>Las columnas {@code transcribio} y {@code verifico} son la <b>doble firma de ADR-0007, ya
 * ocurrida en el corpus</b>: se copian de la cabecera del archivo de {@code
 * docs/10-negocio/valores-normativos/} y viajan a {@code usuario_carga} y {@code usuario_aprueba}.
 * Quien corre el proceso no firma; transporta lo que se firmo al leer la norma. Que sean distintas
 * lo exige la base ({@code parametro_doble_verificacion_ck}, RNF-092) y lo comprueba antes, en cada
 * PR, {@code docs/10-negocio/verificar-publicacion.mjs}.
 *
 * <h2>La undecima columna: {@code valor_maquina} (#192)</h2>
 *
 * <p>Hay valores cuya forma en la norma <b>no es</b> la forma que el codigo consume. El art. 43 del
 * TUO del Codigo Tributario dice «cuatro (4) anios» y {@link
 * kamayuk.rentas.dominio.Plazo#de(String)} solo acepta «4 ANIOS», porque rechaza a proposito toda
 * lectura tolerante —un plazo interpretado «lo mejor posible» es plausible y equivocado, que es el
 * modo de falla que nadie detecta—. Las dos exigencias no caben en una columna: {@code valor_texto}
 * es la transcripcion verbatim, que {@code verificar-publicacion.mjs} compara letra por letra
 * contra el corpus, y {@code valor_maquina} es lo que se publica en su lugar cuando la fila la
 * trae.
 *
 * <p><b>Es opcional, y aqui no se exige.</b> Una fila sin ella publica su texto verbatim, como
 * siempre. Quien exige la forma de maquina a las filas de tipo {@code PLAZO} es {@code
 * verificar-publicacion.mjs}, en cada PR y antes de montar el archivo en el Job —igual que la doble
 * firma la sostiene la restriccion de la base y no el {@code if} de aqui abajo—. Exigirla tambien
 * aqui obligaria a este proceso, que publica cualquier tipo de parametro, a conocer la forma que
 * espera un consumidor concreto; y lo que pasa si nadie la exige esta probado: {@code
 * PublicarParametrosTest} publica el verbatim tal cual y demuestra que {@code Plazo.de} lo rechaza.
 *
 * <p><b>La comprobacion de que las dos firmas son distintas que hay aqui abajo no es la que
 * muerde</b>, y conviene decirlo: quitandola, las once pruebas que {@code PublicarParametrosTest}
 * tenia entonces siguen en verde —se probo—, porque la fila llega a la base y {@code
 * parametro_doble_verificacion_ck} la rechaza con {@code 23514}, y el proceso la informa igual. Lo
 * unico que aporta es que el informe diga cual fila y por que sin gastar un viaje a PostgreSQL. La
 * regla la sostiene la restriccion, como debe ser.
 */
record FilaPublicable(ParametroTributario parametro, String transcribio, String verifico) {

    static final int COLUMNAS = 10;

    /** La undecima, opcional: la forma que el codigo consume cuando no es la de la norma (#192). */
    static final int VALOR_MAQUINA = 10;

    LlaveDeParametro llave() {
        return new LlaveDeParametro(
                parametro.tipo(),
                parametro.clave(),
                java.util.Objects.requireNonNull(
                        parametro.vigencia().desde(),
                        "La fila valida siempre trae fecha de inicio de vigencia"));
    }

    /**
     * Analiza una fila del CSV.
     *
     * @throws IllegalArgumentException con un mensaje que se muestra tal cual en el informe
     */
    static FilaPublicable de(List<String> campos) {
        if (campos.size() < COLUMNAS) {
            throw new IllegalArgumentException(
                    "La fila trae "
                            + campos.size()
                            + " columna(s) y hacen falta "
                            + COLUMNAS
                            + ": tipo, clave, vigenciaDesde, vigenciaHasta, valorNumerico,"
                            + " valorTexto, documentoFuente, archivoDelCorpus, transcribio,"
                            + " verifico; y, opcional, valorMaquina");
        }
        String tipo = campos.get(0);
        if (tipo.isBlank()) {
            throw new IllegalArgumentException("La fila necesita el tipo del parametro");
        }
        LocalDate desde = fecha(campos.get(2), "de inicio de vigencia");
        LocalDate hasta =
                campos.get(3).isBlank() ? null : fecha(campos.get(3), "de fin de vigencia");
        String transcribio = campos.get(8).strip();
        String verifico = campos.get(9).strip();
        if (transcribio.isBlank() || verifico.isBlank()) {
            throw new IllegalArgumentException(
                    "La fila no trae las dos firmas del corpus, y sin ellas no se publica: una"
                            + " cifra normativa la leen dos personas (ADR-0007)");
        }
        if (transcribio.equals(verifico)) {
            throw new IllegalArgumentException(
                    "Quien transcribio y quien verifico son la misma firma («"
                            + transcribio
                            + "»): releerse a uno mismo no es verificar (RNF-092)");
        }

        return new FilaPublicable(
                new ParametroTributario(
                        null,
                        tipo,
                        vacioComoNulo(campos.get(1)),
                        numero(campos.get(4)),
                        vacioComoNulo(loQuePublicaComoTexto(campos)),
                        new Vigencia(desde, hasta),
                        campos.get(6)),
                transcribio,
                verifico);
    }

    /**
     * Lo que va a {@code parametro_tributario.valor_texto}: la forma de maquina si la fila la trae,
     * y si no el texto verbatim (#192).
     *
     * <p>El verbatim no se pierde —vive en el archivo del corpus, que es la fuente, y en el propio
     * CSV, que es donde se compara contra el— pero no es lo que se publica: lo que se publica es lo
     * que el codigo tiene que poder leer sin interpretarlo.
     */
    private static String loQuePublicaComoTexto(List<String> campos) {
        String maquina = campos.size() > VALOR_MAQUINA ? campos.get(VALOR_MAQUINA) : "";
        return maquina.isBlank() ? campos.get(5) : maquina;
    }

    private static @Nullable String vacioComoNulo(String campo) {
        return campo.isBlank() ? null : campo.strip();
    }

    private static @Nullable ValorNormativo numero(String campo) {
        if (campo.isBlank()) {
            return null;
        }
        try {
            // Desde texto y nunca desde double: la precision de un valor normativo es el punto
            // (regla 1). Y la cifra viene del CSV, no del codigo (regla 5).
            return new ValorNormativo(new BigDecimal(campo.strip()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "El valor numerico no es un decimal: '" + campo + "'");
        }
    }

    private static LocalDate fecha(String campo, String cual) {
        try {
            return LocalDate.parse(campo.strip());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "La fecha " + cual + " no es una fecha aaaa-mm-dd: '" + campo + "'");
        }
    }
}
