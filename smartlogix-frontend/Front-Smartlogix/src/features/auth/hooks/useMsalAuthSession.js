import { useMsal, useIsAuthenticated } from '@azure/msal-react'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { loginRequest } from '../../../auth/msalConfig'

/*
 * Hook de sesion basado en MSAL/Azure AD (tenant DSY1107005V).
 * Expone la misma forma de datos que el useAuthSession original
 * (session, roles, userDisplayName, userInitials, handleLogout) para
 * no tener que tocar SessionCard ni los dashboards por rol.
 */
export function useMsalAuthSession() {
  const { instance, accounts } = useMsal()
  const isAuthenticated = useIsAuthenticated()
  const account = accounts[0]

  // Los roles vienen del claim "roles" del ID token, asignados en Entra ID
  // como App Roles (ADMIN, OPERADOR_PEDIDOS, ...). Ver Fase 1 del informe.
  const roles = account?.idTokenClaims?.roles || []

  // Access token del scope access_as_user: es el que valida el API Gateway.
  // Las secciones lo leen como session.token, igual que con el login propio.
  const [accessToken, setAccessToken] = useState(null)

  useEffect(() => {
    if (!account) {
      return undefined
    }

    let cancelled = false
    instance
      .acquireTokenSilent({ ...loginRequest, account })
      .then((result) => {
        if (!cancelled) {
          setAccessToken(result.accessToken)
        }
      })
      .catch((error) => {
        console.error('No se pudo obtener el access token de Azure AD', error)
      })

    return () => {
      cancelled = true
    }
  }, [account, instance])

  const session = useMemo(() => {
    if (!isAuthenticated || !account) {
      return null
    }

    return {
      token: accessToken,
      tokenType: 'Bearer',
      expiresAt: account.idTokenClaims?.exp
        ? account.idTokenClaims.exp * 1000
        : null,
      user: {
        email: account.username,
        firstName: account.idTokenClaims?.given_name || '',
        lastName: account.idTokenClaims?.family_name || '',
        roles,
      },
    }
  }, [accessToken, account, isAuthenticated, roles])

  const userDisplayName = useMemo(() => {
    if (!session?.user) {
      return ''
    }

    const fullName = [session.user.firstName, session.user.lastName]
      .filter(Boolean)
      .join(' ')

    return fullName || session.user.email
  }, [session])

  const userInitials = useMemo(() => {
    if (!userDisplayName) {
      return 'SL'
    }

    return userDisplayName
      .split(' ')
      .slice(0, 2)
      .map((part) => part.charAt(0).toUpperCase())
      .join('')
  }, [userDisplayName])

  const handleLogin = useCallback(() => {
    // loginRedirect navega fuera de la app y MSAL retoma el flujo al volver
    // (ver handleRedirectPromise en src/auth/msalInstance.js).
    instance.loginRedirect(loginRequest).catch((error) => {
      console.error('Error en el login con Microsoft', error)
    })
  }, [instance])

  const handleLogout = useCallback(() => {
    instance.logoutRedirect({ account }).catch((error) => {
      console.error('Error al cerrar sesion en Microsoft', error)
    })
  }, [account, instance])

  // Usado por el resto de la app (apiClient) para adjuntar el access token
  // al llamar al BFF a traves del API Gateway.
  const getAccessToken = useCallback(async () => {
    if (!account) {
      return null
    }

    try {
      const result = await instance.acquireTokenSilent({
        ...loginRequest,
        account,
      })
      return result.accessToken
    } catch {
      // Si el token silencioso falla (p.ej. expiro el refresh token),
      // se repite el mismo patron de redirect que el login.
      await instance.acquireTokenRedirect(loginRequest)
      return null
    }
  }, [account, instance])

  return {
    getAccessToken,
    handleLogin,
    handleLogout,
    isAuthenticated,
    roles,
    session,
    userDisplayName,
    userInitials,
  }
}
