package apexstore.pagos;

import apexstore.contrato.MedioPago;
import apexstore.contrato.SolicitudCompra;

import java.util.concurrent.CompletionStage;

/**
 * Interfaz común del patrón Strategy. Para agregar un medio de pago (RAS-04) basta con
 * implementar esta interfaz y registrarla en el ProcesadorPagosContexto: ninguna otra clase cambia.
 */
public interface EstrategiaPago {

    MedioPago medio();

    /** Valida los datos propios del medio. Lanza IllegalArgumentException si son incorrectos. */
    void validar(SolicitudCompra solicitud);

    /** Despacha el cobro y devuelve el acuse (referencia). La confirmación llega por callback. */
    CompletionStage<String> cobrar(SolicitudCompra solicitud);

    /** Pasa los datos sin validar para que la estrategia remota del nodo de pasarelas los valide. */
    static String datoRemoto(SolicitudCompra solicitud, String clave) {
        return solicitud.datosPago == null ? null : solicitud.datosPago.get(clave);
    }
}
