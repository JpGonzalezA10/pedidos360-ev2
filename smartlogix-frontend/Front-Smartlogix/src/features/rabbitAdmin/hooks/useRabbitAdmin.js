import { useEffect, useState } from 'react'
import {
  createBinding,
  createExchange,
  createQueue,
  deleteBinding,
  deleteExchange,
  deleteQueue,
  fetchBindings,
  fetchDeadLetterSummary,
  fetchExchanges,
  fetchQueues,
  peekQueueMessages,
  purgeQueue,
} from '../services/rabbitAdminService'

// Frecuencia de consulta de las DLQ para la alerta visual (polling).
const DLQ_POLL_INTERVAL_MS = 5000

const INITIAL_QUEUE_FORM = {
  deadLetterExchange: '',
  deadLetterRoutingKey: '',
  durable: true,
  maxLength: '',
  messageTtlMs: '',
  name: '',
}

const INITIAL_EXCHANGE_FORM = {
  durable: true,
  name: '',
  type: 'topic',
}

const INITIAL_BINDING_FORM = {
  exchange: '',
  queue: '',
  routingKey: '',
}

const EXCHANGE_TYPES = ['direct', 'topic', 'fanout', 'headers']

/*
 * Hook de orquestacion del administrador de RabbitMQ.
 * Carga colas, exchanges y bindings, ejecuta las operaciones de escritura
 * y consulta periodicamente las DLQ para mostrar la alerta.
 */
export function useRabbitAdmin(session) {
  const [queues, setQueues] = useState([])
  const [exchanges, setExchanges] = useState([])
  const [bindings, setBindings] = useState([])
  const [deadLetters, setDeadLetters] = useState({ alert: false, queues: [], totalMessages: 0 })
  const [inspectedQueue, setInspectedQueue] = useState(null)
  const [inspectedMessages, setInspectedMessages] = useState([])
  const [queueForm, setQueueForm] = useState(INITIAL_QUEUE_FORM)
  const [exchangeForm, setExchangeForm] = useState(INITIAL_EXCHANGE_FORM)
  const [bindingForm, setBindingForm] = useState(INITIAL_BINDING_FORM)
  const [isLoading, setIsLoading] = useState(false)
  const [isSaving, setIsSaving] = useState(false)
  const [errorMessage, setErrorMessage] = useState('')
  const [validationDetails, setValidationDetails] = useState({})
  const [successMessage, setSuccessMessage] = useState('')

  const token = session?.token

  async function loadDeadLetters() {
    if (!token) return
    try {
      setDeadLetters(await fetchDeadLetterSummary({ token }))
    } catch {
      // La alerta no debe interrumpir la pantalla si una consulta puntual falla.
    }
  }

  // Consulta colas, exchanges, bindings y DLQ; solo actualiza estado al recibir respuesta.
  async function fetchResources() {
    try {
      const [queueList, exchangeList, bindingList] = await Promise.all([
        fetchQueues({ token }),
        fetchExchanges({ token }),
        fetchBindings({ token }),
      ])
      setQueues(queueList || [])
      setExchanges(exchangeList || [])
      setBindings(bindingList || [])
      setErrorMessage('')
      await loadDeadLetters()
    } catch (err) {
      setErrorMessage(err.message || 'Error cargando recursos de RabbitMQ.')
    } finally {
      setIsLoading(false)
    }
  }

  // Recarga manual (boton Actualizar y despues de cada operacion).
  async function loadAll() {
    if (!token) return
    setIsLoading(true)
    await fetchResources()
  }

  useEffect(() => {
    if (!token) {
      return undefined
    }
    // Carga inicial y polling de las DLQ (alerta visual) mientras la seccion este abierta.
    const initialLoadId = setTimeout(fetchResources, 0)
    const intervalId = setInterval(loadDeadLetters, DLQ_POLL_INTERVAL_MS)
    return () => {
      clearTimeout(initialLoadId)
      clearInterval(intervalId)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token])

  // Ejecuta una operacion de escritura, muestra el resultado y recarga los listados.
  async function runOperation(operation, successText) {
    try {
      setIsSaving(true)
      setErrorMessage('')
      setValidationDetails({})
      setSuccessMessage('')
      await operation()
      setSuccessMessage(successText)
      await loadAll()
      return true
    } catch (err) {
      setErrorMessage(err.message || 'No fue posible completar la operacion.')
      setValidationDetails(err.details || {})
      return false
    } finally {
      setIsSaving(false)
    }
  }

  function handleFormChange(setter) {
    return (event) => {
      const { checked, name, type, value } = event.target
      setter((current) => ({ ...current, [name]: type === 'checkbox' ? checked : value }))
    }
  }

  async function submitQueue(event) {
    event.preventDefault()
    const queue = {
      deadLetterExchange: emptyToNull(queueForm.deadLetterExchange),
      deadLetterRoutingKey: emptyToNull(queueForm.deadLetterRoutingKey),
      durable: queueForm.durable,
      maxLength: numberOrNull(queueForm.maxLength),
      messageTtlMs: numberOrNull(queueForm.messageTtlMs),
      name: queueForm.name.trim(),
    }
    const ok = await runOperation(() => createQueue({ queue, token }), `Cola ${queue.name} creada.`)
    if (ok) setQueueForm(INITIAL_QUEUE_FORM)
  }

  async function submitExchange(event) {
    event.preventDefault()
    const exchange = { ...exchangeForm, name: exchangeForm.name.trim() }
    const ok = await runOperation(
      () => createExchange({ exchange, token }),
      `Exchange ${exchange.name} creado.`,
    )
    if (ok) setExchangeForm(INITIAL_EXCHANGE_FORM)
  }

  async function submitBinding(event) {
    event.preventDefault()
    const binding = {
      exchange: bindingForm.exchange,
      queue: bindingForm.queue,
      routingKey: bindingForm.routingKey.trim(),
    }
    const ok = await runOperation(
      () => createBinding({ binding, token }),
      `Binding ${binding.exchange} -> ${binding.queue} creado.`,
    )
    if (ok) setBindingForm(INITIAL_BINDING_FORM)
  }

  function removeQueue(name) {
    if (!window.confirm(`Eliminar la cola ${name}? Solo se eliminara si no tiene consumidores.`)) return
    runOperation(() => deleteQueue({ ifUnused: true, name, token }), `Cola ${name} eliminada.`)
  }

  function purge(name) {
    if (!window.confirm(`Purgar todos los mensajes de ${name}?`)) return
    runOperation(() => purgeQueue({ name, token }), `Cola ${name} purgada.`)
  }

  function removeExchange(name) {
    if (!window.confirm(`Eliminar el exchange ${name}?`)) return
    runOperation(() => deleteExchange({ name, token }), `Exchange ${name} eliminado.`)
  }

  function removeBinding(binding) {
    runOperation(
      () => deleteBinding({
        binding: { exchange: binding.exchange, queue: binding.destination, routingKey: binding.routingKey },
        token,
      }),
      'Binding eliminado.',
    )
  }

  async function inspectQueue(name) {
    try {
      setErrorMessage('')
      setInspectedQueue(name)
      setInspectedMessages(await peekQueueMessages({ count: 5, name, token }))
    } catch (err) {
      setErrorMessage(err.message || 'No fue posible leer los mensajes.')
    }
  }

  return {
    EXCHANGE_TYPES,
    bindingForm,
    bindings,
    deadLetters,
    errorMessage,
    exchangeForm,
    exchanges,
    handleBindingChange: handleFormChange(setBindingForm),
    handleExchangeChange: handleFormChange(setExchangeForm),
    handleQueueChange: handleFormChange(setQueueForm),
    inspectQueue,
    inspectedMessages,
    inspectedQueue,
    isLoading,
    isSaving,
    loadAll,
    purge,
    queueForm,
    queues,
    removeBinding,
    removeExchange,
    removeQueue,
    submitBinding,
    submitExchange,
    submitQueue,
    successMessage,
    validationDetails,
  }
}

function emptyToNull(value) {
  return value && value.trim() ? value.trim() : null
}

function numberOrNull(value) {
  return value === '' || value === null ? null : Number(value)
}
