package com.api.pedidos;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PedidosApplicationTests {

	// Guia: valida application class exists.
	@Test
	void applicationClassExists() {
		assertThat(PedidosApplication.class).isNotNull();
	}

}
