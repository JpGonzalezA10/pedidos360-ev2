import { apiRequest } from '../../../services/apiClient'

/*
 * Servicio frontend del administrador de RabbitMQ (EP3).
 * Todas las rutas pasan por el API Gateway (/rabbit-admin/**, solo ADMIN)
 * con el JWT de Azure AD de la sesion activa.
 */
export function fetchQueues({ token }) {
  return apiRequest('/rabbit-admin/queues', { token })
}

export function createQueue({ queue, token }) {
  return apiRequest('/rabbit-admin/queues', { method: 'POST', body: queue, token })
}

export function deleteQueue({ name, ifEmpty = false, ifUnused = false, token }) {
  const query = new URLSearchParams({ ifEmpty: String(ifEmpty), ifUnused: String(ifUnused) })
  return apiRequest(`/rabbit-admin/queues/${encodeURIComponent(name)}?${query}`, {
    method: 'DELETE',
    token,
  })
}

export function purgeQueue({ name, token }) {
  return apiRequest(`/rabbit-admin/queues/${encodeURIComponent(name)}/messages`, {
    method: 'DELETE',
    token,
  })
}

export function peekQueueMessages({ name, count = 5, token }) {
  return apiRequest(`/rabbit-admin/queues/${encodeURIComponent(name)}/messages?count=${count}`, {
    token,
  })
}

export function fetchExchanges({ token }) {
  return apiRequest('/rabbit-admin/exchanges', { token })
}

export function createExchange({ exchange, token }) {
  return apiRequest('/rabbit-admin/exchanges', { method: 'POST', body: exchange, token })
}

export function deleteExchange({ name, token }) {
  return apiRequest(`/rabbit-admin/exchanges/${encodeURIComponent(name)}`, {
    method: 'DELETE',
    token,
  })
}

export function fetchBindings({ token }) {
  return apiRequest('/rabbit-admin/bindings', { token })
}

export function createBinding({ binding, token }) {
  return apiRequest('/rabbit-admin/bindings', { method: 'POST', body: binding, token })
}

export function deleteBinding({ binding, token }) {
  const query = new URLSearchParams({
    exchange: binding.exchange,
    queue: binding.queue,
    routingKey: binding.routingKey || '',
  })
  return apiRequest(`/rabbit-admin/bindings?${query}`, { method: 'DELETE', token })
}

export function fetchDeadLetterSummary({ token }) {
  return apiRequest('/rabbit-admin/dead-letters', { token })
}
