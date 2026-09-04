package kamayuk.rentas.parametros.aplicacion;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import kamayuk.rentas.dominio.Vigencia;
import kamayuk.rentas.parametros.dominio.LlaveDeParametro;
import kamayuk.rentas.parametros.dominio.ParametroTributario;
import org.jspecify.annotations.Nullable;

/**
 * Una fila del manifiesto de cuadros normativos, ya analizada (D-13, ADR-0017).
 *
 * <p>Declara <b>una edicion</b> y de donde salen sus filas. Las once columnas son:
 *
 * <pre>
 * tipo,clave,vigencia_desde,vigencia_hasta,documento_fuente,
 * archivo_de_filas,sha256,archivo_del_corpus,transcribio,verifico,cuadro
 * </pre>
 *
 * <p>Igual que en {@link FilaPublicable}, las <b>tres columnas de la llave</b> —{@code tipo},
 * {@code clave}, {@code vigencia_desde}— van primero y en el orden que lee {@code
 * ImportarParametrosDelConjunto}, de modo que el mismo manifiesto sirve para publicar la edicion y
 * para componerla en un conjunto. Ahi las demas sobran y se ignoran.
 *
 * <p><b>Y {@code cuadro} va al final por eso mismo, no por gusto.</b> Estaba primero, y con el
 * primero el paso que compone leia {@code tipo = VALOR_REFERENCIAL}, {@code clave =
 * TABLA_VALORES_REFERENCIALES} y una fecha que decia «2026»: rechazaba la fila y sellaba el
 * conjunto <b>sin la edicion dentro</b>, que es sellar el nombre del cuadro sin su contenido. Los
 * consumidores leen por POSICION y no por cabecera —los tres de {@code parametros-2026.csv} y los
 * tres de este—, y por eso {@code valor_maquina} tambien esta al final alli (#192).
 *
 * <p>Las dos firmas son las del corpus y viajan a {@code usuario_carga} y {@code usuario_aprueba},
 * donde {@code parametro_doble_verificacion_ck} exige que sean distintas. Quien corre el proceso no
 * firma nada.
 */
record FilaDelManifiesto(
        String cuadro,
        ParametroTributario cabecera,
        String archivoDeFilas,
        String sha256,
        String archivoDelCorpus,
        String transcribio,
        String verifico) {

    static final int COLUMNAS = 11;

    /** La tabla de valores referenciales de vehiculos del MEF (R.M. anual EF/15). */
    static final String VEHICULAR = "VALOR_REFERENCIAL";

    /** Las cuatro tablas del Anexo I del Reglamento Nacional de Tasaciones (V57, H-15). */
    static final String DEPRECIACION = "DEPRECIACION";

    /**
     * Los cuadros que este proceso sabe publicar hoy, y por que falta el tercero.
     *
     * <p>{@code VALOR_UNITARIO} <b>no</b> esta, y no es que falte escribir el codigo. La R.M. anual
     * del MVCS publica <b>un cuadro por region</b> —Costa, Lima/Callao, Sierra y Selva— y {@code
     * valores-unitarios-2026.md} solo trae Costa. El archivo volvio a {@code TRANSCRITO} el
     * 2026-08-28, cuando el cotejo contra el Anexo I.2 real devolvio tres partidas donde se habian
     * transcrito siete, y esta {@code VERIFICADO} otra vez desde el 2026-08-29, con la segunda
     * firma de ADR-0007 puesta sobre la matriz sustituida. <b>Lo que falta ya no es una firma</b>:
     * no hay archivo de filas con su sha256, y esas tres partidas conviven con las <b>siete</b> que
     * declaran {@code valor_unitario_edificacion.partida} (V1), {@code
     * edificacion_estructura.partida} (V43) y las columnas {@code categoria_*} de {@code
     * construccion}, de modo que publicar tres dejaria cuatro sin ninguna fila —y una edicion
     * incompleta no se distingue de una completa hasta que alguien valoriza un predio (GOB-03,
     * H-14).
     *
     * <p>{@code DEPRECIACION} si esta desde V57, y hasta entonces estuvo fuera por lo mismo que
     * ahora deja de estarlo: {@code depreciacion} no tenia columna de uso, y el Anexo I publica
     * cuatro tablas —una por uso de la edificacion—, de modo que cargarlas habria dejado que la
     * unicidad se quedara con la primera y descartara tres en silencio.
     *
     * <p>Por eso el manifiesto que nombre un cuadro que no este aqui se rechaza <b>nombrando el
     * motivo</b>, en vez de publicar un cuadro incompleto que nadie distinguiria de uno completo.
     */
    static final List<String> CUADROS = List.of(DEPRECIACION, VEHICULAR);

    LlaveDeParametro llave() {
        return new LlaveDeParametro(
                cabecera.tipo(),
                cabecera.clave(),
                java.util.Objects.requireNonNull(
                        cabecera.vigencia().desde(),
                        "La fila valida siempre trae fecha de inicio de vigencia"));
    }

    /** El ejercicio del cuadro: el ano en que empieza su vigencia. */
    int ejercicio() {
        return java.util.Objects.requireNonNull(cabecera.vigencia().desde()).getYear();
    }

    String documentoFuente() {
        return cabecera.documentoFuente();
    }

    /**
     * Analiza una fila del manifiesto.
     *
     * @throws IllegalArgumentException con un mensaje que se muestra tal cual en el informe
     */
    static FilaDelManifiesto de(List<String> campos) {
        if (campos.size() < COLUMNAS) {
            throw new IllegalArgumentException(
                    "La fila del manifiesto trae "
                            + campos.size()
                            + " columna(s) y hacen falta "
                            + COLUMNAS
                            + ": tipo, clave, vigencia_desde, vigencia_hasta,"
                            + " documento_fuente, archivo_de_filas, sha256, archivo_del_corpus,"
                            + " transcribio, verifico, cuadro");
        }
        String cuadro = campos.get(10).strip();
        if (!CUADROS.contains(cuadro)) {
            throw new IllegalArgumentException(
                    "«"
                            + cuadro
                            + "» no es un cuadro que este proceso sepa publicar todavia. Los que"
                            + " si son "
                            + String.join(", ", CUADROS)
                            + ": ver FilaDelManifiesto.CUADROS para que le falta al que no esta");
        }
        String transcribio = campos.get(8).strip();
        String verifico = campos.get(9).strip();
        if (transcribio.isEmpty() || verifico.isEmpty()) {
            throw new IllegalArgumentException(
                    "Faltan las dos firmas del corpus: una edicion normativa la lee quien la"
                            + " transcribe y la vuelve a leer quien la verifica (ADR-0007)");
        }
        if (transcribio.equals(verifico)) {
            throw new IllegalArgumentException(
                    "Las dos firmas son la misma persona («"
                            + transcribio
                            + "»): releerse a uno mismo no es verificar (RNF-092). La base lo"
                            + " rechaza igual con parametro_doble_verificacion_ck");
        }
        String sha256 = campos.get(6).strip();
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "«" + sha256 + "» no es un sha256 en minusculas de 64 digitos hexadecimales");
        }
        String archivoDeFilas = obligatorio(campos.get(5), "archivo_de_filas");
        String archivoDelCorpus = obligatorio(campos.get(7), "archivo_del_corpus");
        String documentoFuente = obligatorio(campos.get(4), "documento_fuente");

        // La cabecera de una edicion no tiene valor numerico —lo tienen sus miles de filas— y
        // `parametro_valor_ck` (V1) exige uno de los dos. El valor de texto es la norma que
        // aprueba el cuadro, que es exactamente lo que la edicion ES: no se inventa un numero
        // para satisfacer una restriccion.
        ParametroTributario cabecera =
                new ParametroTributario(
                        null,
                        obligatorio(campos.get(0), "tipo"),
                        vacioEsNulo(campos.get(1)),
                        null,
                        documentoFuente,
                        new Vigencia(
                                fecha(campos.get(2), "vigencia_desde"),
                                fechaOpcional(campos.get(3))),
                        documentoFuente);

        return new FilaDelManifiesto(
                cuadro, cabecera, archivoDeFilas, sha256, archivoDelCorpus, transcribio, verifico);
    }

    private static String obligatorio(String celda, String columna) {
        String valor = celda.strip();
        if (valor.isEmpty()) {
            throw new IllegalArgumentException("La columna " + columna + " no puede ir vacia");
        }
        return valor;
    }

    private static @Nullable String vacioEsNulo(String celda) {
        String valor = celda.strip();
        return valor.isEmpty() ? null : valor;
    }

    private static LocalDate fecha(String celda, String columna) {
        String valor = obligatorio(celda, columna);
        try {
            return LocalDate.parse(valor);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "«" + valor + "» no es una fecha AAAA-MM-DD en " + columna, e);
        }
    }

    private static @Nullable LocalDate fechaOpcional(String celda) {
        String valor = celda.strip();
        if (valor.isEmpty()) {
            return null;
        }
        return fecha(valor, "vigencia_hasta");
    }
}
