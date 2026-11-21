package com.example.thesis

import android.app.Application
import com.example.thesis.diagnostics.CrashLogger
import com.example.thesis.notifications.ThesisSessionContainer

class ThesisApplication : Application() {
    // Inicializacion perezosa para no activar Keystore ni XMTP durante pruebas instrumentadas aisladas.
    internal val sessionContainer: ThesisSessionContainer by lazy {
        ThesisSessionContainer(applicationContext)
    }

    override fun onCreate() {
        super.onCreate()
        // Instalamos el registro de fallos lo antes posible para capturar cierres inesperados en cualquier pantalla.
        CrashLogger.install(this)
    }
}
