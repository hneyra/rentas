// Viola: las peticiones pasan por «solicitar» de `src/api`, no por un `fetch` suelto.
//
// Este `fetch` no lleva token, no lleva clave de idempotencia y no sabe leer el
// `problem+json` del backend. Funciona en desarrollo, donde no hay ni token ni errores, y
// se descubre en la municipalidad.
export async function traerContribuyentes() {
  const respuesta = await fetch('/rentas/api/v1/contribuyentes');
  return respuesta.json();
}
