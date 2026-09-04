package kamayuk.rentas.contribuyentes;

import java.util.Optional;
import kamayuk.rentas.dominio.DocumentoIdentidad;

/**
 * ¿Figura en el padron de <b>esta</b> municipalidad quien presenta este documento? (ADR-0020, #57).
 *
 * <h2>Por que es una puerta aparte y no un metodo mas de {@link DirectorioDeContribuyentes}</h2>
 *
 * <p>Porque quien pregunta no es la misma poblacion. El directorio existe para que un contexto
 * vecino resuelva el nombre del titular de un predio o el domicilio al que notificar: lo consulta
 * <b>un funcionario</b>, a traves de una pantalla del catalogo, dentro de su municipalidad. Esto lo
 * consulta el <b>ciudadano</b>, con su propio token, y la respuesta decide si esa municipalidad
 * entra siquiera en su recorrido.
 *
 * <p>Y la respuesta tambien es otra: {@link ContribuyenteAcreditado} lleva {@code activo}, que el
 * resumen del directorio no lleva —una grilla de titulares no pregunta si el titular sigue de alta,
 * y el portal tiene que <b>decirlo</b>—.
 *
 * <h2>El sondeo, y lo que no deja detras</h2>
 *
 * <p>Es lo primero que hace cada rama del recorrido, y cuando devuelve vacio <b>no se lee nada mas
 * de esa municipalidad y no se audita nada</b>. El sondeo del padron no es un acceso: auditarlo
 * convertiria la bitacora de cada municipio en una forma de saber que alguien existe en otro, que
 * es exactamente lo que el aislamiento impide por todos los demas caminos.
 *
 * <h2>Una fila, y lo garantiza la base</h2>
 *
 * <p>{@code contribuyente_documento_uq} es {@code UNIQUE (municipalidad_id, tipo_documento,
 * numero_documento)}: no hay dos. Es lo que permite componer la situacion de una municipalidad sin
 * tener que elegir entre candidatas.
 *
 * <h2>Y nada mas se compone a partir de aqui</h2>
 *
 * <p>Ni el conyuge ({@code conyuge_id}), ni la sucesion indivisa, ni la sociedad conyugal, ni el
 * RUC de la empresa que representa, ni las obligaciones donde figura como responsable solidario
 * (V12). Las cinco son composiciones plausibles y todas ensenan deuda de <b>otra</b> persona
 * —natural o juridica— a quien no es ella. Quien las necesite hara su propia decision y la
 * escribira; no salen gratis de esta puerta.
 */
public interface AcreditacionEnElPadron {

    /** La unica fila con ese documento, o vacio si esta persona no figura en esta municipalidad. */
    Optional<ContribuyenteAcreditado> de(DocumentoIdentidad documento);
}
