// Viola: el frontend jamas envia municipalidadId (regla 2, ADR-0028 §2).
//
// El tenant sale del token y se fija con `SET LOCAL` en el backend. Un parametro que lo
// nombra es un parametro que alguien puede cambiar en la barra del navegador — y entonces
// el aislamiento entre municipalidades depende de que nadie lo intente.

export function rutaDelPadron(municipalidadId: string) {
  return `/rentas/api/v1/contribuyentes?municipalidad=${municipalidadId}`;
}
