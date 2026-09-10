/**
 * La COPIA LOCAL de la autorizacion —modulos, accesos, grupos, usuarios, miembros y permisos—, las
 * sesiones y la auditoria (ARQ-01 §3.12, REQ-03). Desde la etapa 4 de ADR-0039 la administracion
 * vive en {@code identidad}: aqui se siembra el arranque en frio, se consume su buzon y se autoriza
 * contra lo que la copia dice.
 *
 * <p>Transversal: todos dependen de el y el de ninguno. La autenticacion vive fuera (ADR-0005);
 * aqui esta la autorizacion, que es la del manual.
 */
@org.jspecify.annotations.NullMarked
package kamayuk.rentas.seguridad;
