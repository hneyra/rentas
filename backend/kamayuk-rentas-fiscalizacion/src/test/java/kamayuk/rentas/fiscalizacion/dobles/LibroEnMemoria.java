package kamayuk.rentas.fiscalizacion.dobles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.ConsultaDeLoOriginado;
import kamayuk.rentas.cuentacorriente.ObligacionOriginada;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * El libro de {@code cuentacorriente} en memoria, <b>no vacio</b> (#342).
 *
 * <p>Las cinco pruebas que montaban el estado de cuenta simulaban un libro sin ningun asiento
 * —{@code (contribuyenteId, fecha) -> List.of()}— o con una sola obligacion en cero, y con eso no
 * se podia ver que el estado de cuenta le atribuia a la fiscalizacion la deuda ordinaria de la
 * misma unidad. Este doble guarda asientos con su <b>documento de origen</b> y su periodo, y agrupa
 * como el libro de verdad: {@link #todasDe} por obligacion, sin el periodo, como {@code
 * ConsultarDeuda#todasLasObligacionesDe}; y {@link #deLoOriginadoPor} por clave de saldo —con el
 * periodo— que tenga un cargo con alguno de los documentos, como {@code dominio.LoOriginadoPor}. Lo
 * que el libro de verdad contesta a esa pregunta lo mide {@code ConsultaDeDeudaCuentaCorrienteTest}
 * contra PostgreSQL.
 *
 * <p>Implementa los dos puertos, como la clase de verdad: el estado de cuenta solo pregunta por lo
 * originado, y {@link #todasDe} esta para poder medir que casar por clave —lo de antes de #342— se
 * llevaria la deuda ordinaria.
 *
 * <p>No calcula mora: el insoluto neto es la cifra. Lo que las pruebas miden es <b>que</b> deuda se
 * atribuye a la fiscalizacion, no cuanto crece.
 */
public final class LibroEnMemoria implements ConsultaDeDeudaPublica, ConsultaDeLoOriginado {

    private final List<AsientoDePrueba> asientos = new ArrayList<>();
    private int consultas;

    /** Un cargo: aumenta la deuda de su clave. */
    public LibroEnMemoria cargo(
            String tributo,
            int ejercicio,
            @Nullable Integer periodo,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            String monto,
            String documentoOrigen) {
        asientos.add(
                new AsientoDePrueba(
                        tributo,
                        new Ejercicio(ejercicio),
                        periodo == null ? 0 : periodo,
                        predioId,
                        vehiculoId,
                        Dinero.de(monto),
                        documentoOrigen,
                        true));
        return this;
    }

    /** Un abono: la reduce, con su propio documento —un recibo—. */
    public LibroEnMemoria abono(
            String tributo,
            int ejercicio,
            @Nullable Integer periodo,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            String monto,
            String documentoOrigen) {
        asientos.add(
                new AsientoDePrueba(
                        tributo,
                        new Ejercicio(ejercicio),
                        periodo == null ? 0 : periodo,
                        predioId,
                        vehiculoId,
                        Dinero.de(monto),
                        documentoOrigen,
                        false));
        return this;
    }

    /** Cuantas veces se le pregunto algo al libro. */
    public int consultas() {
        return consultas;
    }

    @Override
    public List<ObligacionPublica> todasDe(long contribuyenteId, LocalDate fecha) {
        consultas++;
        Map<String, List<AsientoDePrueba>> porObligacion = new LinkedHashMap<>();
        for (AsientoDePrueba asiento : asientos) {
            porObligacion
                    .computeIfAbsent(asiento.claveSinPeriodo(), k -> new ArrayList<>())
                    .add(asiento);
        }
        return porObligacion.values().stream().map(grupo -> publicar(grupo, fecha)).toList();
    }

    @Override
    public List<ObligacionOriginada> deLoOriginadoPor(
            long contribuyenteId, Set<String> documentosDeOrigen, LocalDate fecha) {
        consultas++;
        Set<String> buscados = new LinkedHashSet<>();
        for (String documento : documentosDeOrigen) {
            buscados.add(documento.strip().toUpperCase(Locale.ROOT));
        }
        Map<String, List<AsientoDePrueba>> porClave = new LinkedHashMap<>();
        Map<String, Set<String>> origenes = new LinkedHashMap<>();
        for (AsientoDePrueba asiento : asientos) {
            porClave.computeIfAbsent(asiento.claveDeSaldo(), k -> new ArrayList<>()).add(asiento);
            String documento = asiento.documentoOrigen().strip().toUpperCase(Locale.ROOT);
            if (asiento.cargo() && buscados.contains(documento)) {
                origenes.computeIfAbsent(asiento.claveDeSaldo(), k -> new LinkedHashSet<>())
                        .add(documento);
            }
        }
        List<ObligacionOriginada> originadas = new ArrayList<>();
        for (Map.Entry<String, Set<String>> origen : origenes.entrySet()) {
            List<AsientoDePrueba> grupo =
                    Objects.requireNonNull(porClave.get(origen.getKey()), "la clave existe");
            originadas.add(
                    new ObligacionOriginada(
                            publicar(grupo, fecha), grupo.get(0).periodo(), origen.getValue()));
        }
        return originadas;
    }

    private static ObligacionPublica publicar(List<AsientoDePrueba> grupo, LocalDate fecha) {
        AsientoDePrueba primero = grupo.get(0);
        Dinero neto = Dinero.CERO;
        for (AsientoDePrueba asiento : grupo) {
            neto = asiento.cargo() ? neto.mas(asiento.monto()) : neto.menos(asiento.monto());
        }
        return new ObligacionPublica(
                primero.tributo(),
                primero.ejercicio(),
                primero.predioId(),
                primero.vehiculoId(),
                fecha,
                neto,
                Dinero.CERO,
                Dinero.CERO,
                Dinero.CERO,
                "ORDINARIA");
    }

    private record AsientoDePrueba(
            String tributo,
            Ejercicio ejercicio,
            int periodo,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            Dinero monto,
            String documentoOrigen,
            boolean cargo) {

        AsientoDePrueba {
            Objects.requireNonNull(tributo);
            tributo = tributo.toUpperCase(Locale.ROOT);
        }

        String claveSinPeriodo() {
            return tributo + "|" + ejercicio.valor() + "|" + predioId + "|" + vehiculoId;
        }

        String claveDeSaldo() {
            return claveSinPeriodo() + "|" + periodo;
        }
    }
}
