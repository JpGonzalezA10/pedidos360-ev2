import { useState } from 'react'
import UsersAdminSection from '../../authAdmin/components/UsersAdminSection'
import SessionCard from '../../auth/components/SessionCard'
import InventorySection from '../../inventory/components/InventorySection'
import OrdersSection from '../../orders/components/OrdersSection'
import RabbitAdminSection from '../../rabbitAdmin/components/RabbitAdminSection'
import ShippingSection from '../../shipping/components/ShippingSection'
import { ADMIN_SECTIONS } from '../constants/adminSections'
import '../styles/admin-dashboard.css'
import AdminHome from './AdminHome'

/*
 * Dashboard para usuarios ADMIN.
 * Separa cada dominio por seccion para mantener el desacoplamiento del frontend.
 */
function AdminDashboard({ authSession }) {
  const [activeSection, setActiveSection] = useState('home')

  return (
    <main className="admin-dashboard">
      <header className="admin-header">
        <div>
          <p className="admin-kicker">SmartLogix Admin</p>
          <h1>Panel administrador</h1>
        </div>
        <button
          className="admin-logout-button"
          onClick={authSession.handleLogout}
          type="button"
        >
          Cerrar sesi&oacute;n
        </button>
      </header>

      <nav className="admin-section-nav" aria-label="Secciones administrativas">
        {ADMIN_SECTIONS.map((section) => (
          <button
            className={
              activeSection === section.id
                ? 'admin-section-button admin-section-button-active'
                : 'admin-section-button'
            }
            key={section.id}
            onClick={() => setActiveSection(section.id)}
            type="button"
          >
            {section.label}
          </button>
        ))}
      </nav>

      <div className="admin-layout">
        <aside className="admin-session-card">
          <SessionCard
            onLogout={authSession.handleLogout}
            roles={authSession.roles}
            session={authSession.session}
            userDisplayName={authSession.userDisplayName}
            userInitials={authSession.userInitials}
          />
        </aside>

        <div className="admin-section-content">
          {renderAdminSection(activeSection, authSession.session)}
        </div>
      </div>
    </main>
  )
}

function renderAdminSection(activeSection, session) {
  if (activeSection === 'auth') {
    return <UsersAdminSection session={session} />
  }

  if (activeSection === 'inventory') {
    return <InventorySection session={session} />
  }

  if (activeSection === 'orders') {
    return <OrdersSection session={session} />
  }

  if (activeSection === 'shipping') {
    return <ShippingSection session={session} />
  }

  if (activeSection === 'messaging') {
    return <RabbitAdminSection session={session} />
  }

  return <AdminHome session={session} />
}

export default AdminDashboard
