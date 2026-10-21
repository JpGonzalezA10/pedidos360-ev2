import { useState } from 'react'
import { useRabbitAdmin } from '../hooks/useRabbitAdmin'
import '../../orders/styles/orders-section.css'
import '../styles/rabbit-admin-section.css'

const DEFAULT_OPEN_SECTIONS = {
  bindings: false,
  deadLetters: true,
  exchanges: false,
  queues: true,
}

/*
 * Seccion ADMIN de mensajeria (EP3).
 * Consume el microservicio rabbitmq-admin via API Gateway para administrar
 * colas, exchanges y bindings, y muestra una alerta cuando hay mensajes en DLQ.
 */
function RabbitAdminSection({ session }) {
  const rabbitAdmin = useRabbitAdmin(session)
  const [openSections, setOpenSections] = useState(DEFAULT_OPEN_SECTIONS)

  function toggleSection(sectionId) {
    setOpenSections((current) => ({ ...current, [sectionId]: !current[sectionId] }))
  }

  const totalMessages = rabbitAdmin.queues.reduce((sum, queue) => sum + queue.messages, 0)
  const totalConsumers = rabbitAdmin.queues.reduce((sum, queue) => sum + queue.consumers, 0)

  return (
    <section className="orders-section" aria-label="Mensajeria RabbitMQ">
      <div className="orders-header">
        <div>
          <p className="orders-kicker">Mensajeria</p>
          <h2>RabbitMQ: colas, exchanges y DLQ</h2>
        </div>
        <button
          className="orders-ghost-button"
          disabled={rabbitAdmin.isLoading}
          onClick={() => rabbitAdmin.loadAll()}
          type="button"
        >
          {rabbitAdmin.isLoading ? 'Actualizando...' : 'Actualizar'}
        </button>
      </div>

      <DeadLetterAlert deadLetters={rabbitAdmin.deadLetters} />
      <Messages rabbitAdmin={rabbitAdmin} />

      <div className="orders-summary" aria-label="Resumen RabbitMQ">
        <SummaryItem label="Colas" value={rabbitAdmin.queues.length} />
        <SummaryItem label="Exchanges" value={rabbitAdmin.exchanges.length} />
        <SummaryItem label="Bindings" value={rabbitAdmin.bindings.length} />
        <SummaryItem label="Mensajes en colas" value={totalMessages} />
        <SummaryItem label="Consumidores" value={totalConsumers} />
        <SummaryItem label="Mensajes en DLQ" value={rabbitAdmin.deadLetters.totalMessages} />
      </div>

      <div className="orders-accordion">
        <Collapsible
          id="deadLetters"
          isOpen={openSections.deadLetters}
          meta={`${rabbitAdmin.deadLetters.queues.length} DLQ`}
          onToggle={toggleSection}
          title="Dead Letter Queues"
        >
          <DeadLetterPanel rabbitAdmin={rabbitAdmin} />
        </Collapsible>

        <Collapsible
          id="queues"
          isOpen={openSections.queues}
          meta={`${rabbitAdmin.queues.length} colas`}
          onToggle={toggleSection}
          title="Colas"
        >
          <QueueForm rabbitAdmin={rabbitAdmin} />
          <QueuesTable rabbitAdmin={rabbitAdmin} />
        </Collapsible>

        <Collapsible
          id="exchanges"
          isOpen={openSections.exchanges}
          meta={`${rabbitAdmin.exchanges.length} exchanges`}
          onToggle={toggleSection}
          title="Exchanges"
        >
          <ExchangeForm rabbitAdmin={rabbitAdmin} />
          <ExchangesTable rabbitAdmin={rabbitAdmin} />
        </Collapsible>

        <Collapsible
          id="bindings"
          isOpen={openSections.bindings}
          meta={`${rabbitAdmin.bindings.length} bindings`}
          onToggle={toggleSection}
          title="Bindings"
        >
          <BindingForm rabbitAdmin={rabbitAdmin} />
          <BindingsTable rabbitAdmin={rabbitAdmin} />
        </Collapsible>
      </div>
    </section>
  )
}

function DeadLetterAlert({ deadLetters }) {
  if (!deadLetters.alert) {
    return (
      <p className="rabbit-dlq-ok" role="status">
        Sin mensajes en Dead Letter Queues.
      </p>
    )
  }

  const affected = deadLetters.queues.filter((queue) => queue.messages > 0)
  return (
    <div className="rabbit-dlq-alert" role="alert">
      <strong>ALERTA: {deadLetters.totalMessages} mensaje(s) en Dead Letter Queues.</strong>
      <span>
        {affected.map((queue) => `${queue.name} (${queue.messages})`).join(', ')}. Revise los logs
        del microservicio y el detalle del mensaje antes de reprocesar.
      </span>
    </div>
  )
}

function Messages({ rabbitAdmin }) {
  const details = Object.entries(rabbitAdmin.validationDetails || {})
  return (
    <>
      {rabbitAdmin.errorMessage ? (
        <div className="orders-error" role="alert">
          {rabbitAdmin.errorMessage}
          {details.length > 0 ? (
            <ul className="rabbit-validation-list">
              {details.map(([field, message]) => (
                <li key={field}>
                  <strong>{field}</strong>: {message}
                </li>
              ))}
            </ul>
          ) : null}
        </div>
      ) : null}
      {rabbitAdmin.successMessage ? (
        <p className="orders-success" role="status">
          {rabbitAdmin.successMessage}
        </p>
      ) : null}
    </>
  )
}

function DeadLetterPanel({ rabbitAdmin }) {
  return (
    <div className="orders-panel">
      <div className="orders-table-wrap">
        <table className="orders-table">
          <thead>
            <tr>
              <th>DLQ</th>
              <th>Mensajes</th>
              <th>Acciones</th>
            </tr>
          </thead>
          <tbody>
            {rabbitAdmin.deadLetters.queues.map((queue) => (
              <tr className={queue.messages > 0 ? 'rabbit-row-alert' : undefined} key={queue.name}>
                <td>{queue.name}</td>
                <td>{queue.messages}</td>
                <td className="rabbit-actions">
                  <button className="orders-table-action" onClick={() => rabbitAdmin.inspectQueue(queue.name)} type="button">
                    Ver mensajes
                  </button>
                  <button className="orders-table-action" disabled={queue.messages === 0} onClick={() => rabbitAdmin.purge(queue.name)} type="button">
                    Purgar
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {rabbitAdmin.inspectedQueue ? (
        <div className="rabbit-inspector">
          <h3>Mensajes en {rabbitAdmin.inspectedQueue}</h3>
          {rabbitAdmin.inspectedMessages.length === 0 ? (
            <p>La cola no tiene mensajes.</p>
          ) : (
            rabbitAdmin.inspectedMessages.map((message, index) => (
              <article className="rabbit-message" key={`${message.routingKey}-${index}`}>
                <p>
                  <strong>Routing key:</strong> {message.routingKey} | <strong>Exchange:</strong>{' '}
                  {message.exchange} | <strong>Motivo:</strong> {deathReason(message.headers)}
                </p>
                <pre>{prettyPayload(message.payload)}</pre>
              </article>
            ))
          )}
        </div>
      ) : null}
    </div>
  )
}

function QueueForm({ rabbitAdmin }) {
  const form = rabbitAdmin.queueForm
  return (
    <form className="orders-form" onSubmit={rabbitAdmin.submitQueue}>
      <div className="orders-form-grid">
        <Field label="Nombre de la cola">
          <input name="name" onChange={rabbitAdmin.handleQueueChange} placeholder="notificaciones.queue" value={form.name} />
        </Field>
        <Field label="TTL mensajes (ms)">
          <input min="1" name="messageTtlMs" onChange={rabbitAdmin.handleQueueChange} placeholder="60000" type="number" value={form.messageTtlMs} />
        </Field>
        <Field label="Largo maximo">
          <input min="1" name="maxLength" onChange={rabbitAdmin.handleQueueChange} placeholder="1000" type="number" value={form.maxLength} />
        </Field>
        <Field label="Dead letter exchange">
          <select name="deadLetterExchange" onChange={rabbitAdmin.handleQueueChange} value={form.deadLetterExchange}>
            <option value="">Sin DLX</option>
            {rabbitAdmin.exchanges.map((exchange) => (
              <option key={exchange.name} value={exchange.name}>
                {exchange.name}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Dead letter routing key">
          <input name="deadLetterRoutingKey" onChange={rabbitAdmin.handleQueueChange} placeholder="notificaciones.dlq" value={form.deadLetterRoutingKey} />
        </Field>
        <label className="rabbit-checkbox">
          <input checked={form.durable} name="durable" onChange={rabbitAdmin.handleQueueChange} type="checkbox" />
          Durable
        </label>
      </div>
      <div className="orders-actions">
        <button className="orders-primary-button" disabled={rabbitAdmin.isSaving} type="submit">
          Crear cola
        </button>
      </div>
    </form>
  )
}

function QueuesTable({ rabbitAdmin }) {
  return (
    <div className="orders-table-wrap">
      <table className="orders-table">
        <thead>
          <tr>
            <th>Cola</th>
            <th>Listos</th>
            <th>Sin ACK</th>
            <th>Consumidores</th>
            <th>DLX / TTL</th>
            <th>Acciones</th>
          </tr>
        </thead>
        <tbody>
          {rabbitAdmin.queues.map((queue) => (
            <tr key={queue.name}>
              <td>{queue.name}</td>
              <td>{queue.messagesReady}</td>
              <td>{queue.messagesUnacknowledged}</td>
              <td>{queue.consumers}</td>
              <td>{describeArguments(queue.arguments)}</td>
              <td className="rabbit-actions">
                <button className="orders-table-action" onClick={() => rabbitAdmin.inspectQueue(queue.name)} type="button">
                  Ver
                </button>
                <button className="orders-table-action" onClick={() => rabbitAdmin.purge(queue.name)} type="button">
                  Purgar
                </button>
                <button className="orders-table-action rabbit-danger" onClick={() => rabbitAdmin.removeQueue(queue.name)} type="button">
                  Eliminar
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function ExchangeForm({ rabbitAdmin }) {
  const form = rabbitAdmin.exchangeForm
  return (
    <form className="orders-form" onSubmit={rabbitAdmin.submitExchange}>
      <div className="orders-form-grid">
        <Field label="Nombre del exchange">
          <input name="name" onChange={rabbitAdmin.handleExchangeChange} placeholder="notificaciones.events" value={form.name} />
        </Field>
        <Field label="Tipo">
          <select name="type" onChange={rabbitAdmin.handleExchangeChange} value={form.type}>
            {rabbitAdmin.EXCHANGE_TYPES.map((type) => (
              <option key={type} value={type}>
                {type}
              </option>
            ))}
          </select>
        </Field>
        <label className="rabbit-checkbox">
          <input checked={form.durable} name="durable" onChange={rabbitAdmin.handleExchangeChange} type="checkbox" />
          Durable
        </label>
      </div>
      <div className="orders-actions">
        <button className="orders-primary-button" disabled={rabbitAdmin.isSaving} type="submit">
          Crear exchange
        </button>
      </div>
    </form>
  )
}

function ExchangesTable({ rabbitAdmin }) {
  return (
    <div className="orders-table-wrap">
      <table className="orders-table">
        <thead>
          <tr>
            <th>Exchange</th>
            <th>Tipo</th>
            <th>Durable</th>
            <th>Acciones</th>
          </tr>
        </thead>
        <tbody>
          {rabbitAdmin.exchanges.map((exchange) => (
            <tr key={exchange.name}>
              <td>{exchange.name}</td>
              <td>{exchange.type}</td>
              <td>{exchange.durable ? 'Si' : 'No'}</td>
              <td>
                {exchange.name.startsWith('amq.') ? (
                  <span className="rabbit-muted">Reservado</span>
                ) : (
                  <button className="orders-table-action rabbit-danger" onClick={() => rabbitAdmin.removeExchange(exchange.name)} type="button">
                    Eliminar
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function BindingForm({ rabbitAdmin }) {
  const form = rabbitAdmin.bindingForm
  return (
    <form className="orders-form" onSubmit={rabbitAdmin.submitBinding}>
      <div className="orders-form-grid">
        <Field label="Exchange">
          <select name="exchange" onChange={rabbitAdmin.handleBindingChange} value={form.exchange}>
            <option value="">Selecciona exchange</option>
            {rabbitAdmin.exchanges.map((exchange) => (
              <option key={exchange.name} value={exchange.name}>
                {exchange.name}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Cola">
          <select name="queue" onChange={rabbitAdmin.handleBindingChange} value={form.queue}>
            <option value="">Selecciona cola</option>
            {rabbitAdmin.queues.map((queue) => (
              <option key={queue.name} value={queue.name}>
                {queue.name}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Routing key">
          <input name="routingKey" onChange={rabbitAdmin.handleBindingChange} placeholder="pedido.#" value={form.routingKey} />
        </Field>
      </div>
      <div className="orders-actions">
        <button className="orders-primary-button" disabled={rabbitAdmin.isSaving} type="submit">
          Crear binding
        </button>
      </div>
    </form>
  )
}

function BindingsTable({ rabbitAdmin }) {
  return (
    <div className="orders-table-wrap">
      <table className="orders-table">
        <thead>
          <tr>
            <th>Exchange</th>
            <th>Routing key</th>
            <th>Cola</th>
            <th>Acciones</th>
          </tr>
        </thead>
        <tbody>
          {rabbitAdmin.bindings.map((binding) => (
            <tr key={`${binding.exchange}-${binding.destination}-${binding.routingKey}`}>
              <td>{binding.exchange}</td>
              <td>{binding.routingKey || '(vacia)'}</td>
              <td>{binding.destination}</td>
              <td>
                <button className="orders-table-action rabbit-danger" onClick={() => rabbitAdmin.removeBinding(binding)} type="button">
                  Eliminar
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function Collapsible({ children, id, isOpen, meta, onToggle, title }) {
  const contentId = `rabbit-section-${id}`
  return (
    <article className="orders-disclosure">
      <button
        aria-controls={contentId}
        aria-expanded={isOpen}
        className="orders-disclosure-button"
        onClick={() => onToggle(id)}
        type="button"
      >
        <span className="orders-disclosure-main">
          <span className="orders-disclosure-title">{title}</span>
          {meta ? <span className="orders-disclosure-meta">{meta}</span> : null}
        </span>
        <span className="orders-disclosure-icon" aria-hidden="true">
          {isOpen ? '-' : '+'}
        </span>
      </button>
      {isOpen ? (
        <div className="orders-disclosure-content" id={contentId}>
          {children}
        </div>
      ) : null}
    </article>
  )
}

function Field({ children, label }) {
  return (
    <label className="orders-field">
      <span>{label}</span>
      {children}
    </label>
  )
}

function SummaryItem({ label, value }) {
  return (
    <div className="orders-summary-item">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  )
}

function describeArguments(args = {}) {
  const parts = []
  if (args['x-dead-letter-exchange'] !== undefined) {
    parts.push(`DLX: ${args['x-dead-letter-exchange'] || '(default)'}`)
  }
  if (args['x-message-ttl'] !== undefined) parts.push(`TTL: ${args['x-message-ttl']} ms`)
  if (args['x-max-length'] !== undefined) parts.push(`Max: ${args['x-max-length']}`)
  return parts.length ? parts.join(' | ') : '-'
}

// RabbitMQ agrega el header x-death con la razon del dead-lettering (rejected, expired, maxlen).
function deathReason(headers = {}) {
  const death = Array.isArray(headers['x-death']) ? headers['x-death'][0] : null
  return death ? `${death.reason} en ${death.queue}` : 'sin x-death'
}

function prettyPayload(payload) {
  try {
    return JSON.stringify(JSON.parse(payload), null, 2)
  } catch {
    return payload
  }
}

export default RabbitAdminSection
