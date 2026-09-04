package kamayuk.rentas.rentas.aplicacion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.catastro.PredioDelContribuyente;
import kamayuk.rentas.catastro.PrediosDelContribuyente;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.rentas.dominio.DeclaracionJurada;
import kamayuk.rentas.rentas.dominio.DeclaracionJuradaRepository;
import kamayuk.rentas.rentas.dominio.predial.DetalleDeterminacionPredio;
import kamayuk.rentas.rentas.dominio.predial.Determinacion;
import kamayuk.rentas.rentas.dominio.predial.DeterminacionRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lo que la hoja resumen de una declaracion jurada consigna (#563).
 *
 * <h2>Por que existe</h2>
 *
 * <p>{@code Rentas · Declaración jurada} es el <b>unico documento del modulo pensado para
 * imprimirse y firmarse</b>: termina con «Declaro bajo juramento que los datos consignados son
 * verdaderos» y dos lineas de firma. Todo lo que consignaba —el declarante, sus predios con su
 * valuo, y los cuatro totales— venia del juego de datos de la maqueta, con cualquier sesion y sin
 * haber abierto ningun contribuyente. Lo unico que el backend publicaba de una DJ eran sus
 * identificadores y sus fechas: nada del contribuyente, nada del predio, ninguna cifra.
 *
 * <p>Un papel es la unica salida del sistema que sobrevive fuera de el. Una hoja con el nombre y el
 * DNI de una persona, dos predios que no son suyos y un «total a pagar», firmada por quien atiende
 * y por quien declara, <b>no se distingue de una correcta</b> una vez impresa.
 *
 * <h2>De donde sale cada cosa, y por que de ahi</h2>
 *
 * <ul>
 *   <li><b>El declarante</b>, del padron: {@code DirectorioDeContribuyentes}. El domicilio es el
 *       <b>vigente a la fecha de corte</b> y no «el ultimo» (regla 9): la hoja de una DJ de marzo
 *       tiene que poder reimprimirse como se imprimio.
 *   <li><b>Los predios</b>, de catastro: {@code PrediosDelContribuyente}, con su {@code %} de
 *       titularidad. Es la misma lectura que usa {@code GET /rentas/predios}.
 *   <li><b>Las cifras</b> —autovaluo, valuo exonerado, valuo afecto y el impuesto— de la <b>ultima
 *       determinacion predial del ejercicio</b> de ese contribuyente, que es el unico sitio donde
 *       el sistema las tiene. No se derivan aqui: el autovaluo <b>se declara</b> (#395), porque el
 *       sistema no sabe valorizar mientras falten el cuadro de valores unitarios y la depreciacion
 *       (GOB-03), los aranceles (D-02b) y el {@code % actualizacion} (D-11).
 * </ul>
 *
 * <h2>Lo que la hoja NO puede consignar todavia, dicho por su nombre</h2>
 *
 * <p>Sin determinacion del ejercicio no hay ninguna cifra que poner, y la hoja lo dice en vez de
 * dejar celdas en blanco que se lean como ceros. El <b>derecho de emision</b> y el <b>total a
 * pagar</b> tampoco viajan aunque haya determinacion: {@code Determinacion} guarda la base
 * imponible y el impuesto, no el derecho —que es una cifra de ordenanza local, {@code
 * DERECHO_EMISION_PREDIAL}, y sigue siendo D-02b—. Sumarlo aqui con un cero inventado seria
 * exactamente lo que este issue existe para impedir.
 *
 * <p><b>Una sola transaccion</b> para las cuatro lecturas (#486): entre una y otra cabria una
 * transferencia, y la hoja saldria diciendo que un predio es de dos personas y de ninguna.
 */
@Service
public class ConsultaDeLaHojaDeDeclaracion {

    private final DeclaracionJuradaRepository declaraciones;
    private final DirectorioDeContribuyentes directorio;
    private final PrediosDelContribuyente predios;
    private final DeterminacionRepository determinaciones;

    public ConsultaDeLaHojaDeDeclaracion(
            DeclaracionJuradaRepository declaraciones,
            DirectorioDeContribuyentes directorio,
            PrediosDelContribuyente predios,
            DeterminacionRepository determinaciones) {
        this.declaraciones = declaraciones;
        this.directorio = directorio;
        this.predios = predios;
        this.determinaciones = determinaciones;
    }

    /**
     * La hoja de esa declaracion, o vacio si no hay ninguna con ese numero en ese ejercicio.
     *
     * @param aLaFecha a que dia se resuelven el domicilio y la titularidad (regla 9)
     */
    @Transactional(readOnly = true)
    public Optional<Hoja> de(String numero, Ejercicio ejercicio, LocalDate aLaFecha) {
        Objects.requireNonNull(aLaFecha, "Toda lectura del padron indica a que fecha (regla 9)");

        Optional<DeclaracionJurada> encontrada = declaraciones.porNumero(numero, ejercicio);
        if (encontrada.isEmpty()) {
            return Optional.empty();
        }
        DeclaracionJurada declaracion = encontrada.get();
        long contribuyenteId = declaracion.contribuyenteId();

        ResumenDeContribuyente quien =
                directorio.porIds(Set.of(contribuyenteId)).get(contribuyenteId);
        String domicilio = directorio.domicilioFiscalDe(contribuyenteId, aLaFecha).orElse(null);

        Optional<Determinacion> determinacion =
                determinaciones.ultimaPredialDe(ejercicio, contribuyenteId);
        Map<Long, DetalleDeterminacionPredio> cifras = new LinkedHashMap<>();
        determinacion
                .map(Determinacion::id)
                .ifPresent(
                        id ->
                                determinaciones
                                        .detalleDe(id)
                                        .forEach(
                                                detalle ->
                                                        cifras.put(detalle.predioId(), detalle)));

        List<FilaDePredio> filas = new ArrayList<>();
        for (PredioDelContribuyente predio : predios.de(contribuyenteId, aLaFecha)) {
            DetalleDeterminacionPredio detalle = cifras.get(predio.predioId());
            filas.add(
                    new FilaDePredio(
                            predio.predioId(),
                            predio.codigoReferenciaCatastral(),
                            predio.direccion(),
                            predio.tipo(),
                            detalle == null
                                    ? predio.porcentajeTitularidad()
                                    : detalle.porcentajePropiedad(),
                            detalle == null ? null : detalle.autovaluo(),
                            detalle == null ? null : detalle.valuoExonerado(),
                            detalle == null ? null : detalle.baseImponiblePredio()));
        }

        return Optional.of(
                new Hoja(
                        declaracion,
                        aLaFecha,
                        quien,
                        domicilio,
                        List.copyOf(filas),
                        determinacion.map(Determinacion::baseImponible).orElse(null),
                        determinacion.map(Determinacion::montoDeterminado).orElse(null),
                        List.copyOf(loQueFalta(determinacion.isPresent(), ejercicio))));
    }

    /**
     * Lo que la hoja no puede consignar, nombrado.
     *
     * <p>Se publica como lista y no como un booleano «imprimible» para que la pantalla pueda decir
     * <b>que</b> falta: «no se puede imprimir» sin decir por que es lo que hace que alguien lo
     * imprima igual desde otro sitio.
     */
    private static List<String> loQueFalta(boolean hayDeterminacion, Ejercicio ejercicio) {
        List<String> falta = new ArrayList<>();
        if (!hayDeterminacion) {
            falta.add(
                    "No hay determinacion del impuesto predial del ejercicio "
                            + ejercicio.valor()
                            + " para este contribuyente: sin ella la hoja no tiene autovaluo, ni"
                            + " valuo afecto, ni impuesto que consignar. Se calcula en «Calculo"
                            + " individual del impuesto predial»");
        }
        // Aunque haya determinacion: el derecho de emision es una cifra de ordenanza local
        // (DERECHO_EMISION_PREDIAL, D-02b) y la determinacion guarda la base y el impuesto,
        // no el derecho. Sin el no hay «total a pagar» que escribir en un papel que se firma.
        falta.add(
                "El derecho de emision y el total a pagar no se publican: son"
                        + " DERECHO_EMISION_PREDIAL del conjunto sellado, una cifra de ordenanza"
                        + " local que sigue sin cargarse (D-02b)");
        return falta;
    }

    /**
     * La hoja entera, con su fecha de corte.
     *
     * @param declarante nulo si el contribuyente ya no esta en el padron; la hoja lo dice en vez de
     *     inventar un nombre
     * @param valuoAfectoTotal y {@code impuestoInsoluto} nulos cuando no hay determinacion del
     *     ejercicio: no hay cifra que dar, y un cero se leeria como «no debe nada»
     * @param faltan lo que la hoja no puede consignar todavia, con su motivo
     */
    public record Hoja(
            DeclaracionJurada declaracion,
            LocalDate aLaFecha,
            @Nullable ResumenDeContribuyente declarante,
            @Nullable String domicilioFiscal,
            List<FilaDePredio> predios,
            @Nullable Dinero valuoAfectoTotal,
            @Nullable Dinero impuestoInsoluto,
            List<String> faltan) {}

    /**
     * Un predio de la hoja.
     *
     * <p>El {@code porcentajePropiedad} sale de la determinacion cuando la hay —es el que se uso
     * para calcular, y la hoja tiene que decir el que se aplico, no el de hoy— y de la titularidad
     * vigente cuando no.
     */
    public record FilaDePredio(
            long predioId,
            String codigoReferenciaCatastral,
            String direccion,
            String tipo,
            Porcentaje porcentajePropiedad,
            @Nullable Dinero autovaluo,
            @Nullable Dinero valuoExonerado,
            @Nullable Dinero valuoAfecto) {}
}
