package apexstore.nodos;

import apexstore.comun.ComunicadorIce;
import apexstore.checkout.ServicioCheckout;
import apexstore.comun.Bitacora;
import apexstore.contrato.OrquestadorPagoPrx;
import apexstore.contrato.PasarelaCriptoPrx;
import apexstore.contrato.PasarelaPSEPrx;
import apexstore.contrato.PasarelaStripePrx;
import apexstore.contrato.PersistenciaPrx;
import apexstore.pagos.CircuitBreaker;
import apexstore.pagos.ClienteEstrategiaCripto;
import apexstore.pagos.ClienteEstrategiaPSE;
import apexstore.pagos.ClienteEstrategiaStripe;
import apexstore.pagos.ProcesadorPagosContexto;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.EndpointSelectionType;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Properties;
import com.zeroc.Ice.Util;

/**
 * Nodo 3 (una instancia por réplica): ServicioCheckout + ProcesadorPagosContexto.
 * Propiedades: Backend.Nombre, AdaptadorBackend.Endpoints, Datos.Primaria.Endpoint, Datos.Replica.Endpoint,
 * Pasarelas.Endpoint, Backend.TimeoutPasarelaMs, Backend.Breaker.Umbral, Backend.Breaker.EsperaMs.
 */
public final class NodoBackend {

    private NodoBackend() { }

    public static void main(String[] args) {
        try (Communicator c = ComunicadorIce.crear(args)) {
            iniciar(c);
            c.waitForShutdown();
        }
    }

    public static ProcesadorPagosContexto iniciar(Communicator c) {
        Properties p = c.getProperties();
        String nombre = p.getPropertyWithDefault("Backend.Nombre", "Backend");
        int timeoutPasarelaMs = p.getPropertyAsIntWithDefault("Backend.TimeoutPasarelaMs", 1500);
        int umbral = p.getPropertyAsIntWithDefault("Backend.Breaker.Umbral", 3);
        int esperaMs = p.getPropertyAsIntWithDefault("Backend.Breaker.EsperaMs", 5000);

        // Persistencia: se escribe en la primaria. Si cae, ICE prueba el siguiente endpoint (la réplica
        // promovida). La réplica en espera rechaza escrituras, así que nunca hay dos primarias activas.
        String endpointsBd = p.getProperty("Datos.Primaria.Endpoint") + ":" + p.getProperty("Datos.Replica.Endpoint");
        PersistenciaPrx persistencia = PersistenciaPrx.uncheckedCast(c.stringToProxy("persistencia:" + endpointsBd))
                .ice_endpointSelection(EndpointSelectionType.Ordered)
                .ice_invocationTimeout(1500);

        String endpointPasarelas = p.getProperty("Pasarelas.Endpoint");
        PasarelaStripePrx stripe = PasarelaStripePrx.uncheckedCast(c.stringToProxy("pasarelaStripe:" + endpointPasarelas))
                .ice_invocationTimeout(timeoutPasarelaMs);
        PasarelaPSEPrx pse = PasarelaPSEPrx.uncheckedCast(c.stringToProxy("pasarelaPSE:" + endpointPasarelas))
                .ice_invocationTimeout(timeoutPasarelaMs);
        PasarelaCriptoPrx cripto = PasarelaCriptoPrx.uncheckedCast(c.stringToProxy("pasarelaCripto:" + endpointPasarelas))
                .ice_invocationTimeout(timeoutPasarelaMs);

        ProcesadorPagosContexto procesador = new ProcesadorPagosContexto(nombre + "/Procesador", persistencia);
        procesador.registrar(new ClienteEstrategiaStripe(stripe), new CircuitBreaker(nombre + "-Stripe", umbral, esperaMs));
        procesador.registrar(new ClienteEstrategiaPSE(pse), new CircuitBreaker(nombre + "-PSE", umbral, esperaMs));
        procesador.registrar(new ClienteEstrategiaCripto(cripto), new CircuitBreaker(nombre + "-Cripto", umbral, esperaMs));

        ObjectAdapter adaptador = c.createObjectAdapter("AdaptadorBackend");
        adaptador.add(procesador, Util.stringToIdentity("procesadorPagos"));
        adaptador.add(procesador.lollipopNotificacion(), Util.stringToIdentity("notificacionProcesador"));
        OrquestadorPagoPrx procesadorLocal = OrquestadorPagoPrx.uncheckedCast(
                adaptador.createProxy(Util.stringToIdentity("procesadorPagos")));
        adaptador.add(new ServicioCheckout(procesadorLocal), Util.stringToIdentity("servicioCheckout"));
        adaptador.activate();
        Bitacora.info(nombre, "ServicioCheckout y ProcesadorPagosContexto listos");
        return procesador;
    }
}
