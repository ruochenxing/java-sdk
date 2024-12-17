/*
 * Copyright 2024-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.ai.mcp.client.stdio;

import org.junit.jupiter.api.Timeout;

import org.springframework.ai.mcp.client.AbstractMcpSyncClientTests;

/**
 * Tests for the {@link McpSyncClient} with {@link StdioServerTransport}.
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 */
@Timeout(15) // Giving extra time beyond the client timeout
class McpSyncClientTests extends AbstractMcpSyncClientTests {

	@Override
	protected void createMcpTransport() {
		ServerParameters stdioParams = ServerParameters.builder("npx")
			.args("-y", "@modelcontextprotocol/server-everything", "dir")
			.build();
		this.mcpTransport = new StdioServerTransport(stdioParams);
	}

	@Override
	protected void onStart() {

	}

	@Override
	protected void onClose() {

	}

}
