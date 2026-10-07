package apexstore.cliente;

import apexstore.comun.ComunicadorIce;
import apexstore.contrato.CompraException;
import apexstore.contrato.EstadoOrden;
import apexstore.contrato.GestionarComprasPrx;
import apexstore.contrato.MedioPago;
import apexstore.contrato.RespuestaCompra;
import apexstore.contrato.SolicitudCompra;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.Util;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Nodo 1 (WebApp / MobileApp). Solo conoce gestionarComprasHttp, que provee el balanceador:
 * no sabe cuántas réplicas hay ni qué pasarela procesará el pago.
 */
public class ClienteApexStore {

    private final GestionarComprasPrx balanceador;

    public ClienteApexStore(GestionarComprasPrx balanceador) {
        this.balanceador = balanceador;
    }

    public static SolicitudCompra compraStripe(String idOrden, String idCliente, double montoUSD) {
        Map<String, String> datos = new HashMap<>();
        datos.put("tokenTarjeta", "tok_visa_4242");
        datos.put("cvc", "123");
        return new SolicitudCompra(idOrden, idCliente, MedioPago.Stripe, montoUSD, datos);
    }

    public static SolicitudCompra compraPSE(String idOrden, String idCliente, double montoUSD) {
        Map<String, String> datos = new HashMap<>();
        datos.put("codigoBanco", "1007");
        datos.put("tipoDoc", "CC");
        datos.put("numCuenta", "123456789");
        return new SolicitudCompra(idOrden, idCliente, MedioPago.PSE, montoUSD, datos);
    }

    public static SolicitudCompra compraCripto(String idOrden, String idCliente, double montoUSD) {
        Map<String, String> datos = new HashMap<>();
        datos.put("direccionWallet", "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh");
        datos.put("redBlockchain", "bitcoin-mainnet");
        return new SolicitudCompra(idOrden, idCliente, MedioPago.Cripto, montoUSD, datos);
    }

    public RespuestaCompra comprar(SolicitudCompra solicitud) throws CompraException {
        return balanceador.iniciarCompra(solicitud);
    }

    public CompletableFuture<RespuestaCompra> comprarAsync(SolicitudCompra solicitud) {
        return balanceador.iniciarCompraAsync(solicitud);
    }

    public RespuestaCompra consultar(String idOrden) throws CompraException {
        return balanceador.consultarOrden(idOrden);
    }

    /** Consulta periódica hasta que la orden deje de estar Pendiente (la confirmación es asíncrona). */
    public RespuestaCompra esperarResultado(String idOrden, long maximoMs) throws CompraException, InterruptedException {
        long limite = System.currentTimeMillis() + maximoMs;
        RespuestaCompra r = consultar(idOrden);
        while (r.estado == EstadoOrden.Pendiente && System.currentTimeMillis() < limite) {
            Thread.sleep(100);
            r = consultar(idOrden);
        }
        return r;
    }

    public static void main(String[] args) throws Exception {
        try (Communicator c = ComunicadorIce.crear(args)) {
            GestionarComprasPrx prx = GestionarComprasPrx.checkedCast(c.propertyToProxy("Cliente.Balanceador.Proxy"));
            if (prx == null) {
                throw new IllegalStateException("Propiedad Cliente.Balanceador.Proxy inválida");
            }
            ClienteApexStore cliente = new ClienteApexStore(prx.ice_invocationTimeout(10000));
            SolicitudCompra[] compras = {
                compraStripe("ORD-" + UUID.randomUUID().toString().substring(0, 6), "cliente-1", 120.50),
                compraPSE("ORD-" + UUID.randomUUID().toString().substring(0, 6), "cliente-1", 35.00),
                compraCripto("ORD-" + UUID.randomUUID().toString().substring(0, 6), "cliente-1", 500.00)
            };
            for (SolicitudCompra s : compras) {
                RespuestaCompra inicial = cliente.comprar(s);
                System.out.println(s.medio + " " + inicial.idOrden + " -> " + inicial.estado + " (" + inicial.detalle + ")");
                RespuestaCompra fin = cliente.esperarResultado(s.idOrden, 10000);
                System.out.println("   resultado final: " + fin.estado + " ref=" + fin.referencia + " | " + fin.detalle);
            }
        }
    }
}
