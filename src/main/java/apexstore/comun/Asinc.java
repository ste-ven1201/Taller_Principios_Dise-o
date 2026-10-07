package apexstore.comun;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

/** Utilidades para encadenar invocaciones asíncronas de ICE sin perder el tipo de la excepción. */
public final class Asinc {

    private Asinc() { }

    /** Desenvuelve CompletionException/ExecutionException hasta llegar a la causa real. */
    public static Throwable causa(Throwable t) {
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    /**
     * Devuelve una etapa equivalente cuya excepción (si la hay) ya no está envuelta.
     * ICE solo reconoce las excepciones de usuario declaradas en Slice si llegan "desnudas".
     */
    public static <T> CompletionStage<T> desenvolver(CompletionStage<T> etapa) {
        CompletableFuture<T> salida = new CompletableFuture<>();
        etapa.whenComplete((r, e) -> {
            if (e == null) {
                salida.complete(r);
            } else {
                salida.completeExceptionally(causa(e));
            }
        });
        return salida;
    }
}
