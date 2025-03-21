/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransport;
import io.modelcontextprotocol.spec.ServerMcpTransport;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for {@link McpSyncServer} using {@link HttpServletSseServerTransport}.
 *
 * @author Christian Tzolov
 */
@Deprecated
@Timeout(15) // Giving extra time beyond the client timeout
class ServletSseMcpSyncServerDeprecatedTests extends AbstractMcpSyncServerDeprecatedTests {

	@Override
	protected ServerMcpTransport createMcpTransport() {
		return new HttpServletSseServerTransport(new ObjectMapper(), "/mcp/message");
	}

}
