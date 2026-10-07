package apexstore.nodos;

import apexstore.comun.ComunicadorIce;
import apexstore.contrato.NotificacionPagoPrx;
import apexstore.pasarelas.EstrategiaCripto;
import apexstore.pasarelas.EstrategiaPSE;
import apexstore.pasarelas.EstrategiaStripe;
import apexstore.pasarelas.SimuladorPasarela;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Properties;
import com.zeroc.Ice.Util;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Nodo 4: estrategias de pago y proveedores simulados. No hay conexiones financieras reales.
 * Propiedades: AdaptadorPasarelas.Endpoints, Pasarelas.Notificacion.Proxy (balanceador),
 * Pasarelas.Banco.MinMs/MaxMs, Pasarelas.ProbRechazo, Pasarelas.LentaMs,
 * Pasarelas.Stripe.Modo / PSE.Modo / Cripto.Modo (NORMAL|CAIDA|LENTA|INESTABLE).
 */
public final class NodoPasarelas {

    /** Acceso a los motores de simulación (útil para provocar fallos en la demo). */
    public static final class Simuladores {
        public final SimuladorPasarela stripe;
        public final SimuladorPasarela pse;
        public final SimuladorPasarela cripto;

        Simuladores(SimuladorPasarela stripe, SimuladorPasarela pse, SimuladorPasarela cripto) {
            this.stripe = stripe;
            this.pse = pse;
            this.cripto = cripto;
        }
    }

    private NodoPasarelas() { }

    public static void main(String[] args) {
        try (Communicator c = ComunicadorIce.crear(args)) {
            iniciar(c);
            c.waitForShutdown();
        }
    }

    public static Simuladores iniciar(Communicator c) {
        Properties p = c.getProperties();
        ScheduledExecutorService planificador = Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r, "pasarelas-simuladas");
            t.setDaemon(true);
            return t;
        });
        NotificacionPagoPrx notificador = NotificacionPagoPrx.uncheckedCast(
                c.propertyToProxy("Pasarelas.Notificacion.Proxy")).ice_invocationTimeout(3000);
        long min = p.getPropertyAsIntWithDefault("Pasarelas.Banco.MinMs", 300);
        long max = p.getPropertyAsIntWithDefault("Pasarelas.Banco.MaxMs", 1500);
        double rechazo = Double.parseDouble(p.getPropertyWithDefault("Pasarelas.ProbRechazo", "0.05"));
        long lenta = p.getPropertyAsIntWithDefault("Pasarelas.LentaMs", 6000);

        SimuladorPasarela stripe = new SimuladorPasarela("Stripe", planificador, notificador, min, max, rechazo, lenta);
        SimuladorPasarela pse = new SimuladorPasarela("PSE", planificador, notificador, min, max, rechazo, lenta);
        SimuladorPasarela cripto = new SimuladorPasarela("Cripto", planificador, notificador, min, max, rechazo, lenta);
        aplicarModo(p, "Pasarelas.Stripe.Modo", stripe);
        aplicarModo(p, "Pasarelas.PSE.Modo", pse);
        aplicarModo(p, "Pasarelas.Cripto.Modo", cripto);

        ObjectAdapter adaptador = c.createObjectAdapter("AdaptadorPasarelas");
        adaptador.add(new EstrategiaStripe(stripe), Util.stringToIdentity("pasarelaStripe"));
        adaptador.add(new EstrategiaPSE(pse), Util.stringToIdentity("pasarelaPSE"));
        adaptador.add(new EstrategiaCripto(cripto), Util.stringToIdentity("pasarelaCripto"));
        adaptador.activate();
        apexstore.comun.Bitacora.info("Pasarelas", "Stripe, PSE y Cripto simuladas, listas");
        return new Simuladores(stripe, pse, cripto);
    }

    private static void aplicarModo(Properties p, String clave, SimuladorPasarela sim) {
        String valor = p.getProperty(clave);
        if (!valor.isEmpty()) {
            sim.setModo(SimuladorPasarela.Modo.valueOf(valor.toUpperCase()));
        }
    }
}
