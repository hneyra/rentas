package kamayuk.rentas.nucleo.dominio.predial;

import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.nucleo.dominio.EstadoDeDeterminacion;
import kamayuk.rentas.nucleo.dominio.OrigenDeDeterminacion;
import kamayuk.rentas.parametros.IdentificadorDeRegla;
import org.jspecify.annotations.Nullable;

/**
 * La cabecera de una determinacion: cuanto le corresponde pagar a un contribuyente en un ejercicio,
 * con que conjunto de parametros se calculo y que reglas se aplicaron (#30, tabla {@code
 * determinacion} de V2, restringida en V20 a que el predial nunca lleve {@code predio_id}).
 *
 * <p><b>Es por contribuyente, no por predio</b> (NEG-05 §1): {@link #predioId} es siempre {@code
 * null} para {@code PREDIAL} —lo exige tambien la base, con {@code
 * determinacion_predial_sin_predio_ck}—. El aporte de cada predio a la base vive en {@link
 * DetalleDeterminacionPredio}, una fila por predio, referenciando esta cabecera.
 *
 * <p><b>Recalcular no modifica, crea otra</b> (AC2/AC3 de #30, ADR-0007): no hay ningun metodo que
 * cambie {@code baseImponible} ni {@code montoDeterminado} de una determinacion ya guardada. Dos
 * calculos del mismo contribuyente y ejercicio, con dos {@code conjunto_id} distintos, son dos
 * filas. {@link kamayuk.rentas.nucleo.dominio.predial.DeterminacionRepository} no tiene {@code
 * actualizar}: es estructural, no una convencion que alguien pueda romper con un {@code UPDATE}
 * suelto.
 *
 * <p>Nacio en #30 con un unico constructor publico, {@link #nuevaPredial}, porque «arbitrios,
 * vehicular, alcabala, etc. no tenian su regla de calculo todavia». #32 agrega {@link
 * #nuevaVehicular}, {@link #nuevaAlcabala} y {@link #nuevaEspectaculos}: los tres son
 * determinaciones de una sola partida —nunca llevan {@link DetalleDeterminacionPredio}— y por eso
 * usan {@link DeterminacionRepository#insertar(Determinacion)}, no la sobrecarga con detalle que
 * sigue siendo exclusiva del predial.
 *
 * @param id nulo mientras no se ha guardado; lo asigna la base
 * @param ejercicio el ejercicio que determina
 * @param tributo hoy siempre {@code "PREDIAL"} (ver {@link #nuevaPredial})
 * @param periodo el periodo dentro del ejercicio; {@code null} en un tributo anual como el predial
 * @param contribuyenteId el contribuyente determinado
 * @param predioId siempre {@code null} para {@code PREDIAL} (regla estructural de NEG-05 §1)
 * @param vehiculoId siempre {@code null} para {@code PREDIAL}
 * @param conjuntoId el conjunto de parametros sellado con que se calculo (ADR-0007)
 * @param baseImponible la base del contribuyente, ya agregada ({@code
 *     RT011BaseImponibleDelContribuyente})
 * @param montoDeterminado el impuesto resultante, ya redondeado
 * @param reglasAplicadas los identificadores de las reglas que produjeron el monto, en el orden en
 *     que se aplicaron
 * @param origen de donde sale esta determinacion
 * @param estado en que situacion esta
 * @param usuarioCalculo quien la calculo; nulo en una determinacion que todavia no se guardo
 */
public record Determinacion(
        @Nullable Long id,
        Ejercicio ejercicio,
        String tributo,
        @Nullable Integer periodo,
        long contribuyenteId,
        @Nullable Long predioId,
        @Nullable Long vehiculoId,
        long conjuntoId,
        Dinero baseImponible,
        Dinero montoDeterminado,
        List<String> reglasAplicadas,
        OrigenDeDeterminacion origen,
        EstadoDeDeterminacion estado,
        @Nullable String usuarioCalculo) {

    private static final String PREDIAL = "PREDIAL";
    private static final String VEHICULAR = "VEHICULAR";
    private static final String ALCABALA = "ALCABALA";
    private static final String ESPECTACULOS = "ESPECTACULOS";

    public Determinacion {
        Objects.requireNonNull(ejercicio, "La determinacion necesita su ejercicio");
        Objects.requireNonNull(tributo, "La determinacion necesita su tributo");
        if (contribuyenteId <= 0) {
            throw new IllegalArgumentException(
                    "La determinacion tiene un contribuyente: el identificador debe ser positivo");
        }
        if (PREDIAL.equals(tributo) && predioId != null) {
            throw new IllegalArgumentException(
                    "El predial se determina por contribuyente, nunca por un solo predio (NEG-05"
                            + " §1): predioId debe ser null. Ver determinacion_predial_sin_predio_ck");
        }
        if (VEHICULAR.equals(tributo) && (vehiculoId == null || predioId != null)) {
            throw new IllegalArgumentException(
                    "El vehicular se determina por vehiculo: vehiculoId es obligatorio y predioId"
                            + " debe ser null");
        }
        if (ALCABALA.equals(tributo) && (predioId == null || vehiculoId != null)) {
            throw new IllegalArgumentException(
                    "La alcabala grava la transferencia de un predio (TUO LTM art. 21): predioId es"
                            + " obligatorio y vehiculoId debe ser null");
        }
        if (ESPECTACULOS.equals(tributo) && (predioId != null || vehiculoId != null)) {
            throw new IllegalArgumentException(
                    "Los espectaculos publicos no gravan un predio ni un vehiculo: predioId y"
                            + " vehiculoId deben ser null");
        }
        if (conjuntoId <= 0) {
            throw new IllegalArgumentException(
                    "La determinacion necesita el conjunto de parametros sellado con que se"
                            + " calculo (ADR-0007): el identificador debe ser positivo");
        }
        Objects.requireNonNull(baseImponible, "La determinacion necesita su base imponible");
        if (baseImponible.esNegativo()) {
            throw new IllegalArgumentException("La base imponible no puede ser negativa");
        }
        Objects.requireNonNull(montoDeterminado, "La determinacion necesita su monto determinado");
        if (montoDeterminado.esNegativo()) {
            throw new IllegalArgumentException("El monto determinado no puede ser negativo");
        }
        Objects.requireNonNull(reglasAplicadas, "La determinacion necesita las reglas que aplico");
        if (reglasAplicadas.isEmpty()) {
            throw new IllegalArgumentException(
                    "Una determinacion sin ninguna regla aplicada no es reproducible (ADR-0007)");
        }
        if (PREDIAL.equals(tributo)) {
            // Valida el formato de cada identificador (defensa en profundidad: ya lo hizo el
            // motor), y normaliza a la representacion canonica antes de guardar. Solo el predial
            // tiene un catalogo RT-xxx registrado en NEG-05; los demas tributos citan la llave del
            // parametro que aplicaron (tipo:clave), que no tiene ese formato.
            reglasAplicadas =
                    reglasAplicadas.stream().map(r -> IdentificadorDeRegla.de(r).valor()).toList();
        } else {
            reglasAplicadas = List.copyOf(reglasAplicadas);
            for (String regla : reglasAplicadas) {
                if (regla.isBlank()) {
                    throw new IllegalArgumentException("Una regla aplicada no puede ir en blanco");
                }
            }
        }
        Objects.requireNonNull(origen, "La determinacion necesita su origen");
        Objects.requireNonNull(estado, "La determinacion necesita su estado");
    }

    /**
     * Una determinacion predial nueva, todavia sin guardar: {@code origen = ORDINARIA}, {@code
     * estado = BORRADOR}, sin periodo (el predial es anual) y sin predio ni vehiculo (por
     * contribuyente, NEG-05 §1).
     */
    public static Determinacion nuevaPredial(
            Ejercicio ejercicio,
            long contribuyenteId,
            long conjuntoId,
            Dinero baseImponible,
            Dinero montoDeterminado,
            List<String> reglasAplicadas) {
        return new Determinacion(
                null,
                ejercicio,
                PREDIAL,
                null,
                contribuyenteId,
                null,
                null,
                conjuntoId,
                baseImponible,
                montoDeterminado,
                reglasAplicadas,
                OrigenDeDeterminacion.ORDINARIA,
                EstadoDeDeterminacion.BORRADOR,
                null);
    }

    /**
     * Una determinacion vehicular nueva, todavia sin guardar: {@code origen = ORDINARIA}, {@code
     * estado = BORRADOR}, sin periodo (el vehicular es anual) y sobre el vehiculo indicado, nunca
     * un predio (#32).
     */
    public static Determinacion nuevaVehicular(
            Ejercicio ejercicio,
            long contribuyenteId,
            long vehiculoId,
            long conjuntoId,
            Dinero baseImponible,
            Dinero montoDeterminado,
            List<String> reglasAplicadas) {
        return new Determinacion(
                null,
                ejercicio,
                VEHICULAR,
                null,
                contribuyenteId,
                null,
                vehiculoId,
                conjuntoId,
                baseImponible,
                montoDeterminado,
                reglasAplicadas,
                OrigenDeDeterminacion.ORDINARIA,
                EstadoDeDeterminacion.BORRADOR,
                null);
    }

    /**
     * Una determinacion de alcabala nueva, todavia sin guardar: sobre el predio transferido, nunca
     * un vehiculo (TUO LTM art. 21; #32).
     */
    public static Determinacion nuevaAlcabala(
            Ejercicio ejercicio,
            long contribuyenteId,
            long predioId,
            long conjuntoId,
            Dinero baseImponible,
            Dinero montoDeterminado,
            List<String> reglasAplicadas) {
        return new Determinacion(
                null,
                ejercicio,
                ALCABALA,
                null,
                contribuyenteId,
                predioId,
                null,
                conjuntoId,
                baseImponible,
                montoDeterminado,
                reglasAplicadas,
                OrigenDeDeterminacion.ORDINARIA,
                EstadoDeDeterminacion.BORRADOR,
                null);
    }

    /**
     * Una determinacion de espectaculos publicos nueva, todavia sin guardar: sobre el organizador
     * —{@code contribuyenteId}—, sin predio ni vehiculo (#32).
     */
    public static Determinacion nuevaEspectaculos(
            Ejercicio ejercicio,
            long contribuyenteId,
            long conjuntoId,
            Dinero baseImponible,
            Dinero montoDeterminado,
            List<String> reglasAplicadas) {
        return new Determinacion(
                null,
                ejercicio,
                ESPECTACULOS,
                null,
                contribuyenteId,
                null,
                null,
                conjuntoId,
                baseImponible,
                montoDeterminado,
                reglasAplicadas,
                OrigenDeDeterminacion.ORDINARIA,
                EstadoDeDeterminacion.BORRADOR,
                null);
    }

    public boolean esNueva() {
        return id == null;
    }
}
