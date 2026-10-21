package com.api.rabbitadmin.dto;

/*
 * Expresiones regulares compartidas por los DTO de entrada.
 * Un nombre valido: 3 a 200 caracteres, empieza con letra o numero y solo usa letras, numeros, '.', '_' o '-'.
 */
public final class ValidationPatterns {

    public static final String RESOURCE_NAME = "^[A-Za-z0-9][A-Za-z0-9._-]{2,199}$";
    public static final String RESOURCE_NAME_MESSAGE =
            "debe tener 3 a 200 caracteres, iniciar con letra o numero y usar solo letras, numeros, '.', '_' o '-'";

    /** Routing key: palabras separadas por punto; admite comodines * y # para exchanges topic. */
    public static final String ROUTING_KEY = "^[A-Za-z0-9._*#-]{0,255}$";
    public static final String ROUTING_KEY_MESSAGE =
            "solo admite letras, numeros, '.', '_', '-', '*' o '#' (maximo 255 caracteres)";

    public static final String EXCHANGE_TYPE = "^(?i)(direct|topic|fanout|headers)$";

    private ValidationPatterns() {
    }
}
