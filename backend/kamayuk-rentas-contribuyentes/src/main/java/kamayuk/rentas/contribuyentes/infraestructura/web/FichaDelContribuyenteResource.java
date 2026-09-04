package kamayuk.rentas.contribuyentes.infraestructura.web;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.contribuyentes.aplicacion.ConsultaDeLaFichaDelContribuyente;
import kamayuk.rentas.contribuyentes.dominio.Contacto;
import kamayuk.rentas.contribuyentes.dominio.Contribuyente;
import kamayuk.rentas.contribuyentes.dominio.Domicilio;
import kamayuk.rentas.contribuyentes.dominio.ResponsableSolidario;
import org.jspecify.annotations.Nullable;

/**
 * La ficha del contribuyente tal como sale por HTTP: donde esta, como se le ubica y quien responde
 * con el. Campos en español {@code camelCase} (ARQ-04 §3).
 *
 * <p><b>Lleva su fecha, y no es decorativa</b> (regla 9, RNF-075): {@code domicilioFiscal} y {@code
 * domicilioProcesal} son los vigentes a {@code aLaFecha}, no «los ultimos». Publicar la direccion
 * sin decir a que fecha rige es lo que hace que una notificacion de marzo se defienda con la
 * direccion de setiembre.
 *
 * <p>No lleva {@code municipalidadId} y no puede llevarlo: sale del token (regla 2).
 */
public record FichaDelContribuyenteResource(
        ContribuyenteResource contribuyente,
        DatosPersonalesResource datosPersonales,
        LocalDate aLaFecha,
        @Nullable DomicilioResource domicilioFiscal,
        @Nullable DomicilioResource domicilioProcesal,
        List<DomicilioResource> historialDeDomicilios,
        List<ContactoResource> contactos,
        List<ResponsableResource> responsables) {

    public static FichaDelContribuyenteResource de(ConsultaDeLaFichaDelContribuyente.Ficha ficha) {
        return new FichaDelContribuyenteResource(
                ContribuyenteResource.de(ficha.contribuyente()),
                DatosPersonalesResource.de(ficha.contribuyente()),
                ficha.aLaFecha(),
                ficha.domicilioFiscal() == null
                        ? null
                        : DomicilioResource.de(ficha.domicilioFiscal()),
                ficha.domicilioProcesal() == null
                        ? null
                        : DomicilioResource.de(ficha.domicilioProcesal()),
                ficha.historialDeDomicilios().stream().map(DomicilioResource::de).toList(),
                ficha.contactos().stream().map(ContactoResource::de).toList(),
                ficha.responsables().stream().map(ResponsableResource::de).toList());
    }

    /**
     * Un domicilio, con su tramo de vigencia entero.
     *
     * <p>{@code vigenciaHasta} nulo es el que rige hoy. El historial trae tambien los cerrados: no
     * se borra nada (regla 4), y {@code documentoOrigen} es lo que sostiene la notificacion si
     * alguien la impugna.
     */
    /**
     * Los tres datos personales que el expediente dibuja y la grilla no publica (#552).
     *
     * <p>Van <b>aqui</b> y no en {@link ContribuyenteResource}, y la diferencia importa: aquel es
     * la fila de una <b>grilla de busqueda</b> y su javadoc dice por que no los lleva —«lo que no
     * se publica no se filtra»—. La ficha es otra cosa: es la pantalla por la que se corrige el
     * padron, y desde #552 esos tres campos <b>se pueden guardar</b> por {@code PUT
     * /rentas/contribuyentes/&#123;id&#125;}. Un campo que se puede escribir y no se puede leer
     * deja a la pantalla dibujando una caja vacia sobre un dato que si existe.
     *
     * <p>{@code conyugeId} es el identificador interno y no el nombre: resolverlo costaria una
     * consulta mas por ficha, y quien lo necesite lo pide como pide cualquier otro contribuyente.
     */
    public record DatosPersonalesResource(
            @Nullable LocalDate fechaNacimiento,
            @Nullable String estadoCivil,
            @Nullable Long conyugeId) {

        public static DatosPersonalesResource de(Contribuyente contribuyente) {
            return new DatosPersonalesResource(
                    contribuyente.fechaNacimiento(),
                    contribuyente.estadoCivil(),
                    contribuyente.conyugeId());
        }
    }

    public record DomicilioResource(
            long id,
            String tipo,
            String direccion,
            @Nullable String referencia,
            @Nullable String ubigeo,
            LocalDate vigenciaDesde,
            @Nullable LocalDate vigenciaHasta,
            String documentoOrigen) {

        public static DomicilioResource de(Domicilio domicilio) {
            return new DomicilioResource(
                    domicilio.id() == null ? 0L : domicilio.id(),
                    domicilio.tipo().name(),
                    domicilio.direccion(),
                    domicilio.referencia(),
                    domicilio.ubigeo(),
                    domicilio.vigenciaDesde(),
                    domicilio.vigenciaHasta(),
                    domicilio.documentoOrigen());
        }
    }

    /** Un telefono, un correo o un gestor. Se da de baja con {@code vigente}, no se borra. */
    public record ContactoResource(
            long id,
            String tipo,
            String valor,
            @Nullable String nombre,
            @Nullable String documento,
            @Nullable String observacion,
            boolean vigente) {

        public static ContactoResource de(Contacto contacto) {
            return new ContactoResource(
                    contacto.id() == null ? 0L : contacto.id(),
                    contacto.tipo().name(),
                    contacto.valor(),
                    contacto.nombre(),
                    contacto.documento(),
                    contacto.observacion(),
                    contacto.vigente());
        }
    }

    /**
     * Quien responde con el contribuyente, y desde cuando.
     *
     * <p>{@code porcentaje} viaja como texto, no como numero: es un {@link
     * kamayuk.rentas.dominio.Porcentaje} y los objetos de valor se serializan como cadena para no
     * perder escala por el camino.
     */
    public record ResponsableResource(
            long id,
            long responsableId,
            String vinculo,
            @Nullable String porcentaje,
            LocalDate vigenciaDesde,
            @Nullable LocalDate vigenciaHasta,
            String documentoOrigen) {

        public static ResponsableResource de(ResponsableSolidario responsable) {
            return new ResponsableResource(
                    responsable.id() == null ? 0L : responsable.id(),
                    responsable.responsableId(),
                    responsable.vinculo().name(),
                    responsable.porcentaje() == null
                            ? null
                            : responsable.porcentaje().valor().toPlainString(),
                    responsable.vigenciaDesde(),
                    responsable.vigenciaHasta(),
                    responsable.documentoOrigen());
        }
    }
}
