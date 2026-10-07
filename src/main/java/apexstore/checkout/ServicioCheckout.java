package apexstore.checkout;

import apexstore.comun.Asinc;
import apexstore.contrato.CompraException;
import apexstore.contrato.GestionarCompras;
import apexstore.contrato.OrquestadorPagoPrx;
import apexstore.contrato.RespuestaCompra;
import apexstore.contrato.SolicitudCompra;
import com.zeroc.Ice.Current;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Punto de entrada del backend. Provee gestionarComprasInterno (lo consume el balanceador)
 * y requiere iniciarPagoOrden del ProcesadorPagosContexto de su misma réplica.
 */
public class ServicioCheckout implements GestionarCompras {

    private final OrquestadorPagoPrx procesador;

    public ServicioCheckout(OrquestadorPagoPrx procesador) {
        this.procesador = procesador;
    }

    @Override
    public CompletionStage<RespuestaCompra> iniciarCompraAsync(SolicitudCompra solicitud, Current current)
            throws CompraException {
        if (solicitud == null) {
            throw new CompraException("Solicitud vacía");
        }
        if (solicitud.idCliente == null || solicitud.idCliente.trim().isEmpty()) {
            throw new CompraException("La compra debe indicar el cliente");
        }
        SolicitudCompra s = solicitud.clone();
        if (s.idOrden == null || s.idOrden.trim().isEmpty()) {
            s.idOrden = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
        }
        return Asinc.desenvolver(procesador.iniciarPagoOrdenAsync(s));
    }

    @Override
    public CompletionStage<RespuestaCompra> consultarOrdenAsync(String idOrden, Current current)
            throws CompraException {
        return Asinc.desenvolver(procesador.consultarEstadoOrdenAsync(idOrden));
    }
}
