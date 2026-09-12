/**
 * Los dos avisos del pie, **con las palabras del artboard**.
 *
 * El interprete trae unos neutros, porque esta destinado a `@kamayuk/ui` y alli no puede decir
 * «padron» —es vocabulario de un contexto, y ADR-0030 §4 lo prohibe—. Los de V8 viven AQUI, que
 * es donde el vocabulario de Rentas si pertenece, y se los pasa quien monta la pantalla.
 *
 * Salen del artboard palabra por palabra, y que lo sigan haciendo lo comprueba
 * `verificaciones/los-avisos-del-pie-son-los-del-artboard.test.ts`.
 */
export const AVISOS_DE_V8 = {
  consulta: 'Los datos son los que figuran en el padrón a la fecha de hoy.',
  escritura: 'Nada se escribe hasta que pulse Guardar.',
} as const;
