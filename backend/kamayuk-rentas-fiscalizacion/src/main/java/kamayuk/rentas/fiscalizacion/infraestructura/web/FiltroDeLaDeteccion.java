package kamayuk.rentas.fiscalizacion.infraestructura.web;

import kamayuk.rentas.fiscalizacion.dominio.CondicionFiscalizada;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;

/**
 * Los dos filtros de «Omisos y subvaluadores», leídos <b>una sola vez</b> (#550, ADR-0023).
 *
 * <p>ADR-0023 decidió la salida (a): la muestra se sortea y no se manda, y lo que la detección
 * aporta al programa son sus dos filtros —sector y condición—, que ya son dos de los cuatro
 * parámetros del sorteo. Eso obliga a que los dos se lean <b>igual</b> en las dos pantallas, y
 * hasta este issue no se leían:
 *
 * <ul>
 *   <li><b>«Todos» del desplegable de sector.</b> {@code OmisosController} lo leía desde siempre
 *       como «sin filtro»; {@code ProgramasController} lo guardaba <b>literal</b> en {@code
 *       programa_fiscalizacion.sector_codigo}, y el sorteo filtra {@code s.codigo = 'Todos'}: un
 *       programa que no puede encontrar nunca ningún predio, cuyo único síntoma es una muestra de
 *       cero — indistinguible de «en ese sector no hay omisos». El mismo literal, del mismo
 *       desplegable, significando dos cosas dentro del mismo módulo.
 *   <li><b>El nombre de la condición.</b> La detección la leía con {@link
 *       CondicionFiscalizada#porNombre}, que normaliza el espacio, y el programa con {@code
 *       valueOf}, que no: «USO DISTINTO» se aceptaba en una pantalla y se rechazaba en la otra.
 * </ul>
 *
 * <p><b>Y las dos lecturas de la condición no son la misma, a propósito.</b> «Todas» en la
 * detección es «sin filtro» y trae el padrón entero; en el programa <b>no existe</b>, porque un
 * programa sin criterio no puede sortear ({@code ProgramaSinParametros}) y guardarlo así aplaza el
 * fallo hasta el sorteo. Por eso {@link #criterioDelPrograma} lo rechaza <b>diciendo qué es</b> y
 * no «criterio desconocido»: «Todas» no es una palabra que no se conozca, es la ausencia de
 * criterio.
 */
final class FiltroDeLaDeteccion {

    /**
     * Los dos literales con que el manual escribe «sin filtro» en sus desplegables: «Todos» para el
     * sector y «Todas» para la condición. Se admiten los dos en los dos sitios a propósito —un
     * sector cuyo código fuera literalmente «Todas» no existe, y aceptar sólo el de su propia
     * pantalla dejaría la misma diferencia que este issue cierra, un escalón más abajo.
     */
    private static final String[] SIN_FILTRO = {"TODOS", "TODAS"};

    private FiltroDeLaDeteccion() {}

    /** El sector sobre el que se acota, o {@code null} para todo el distrito. */
    static @Nullable String sectorOpcional(@Nullable String texto) {
        String valor = vacioAnulo(texto);
        return valor == null || esSinFiltro(valor) ? null : valor;
    }

    /**
     * La condición por la que se filtra la detección, o {@code null} para traerlas todas —también
     * las conformes, porque la pantalla ofrece «Todas»—.
     */
    static @Nullable CondicionFiscalizada condicionOpcional(@Nullable String texto) {
        String valor = vacioAnulo(texto);
        if (valor == null || esSinFiltro(valor)) {
            return null;
        }
        return condicionDe(valor);
    }

    /**
     * La condición con la que un programa sortea su muestra.
     *
     * <p>{@code null} sólo cuando no se declara ninguna: un programa así se registra —los
     * anteriores a {@code V60} están así en la base— y no puede sortear. Lo que <b>no</b> se admite
     * es «Todas»: es el literal con el que la detección dice «sin filtro», y aceptarlo guardaría un
     * programa que parece tener criterio y falla al sortear.
     */
    static @Nullable CondicionFiscalizada criterioDelPrograma(@Nullable String texto) {
        String valor = vacioAnulo(texto);
        if (valor == null) {
            return null;
        }
        if (esSinFiltro(valor)) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "'"
                            + texto
                            + "' no es un criterio de riesgo: es lo que la deteccion llama «sin"
                            + " filtro». Un programa sortea su muestra por UNA condicion, y hay que"
                            + " elegirla");
        }
        return condicionDe(valor);
    }

    // ------------------------------------------------------------------

    private static CondicionFiscalizada condicionDe(String valor) {
        try {
            return CondicionFiscalizada.porNombre(valor);
        } catch (IllegalArgumentException desconocida) {
            String mensaje = desconocida.getMessage();
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    mensaje == null ? "La condicion recibida no es valida" : mensaje);
        }
    }

    private static boolean esSinFiltro(String valor) {
        for (String literal : SIN_FILTRO) {
            if (literal.equalsIgnoreCase(valor)) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable String vacioAnulo(@Nullable String texto) {
        if (texto == null) {
            return null;
        }
        String limpio = texto.strip();
        return limpio.isEmpty() ? null : limpio;
    }
}
