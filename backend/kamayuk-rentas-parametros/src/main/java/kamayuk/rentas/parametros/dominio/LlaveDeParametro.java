package kamayuk.rentas.parametros.dominio;

import java.time.LocalDate;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Como se nombra un parametro <b>ya publicado</b> para incorporarlo a un conjunto: por lo que es
 * —tipo, clave y desde cuando rige— y no por el identificador que le toco en la base.
 *
 * <p><b>Por que no el identificador.</b> Componer el conjunto de un ejercicio es el acto del que
 * cuelga la reproducibilidad de todo lo que se emita con el (ADR-0007), y se ejecuta al menos dos
 * veces —una en {@code stg} y otra en {@code prod}—. El mismo valor normativo tiene identificadores
 * distintos en cada ambiente, asi que un archivo de operacion escrito con numeros entra en el
 * ambiente equivocado <b>sin fallar</b>: sella un juego de parametros que no es el que dice. Con
 * tipo y clave, el mismo archivo vale en los dos, se lee al lado de {@code
 * docs/10-negocio/valores-normativos/} y lo que no exista se rechaza nombrandolo.
 *
 * <p>La fecha de inicio de vigencia forma parte de la llave porque un tipo se republica: la UIT de
 * 2026 y la de 2027 comparten {@code tipo} y {@code clave} y son filas distintas. Sin ella habria
 * que elegir una, y elegirla en silencio es el modo de falla que ARQ-09 §3 describe.
 *
 * @param tipo que clase de parametro es: {@code UIT}, {@code TRAMO_PREDIAL}, {@code ARANCEL}…
 * @param clave dentro del tipo, cual; nulo si el tipo tiene un solo valor
 * @param vigenciaDesde el dia desde el que rige la fila publicada, tal como se cargo
 */
public record LlaveDeParametro(String tipo, @Nullable String clave, LocalDate vigenciaDesde) {

    public LlaveDeParametro {
        Objects.requireNonNull(tipo, "Todo parametro tiene tipo");
        Objects.requireNonNull(
                vigenciaDesde,
                "Sin la fecha de vigencia la llave no distingue el valor de un ejercicio del de"
                        + " otro");
        tipo = tipo.strip();
        if (tipo.isEmpty()) {
            throw new IllegalArgumentException("El tipo de parametro no puede ir vacio");
        }
        clave = clave == null || clave.isBlank() ? null : clave.strip();
    }

    /**
     * La llave legible, con el mismo formato {@code tipo:clave} que usa {@link
     * kamayuk.rentas.parametros.ParametrosSellados}: lo que se escribe en un informe de carga tiene
     * que poder buscarse tal cual en el mensaje de un parametro ausente.
     */
    @Override
    public String toString() {
        return (clave == null ? tipo : tipo + ":" + clave) + " vigente desde " + vigenciaDesde;
    }
}
