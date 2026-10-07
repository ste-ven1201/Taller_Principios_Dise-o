package apexstore.pagos;

import apexstore.contrato.MedioPago;
import apexstore.contrato.PasarelaPSEPrx;
import apexstore.contrato.SolicitudCompra;

import java.util.concurrent.CompletionStage;

/** Estrategia de débito bancario. Requiere debitarTransferenciaPSE. */
public class ClienteEstrategiaPSE implements EstrategiaPago {

    private static final double COP_POR_USD = 4000.0;

    private final PasarelaPSEPrx pasarela;

    public ClienteEstrategiaPSE(PasarelaPSEPrx pasarela) {
        this.pasarela = pasarela;
    }

    @Override
    public MedioPago medio() {
        return MedioPago.PSE;
    }

    @Override
    public void validar(SolicitudCompra s) {
        // La validación se realiza en la estrategia alojada en el nodo de pasarelas.
    }

    @Override
    public CompletionStage<String> cobrar(SolicitudCompra s) {
        double valorCOP = Math.round(s.montoUSD * COP_POR_USD);
        return pasarela.debitarTransferenciaPSEAsync(s.idOrden, EstrategiaPago.datoRemoto(s, "codigoBanco"),
                EstrategiaPago.datoRemoto(s, "tipoDoc"), EstrategiaPago.datoRemoto(s, "numCuenta"), valorCOP);
    }
}
