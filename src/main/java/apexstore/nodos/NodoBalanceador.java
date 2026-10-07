package apexstore.nodos;

import apexstore.comun.ComunicadorIce;
import apexstore.balanceador.BalanceadorCarga;
import apexstore.balanceador.BalanceadorCarga.Replica;
import apexstore.comun.Bitacora;
import apexstore.contrato.GestionarComprasPrx;
import apexstore.contrato.NotificacionPagoPrx;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Properties;
import com.zeroc.Ice.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Nodo 2. Propiedades: AdaptadorBalanceador.Endpoints, Balanceador.Replicas (cantidad),
 * Balanceador.Replica.N.Endpoint, Balanceador.HealthMs, Balanceador.TimeoutMs.
 */
public final class NodoBalanceador {

    private NodoBalanceador() { }

    public static void main(String[] args) {
        try (Communicator c = ComunicadorIce.crear(args)) {
            iniciar(c);
            c.waitForShutdown();
        }
    }

    public static BalanceadorCarga iniciar(Communicator c) {
        Properties p = c.getProperties();
        int total = p.getPropertyAsIntWithDefault("Balanceador.Replicas", 2);
        int timeoutMs = p.getPropertyAsIntWithDefault("Balanceador.TimeoutMs", 8000);
        long healthMs = p.getPropertyAsIntWithDefault("Balanceador.HealthMs", 1000);

        List<Replica> replicas = new ArrayList<>();
        for (int i = 1; i <= total; i++) {
            String endpoint = p.getProperty("Balanceador.Replica." + i + ".Endpoint");
            GestionarComprasPrx servicio = GestionarComprasPrx.uncheckedCast(
                    c.stringToProxy("servicioCheckout:" + endpoint)).ice_invocationTimeout(timeoutMs);
            NotificacionPagoPrx procesador = NotificacionPagoPrx.uncheckedCast(
                    c.stringToProxy("notificacionProcesador:" + endpoint)).ice_invocationTimeout(timeoutMs);
            replicas.add(new Replica("R" + i, servicio, procesador));
        }
        ScheduledExecutorService planificador = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "health-check");
            t.setDaemon(true);
            return t;
        });
        BalanceadorCarga balanceador = new BalanceadorCarga(replicas, planificador, healthMs);

        ObjectAdapter adaptador = c.createObjectAdapter("AdaptadorBalanceador");
        adaptador.add(balanceador, Util.stringToIdentity("balanceador"));
        adaptador.add(balanceador.lollipopNotificacion(), Util.stringToIdentity("notificacionBalanceador"));
        adaptador.activate();
        Bitacora.info("Balanceador", "listo con " + total + " réplicas");
        return balanceador;
    }
}
