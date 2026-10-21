/*
 * Secciones disponibles en el panel ADMIN.
 * Pedidos y envios quedan visibles aunque sus APIs aun no tengan avance.
 */
export const ADMIN_SECTIONS = [
  { id: 'home', label: 'Inicio' },
  { id: 'auth', label: 'Auth' },
  { id: 'inventory', label: 'Inventario' },
  { id: 'orders', label: 'Pedidos' },
  { id: 'shipping', label: 'Env\u00edos' },
  // EP3: administracion de RabbitMQ y alertas de DLQ.
  { id: 'messaging', label: 'Mensajer\u00eda' },
]
