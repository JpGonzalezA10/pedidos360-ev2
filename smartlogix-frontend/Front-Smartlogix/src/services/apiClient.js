const DEFAULT_API_BASE_URL = 'http://localhost:8080'

const API_BASE_URL = (
  import.meta.env.VITE_API_BASE_URL || DEFAULT_API_BASE_URL
).replace(/\/$/, '')

/*
 * Cliente HTTP base para consumir el API Gateway.
 * Centraliza JSON, Authorization y mensajes de error para los modulos React.
 */
export async function apiRequest(path, { body, headers, method = 'GET', token } = {}) {
  let response

  try {
    response = await fetch(`${API_BASE_URL}${path}`, {
      method,
      headers: buildHeaders({ body, headers, token }),
      body: body ? JSON.stringify(body) : undefined,
    })
  } catch {
    throw new Error(
      'No fue posible conectar con el API Gateway. Verifica que el backend est\u00e9 activo.',
    )
  }

  const payload = await readResponsePayload(response)

  if (!response.ok) {
    const error = new Error(resolveErrorMessage(response.status, payload))
    // Se conserva el status y el detalle por campo (validaciones 400) para mostrarlos en la UI.
    error.status = response.status
    error.details = payload?.details || {}
    throw error
  }

  return payload
}

function buildHeaders({ body, headers = {}, token }) {
  const requestHeaders = {
    ...headers,
  }

  if (body) {
    requestHeaders['Content-Type'] = 'application/json'
  }

  if (token) {
    requestHeaders.Authorization = `Bearer ${token}`
  }

  return requestHeaders
}

async function readResponsePayload(response) {
  // Algunas respuestas validas como 204 no tienen body.
  const text = await response.text()

  if (!text) {
    return null
  }

  try {
    return JSON.parse(text)
  } catch {
    return { message: text }
  }
}

function resolveErrorMessage(status, payload) {
  // Spring puede responder con message, error o detail segun el handler usado.
  const apiMessage = payload?.message || payload?.error || payload?.detail

  if (apiMessage) {
    return apiMessage
  }

  if (status === 401) {
    return 'La sesi\u00f3n no es v\u00e1lida o expir\u00f3.'
  }

  if (status === 403) {
    return 'No tienes permisos para acceder a este recurso.'
  }

  if (status >= 500) {
    return 'El servicio solicitado no est\u00e1 disponible.'
  }

  return 'No fue posible completar la solicitud.'
}
