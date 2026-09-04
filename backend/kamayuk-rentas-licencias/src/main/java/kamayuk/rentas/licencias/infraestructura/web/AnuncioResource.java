package kamayuk.rentas.licencias.infraestructura.web;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.licencias.aplicacion.ConsultaDeAnuncios;
import kamayuk.rentas.licencias.dominio.Anuncio;
import kamayuk.rentas.licencias.dominio.MovimientoDeAnuncio;
import kamayuk.rentas.web.ImporteActualizado;
import org.jspecify.annotations.Nullable;

/**
 * Una autorizacion de anuncio tal como sale por HTTP (#51, RF-114).
 *
 * <p>Los nombres de los campos son los de la pantalla {@code anuncios}: {@code nroAutorizacion},
 * {@code claseAnuncio}, {@code nroLados}. No son los de la tabla, ni los del dominio: el contrato
 * lo fija el prototipo, y traducir aqui es mas barato que traducir en cada pantalla.
 *
 * <p><b>{@code estadoALaFecha} viaja siempre</b>, y no es un adorno: el estado de una autorizacion
 * depende del dia, asi que una respuesta que dijera «VENCIDO» sin decir a que fecha seria una
 * respuesta que manana significa otra cosa (regla 9, RNF-075).
 *
 * <p><b>La tasa viaja como {@link ImporteActualizado}</b>, con su fecha pegada. No es «lo que se
 * debe» —eso lo dice el libro, que descuenta lo pagado y es de otro contexto—: es lo que esta
 * autorizacion ha devengado hasta la fecha de corte, sumando las tasas que cada acto copio cuando
 * se asento.
 *
 * <p><b>El area viaja tipada</b> (#607). Se escribia a mano con {@code valor().toPlainString()}:
 * daba la cifra buena, pero era una segunda convencion para lo mismo, y de tener dos salio que
 * catastro compusiera con {@code toString()} y publicara «360.00 m2» del mismo predio que aqui sale
 * «360.00». Ahora la escribe el serializador que {@code ConfiguracionDeJson} registra para {@code
 * AreaM2}, que es un solo sitio; la unidad la sigue poniendo el nombre del campo, no el dato.
 */
public record AnuncioResource(
        String nroAutorizacion,
        String est,
        String estado,
        LocalDate estadoALaFecha,
        String contribuyente,
        String codContribuyente,
        String documentoDelTitular,
        @Nullable String nroLicencia,
        String claseAnuncio,
        String tipoAnuncio,
        @Nullable String ubicacion,
        @Nullable String forma,
        @Nullable String denominacion,
        String direccion,
        AreaM2 area,
        int nroLados,
        int cantidad,
        LocalDate fecInicio,
        @Nullable LocalDate fecVenc,
        @Nullable String nroDeExpediente,
        @Nullable LocalDate fechaExp,
        ImporteActualizado tasaDevengada,
        List<MovimientoResource> historial) {

    /** La fila de la grilla y la ficha, con lo que cada una traiga. */
    public static AnuncioResource de(ConsultaDeAnuncios.AnuncioEnConsulta fila) {
        Anuncio anuncio = fila.anuncio();
        return new AnuncioResource(
                anuncio.numero(),
                fila.estado().inicial(),
                fila.estado().name(),
                fila.aLaFecha(),
                fila.nombreDelTitular(),
                fila.codigoDelTitular(),
                fila.documentoDelTitular(),
                anuncio.licenciaId() == null ? null : String.valueOf(anuncio.licenciaId()),
                anuncio.clase().name(),
                anuncio.tipo().name(),
                anuncio.emplazamiento(),
                anuncio.forma(),
                anuncio.denominacion(),
                anuncio.ubicacion(),
                anuncio.area(),
                anuncio.lados(),
                anuncio.cantidad(),
                anuncio.fechaAutorizacion(),
                fila.vigenciaHasta(),
                anuncio.expediente(),
                anuncio.fechaExpediente(),
                new ImporteActualizado(fila.devengado(), fila.aLaFecha()),
                fila.historial().stream().map(MovimientoResource::de).toList());
    }

    /**
     * Un acto del historial.
     *
     * <p>{@code referenciaDelCargo} viaja a proposito: es la cadena con la que el cargo entro en el
     * libro, asi que es lo que permite ir del anuncio al asiento sin adivinar. Y {@code tasa} lleva
     * su fecha, que es la del acto que la asento y no la de la consulta.
     */
    public record MovimientoResource(
            String tipo,
            LocalDate fecha,
            @Nullable Integer ejercicio,
            @Nullable String referenciaDelCargo,
            @Nullable ImporteActualizado tasa,
            @Nullable LocalDate fecVenc,
            @Nullable String motivo,
            String observacion) {

        static MovimientoResource de(MovimientoDeAnuncio movimiento) {
            Ejercicio delCargo = movimiento.ejercicio();
            Dinero tasa = movimiento.tasa();
            return new MovimientoResource(
                    movimiento.tipo().name(),
                    movimiento.fecha(),
                    delCargo == null ? null : delCargo.valor(),
                    movimiento.referenciaCargo(),
                    tasa == null ? null : new ImporteActualizado(tasa, movimiento.fecha()),
                    movimiento.vigenciaHasta(),
                    movimiento.motivo(),
                    movimiento.observacion().texto());
        }
    }
}
