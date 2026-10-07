// ApexStore - contrato ICE del diagrama de despliegue corregido (Punto 3).
// Cada interfaz (lollipop) del diagrama se mapea a una interfaz Slice:
//   gestionarComprasHttp / gestionarComprasInterno -> GestionarCompras
//   iniciarPagoOrden                               -> OrquestadorPago
//   notificarTransaccionExitosa / ...Interno       -> NotificacionPago
//   autorizarCargoStripe                           -> PasarelaStripe
//   debitarTransferenciaPSE                        -> PasarelaPSE
//   generarCobroCriptoBtc                          -> PasarelaCripto
//   persistirTransaccionPostgres                   -> Persistencia
//   replicacion                                    -> Replicacion

[["java:package:apexstore"]]
module contrato
{
    enum MedioPago { Stripe, PSE, Cripto };
    enum EstadoOrden { Pendiente, Confirmada, Rechazada };

    dictionary<string, string> DatosPago;

    struct SolicitudCompra
    {
        string idOrden;
        string idCliente;
        MedioPago medio;
        double montoUSD;
        DatosPago datosPago;
    };

    struct RespuestaCompra
    {
        string idOrden;
        EstadoOrden estado;
        string referencia;
        string detalle;
    };

    struct ResultadoPago
    {
        string idOrden;
        bool exito;
        string referencia;
        string detalle;
    };

    struct Transaccion
    {
        string idOrden;
        string idCliente;
        MedioPago medio;
        double montoUSD;
        EstadoOrden estado;
        string referencia;
        string detalle;
        long creadaMs;
        long actualizadaMs;
    };

    struct ResultadoPersistencia
    {
        bool aplicada;
        Transaccion actual;
    };

    struct EntradaLog
    {
        long secuencia;
        Transaccion transaccion;
    };
    sequence<EntradaLog> SecuenciaLog;

    exception CompraException { string razon; };
    exception PasarelaException { string razon; };
    exception NoEsPrimarioException { string razon; };
    exception TransaccionNoEncontrada { string idOrden; };

    // Nodo 2 (balanceador) y Nodo 3 (ServicioCheckout): misma firma, dos lollipops.
    ["amd"] interface GestionarCompras
    {
        RespuestaCompra iniciarCompra(SolicitudCompra solicitud) throws CompraException;
        RespuestaCompra consultarOrden(string idOrden) throws CompraException;
    };

    // ProcesadorPagosContexto (Strategy Context).
    ["amd"] interface OrquestadorPago
    {
        RespuestaCompra iniciarPagoOrden(SolicitudCompra solicitud) throws CompraException;
        RespuestaCompra consultarEstadoOrden(string idOrden) throws CompraException;
    };

    // Callback asincrono: lo provee el balanceador (hacia las pasarelas)
    // y cada ProcesadorPagosContexto (hacia el balanceador).
    ["amd"] interface NotificacionPago
    {
        void notificarTransaccionExitosa(ResultadoPago resultado);
    };

    // Estrategias alojadas en Nodo 4. Validan y transforman el pago, y delegan
    // al proveedor simulado local. Responden un acuse rapido; la confirmacion
    // llega despues por NotificacionPago.
    ["amd"] interface PasarelaStripe
    {
        string autorizarCargoStripe(string idOrden, string tokenTarjeta, double montoUSD, string cvcSeguridad)
            throws PasarelaException;
    };

    ["amd"] interface PasarelaPSE
    {
        string debitarTransferenciaPSE(string idOrden, string codigoBanco, string tipoDoc, string numCuenta, double valorCOP)
            throws PasarelaException;
    };

    ["amd"] interface PasarelaCripto
    {
        string generarCobroCriptoBtc(string idOrden, string direccionWallet, string redBlockchain, long satoshis)
            throws PasarelaException;
    };

    // Nodo 5 (BD primaria y replica). Las operaciones son idempotentes.
    interface Persistencia
    {
        idempotent ResultadoPersistencia persistirTransaccionPostgres(Transaccion transaccion)
            throws NoEsPrimarioException;
        idempotent Transaccion consultarTransaccion(string idOrden) throws TransaccionNoEncontrada;
    };

    interface Replicacion
    {
        idempotent long ultimaSecuencia();
        idempotent SecuenciaLog obtenerDesde(long secuencia, int maximo);
    };

    interface HeartbeatBalanceador
    {
        idempotent void heartbeat(string emisor);
    };
};
