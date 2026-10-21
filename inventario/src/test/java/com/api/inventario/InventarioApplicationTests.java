package com.api.inventario;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/*
 * Prueba minima de sanidad.
 * Confirma que la clase principal de Spring Boot existe y puede ser referenciada.
 */
class InventarioApplicationTests {

	// Guia: valida application class exists.
	@Test
	void applicationClassExists() {
		assertThat(InventarioApplication.class).isNotNull();
	}

}
