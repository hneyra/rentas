package kamayuk.rentas.cuentacorriente.aplicacion;

import java.time.LocalDate;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.cuentacorriente.MovimientoDelLibro;
import kamayuk.rentas.cuentacorriente.MovimientosDelLibro;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.AsientoRepository;
import kamayuk.rentas.cuentacorriente.dominio.CriterioDeAltasBajas;
import kamayuk.rentas.cuentacorriente.dominio.CriterioDePagos;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementa {@link MovimientosDelLibro} sobre {@link AsientoRepository} (#25, RF-046).
 *
 * <p><b>Llama a los mismos dos metodos del repositorio que los endpoints de {@code consulta_pagos}
 * y {@code consulta_altas_bajas}</b>, con los mismos dos criterios: no hay una segunda consulta
 * escrita para la ficha unificada. Es lo que garantiza que la pestaña «Pagos Realizados» de la
 * consulta unificada no pueda decir una cosa y {@code GET /consultas/pagos} otra —dos consultas
 * para la misma pregunta son dos oportunidades de divergir, y la que se mira menos es la que se
 * queda mal—.
 *
 * <p>{@code @Transactional(readOnly = true)} en los dos metodos: sin transaccion no hay {@code SET
 * LOCAL}, y sin el la politica RLS no puede evaluar {@code current_setting('app.municipalidad_id')}
 * —la consulta <b>falla</b>—. Cuando quien llama ya abrio la suya, estas se unen a ella y el
 * contexto se fija una sola vez.
 */
@Service
public class MovimientosDelLibroCuentaCorriente implements MovimientosDelLibro {

    private final AsientoRepository repositorio;

    public MovimientosDelLibroCuentaCorriente(AsientoRepository repositorio) {
        this.repositorio = repositorio;
    }

    @Override
    @Transactional(readOnly = true)
    public Pagina<MovimientoDelLibro> pagosDe(
            String codigoContribuyente,
            @Nullable LocalDate desde,
            @Nullable LocalDate hasta,
            Paginacion paginacion) {
        CriterioDePagos criterio = new CriterioDePagos(codigoContribuyente, desde, hasta);
        return repositorio
                .pagos(criterio, paginacion)
                .mapear(MovimientosDelLibroCuentaCorriente::aPublico);
    }

    @Override
    @Transactional(readOnly = true)
    public Pagina<MovimientoDelLibro> altasYBajasDe(
            String codigoContribuyente, @Nullable String tributo, Paginacion paginacion) {
        CriterioDeAltasBajas criterio =
                new CriterioDeAltasBajas(codigoContribuyente, null, tributo, null);
        return repositorio
                .altasYBajas(criterio, paginacion)
                .mapear(MovimientosDelLibroCuentaCorriente::aPublico);
    }

    /**
     * Un asiento guardado siempre tiene identificador; el {@code null} del record es para el que
     * todavia no se ha insertado, y por aqui no pasa ninguno de esos.
     */
    private static MovimientoDelLibro aPublico(Asiento asiento) {
        return new MovimientoDelLibro(
                asiento.id() == null ? 0L : asiento.id(),
                asiento.ejercicio(),
                asiento.tributo(),
                asiento.concepto().name(),
                asiento.tipo().name(),
                asiento.fase().name(),
                asiento.periodo(),
                asiento.predioId(),
                asiento.vehiculoId(),
                asiento.monto(),
                asiento.fechaValor(),
                asiento.documentoOrigen(),
                asiento.motivo());
    }
}
